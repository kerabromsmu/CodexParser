plugins {
    id("java")
    application
}

group = "se.dimage"
version = "1.0-SNAPSHOT"

val hostOs = System.getProperty("os.name").lowercase().let {
    when {
        it.startsWith("windows") -> "windows"
        it.startsWith("mac") -> "macos"
        it.startsWith("linux") -> "linux"
        else -> "unsupported"
    }
}
val hostArch = when (val arch = System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64" -> "x86_64"
    "arm64", "aarch64" -> "aarch64"
    else -> arch
}
val hostPlatform = "$hostOs-$hostArch"
val swtPlatform = providers.gradleProperty("swtPlatform").getOrElse(hostPlatform)
val swtArtifacts = mapOf(
    "linux-x86_64" to "gtk.linux.x86_64",
    "windows-x86_64" to "win32.win32.x86_64",
    "macos-x86_64" to "cocoa.macosx.x86_64",
    "macos-aarch64" to "cocoa.macosx.aarch64",
)
val swtArtifact = swtArtifacts[swtPlatform]
    ?: throw GradleException("Unsupported SWT platform '$swtPlatform'. Supported: ${swtArtifacts.keys.joinToString()}")
val nativeJvmArgs = listOf("--enable-native-access=ALL-UNNAMED") +
    if (swtPlatform.startsWith("macos-")) listOf("-XstartOnFirstThread") else emptyList()

java {
    toolchain.languageVersion = JavaLanguageVersion.of(27)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 27
    options.encoding = "UTF-8"
}

application {
    mainClass = "se.dimage.codexparser.CodexParserApp"
    applicationName = "codex-parser"
    applicationDefaultJvmArgs = nativeJvmArgs
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.eclipse.platform:org.eclipse.swt.$swtArtifact:3.135.0") {
        // The concrete fragment includes the API and natives. Avoid the generic
        // SWT POM's Maven-only osgi.platform dependency selection.
        isTransitive = false
    }
    compileOnly("org.jetbrains:annotations:26.0.2")
    testCompileOnly("org.jetbrains:annotations:26.0.2")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.github.luben:zstd-jni:1.5.7-14")
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.run {
    doFirst {
        check(swtPlatform == hostPlatform) { "Cannot run $swtPlatform natives on $hostPlatform" }
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveClassifier = swtPlatform
}

tasks.test {
    useJUnitPlatform {
        excludeTags("desktop")
    }
}

tasks.register<Test>("desktopTest") {
    description = "Runs desktop workflow tests; requires a display (or xvfb-run)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(27)
    }
    useJUnitPlatform {
        includeTags("desktop")
    }
    systemProperty("desktop.jvmArgs", nativeJvmArgs.joinToString(" "))
    systemProperty("desktop.classpath", sourceSets["test"].runtimeClasspath.asPath)
    doFirst {
        check(swtPlatform == hostPlatform) { "Cannot test $swtPlatform natives on $hostPlatform" }
    }
    shouldRunAfter(tasks.test)
}

tasks.register<JavaExec>("nativeDialogSmoke") {
    description = "Opens the native UI with synthetic logs for manual file/folder dialog checks."
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "se.dimage.codexparser.SwtDesktopScenario"
    args("smoke", layout.buildDirectory.dir("native-smoke").get().asFile.absolutePath)
    jvmArgs(nativeJvmArgs)
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(27) }
    doFirst {
        check(swtPlatform == hostPlatform) { "Cannot run $swtPlatform natives on $hostPlatform" }
    }
}
