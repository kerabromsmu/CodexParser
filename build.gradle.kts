plugins {
    id("java")
    application
}

group = "se.dimage"
version = "1.0-SNAPSHOT"

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
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

repositories {
    mavenCentral()
}

dependencies {
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
    shouldRunAfter(tasks.test)
}
