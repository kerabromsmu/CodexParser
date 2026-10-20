# Codex Parser

A Java 27 desktop app for finding local Codex conversations and exporting timestamped Markdown transcripts.

## Run

Install a **JDK 27** and set `JAVA_HOME` to its installation directory. The project uses a Java 27 Gradle toolchain and does not enable preview features.

```bash
./gradlew run
```

On Windows, use `gradlew.bat run`.

Create a distribution with launch scripts and all runtime dependencies:

```bash
./gradlew installDist
build/install/codex-parser/bin/codex-parser
```

The Windows launcher is `build\install\codex-parser\bin\codex-parser.bat`. Launchers require Java 27 on `PATH` or through `JAVA_HOME`. `./gradlew distZip` creates a portable distribution archive. The first build needs network access to download Gradle and dependencies; the app itself works offline.

## Browse and export

1. The app searches `$CODEX_HOME` (if set) and your user home's `.codex` folder, including `sessions` and `archived_sessions`.
2. Use **Add log folder…** for another Codex home or a directory containing rollout `.jsonl` or `.jsonl.zst` files. Added locations apply to the current app session. **Refresh** picks up new conversations and messages.
3. Search by title, project, session ID, source path, or chat content. Search matches literal text without case sensitivity, including user prompts, assistant replies, progress messages, and code blocks in plain or compressed logs. Metadata matches appear immediately; after a short typing pause, complete chat messages are searched in the background, including text beyond the preview limit. A status line indicates when content search has finished. Clearing the field restores every conversation, and **Refresh** reruns the current search against refreshed logs. Hover over a row to see its full source path. Use Ctrl/Cmd or Shift to select several conversations. The focused selection appears as a Markdown text preview.
4. Choose **Conversation only** for user messages and assistant replies, including progress updates. Choose **Everything** to include instructions, tool calls/results, metadata, and every original log record in expandable JSON details.
5. Select local time or UTC. Dates include milliseconds and a UTC offset; the document header identifies the timezone.
6. Click **Export selected…**. A single conversation opens a file save dialog; several conversations open a directory chooser and produce one Markdown file each. Existing filenames receive numeric suffixes. The completion report lists saved paths and any failures or malformed records.

Preview text is capped at 200,000 characters, with an explicit truncation notice. Exported files contain the complete selected transcript. Source logs remain read-only. Cancellation removes the current temporary output and retains completed exports.

Content search uses the same chat-message and scaffolding rules as conversation exports. It excludes tool records, system/developer instructions, encrypted fields, and standalone instruction scaffolding. The export-mode selector does not change search scope. Guardian sessions remain searchable when listed. Search reads source files as streams without retaining full transcripts; unreadable or malformed files are reported through **Warnings**, while other conversations continue processing.

## Supported logs

The app reads local Codex rollout **JSONL** logs, including older **Zstandard-compressed `.jsonl.zst`** files, from CLI, desktop, and IDE sessions. Both formats work in the session folders, archives, and custom locations. Compressed files are decompressed as streams inside the app; no extraction or installed `zstd` command is required. The packaged launchers enable the native access required by the bundled decompression library on Java 27.

Supported records include `session_meta`, `response_item`, completed-message events, and legacy `user_message` and `agent_message` events. Conversation titles use `session_index.jsonl` when available, then the first user prompt or session ID.

Conversation mode prefers canonical response messages per speaker and uses completed-message or legacy events when that speaker has no canonical messages. This avoids duplicate representations while retaining repeated messages. Standalone known environment/instruction scaffolding is omitted. Images and other non-text attachments become placeholders.

Everything mode keeps each original JSONL record, including duplicate representations, unknown fields, and encrypted content as recorded. Malformed lines are preserved verbatim; conversation mode skips them and adds a warning. Missing timestamps are labeled unknown. Each export reads up to the source file's size at its start, limiting compressed bytes before decompression for `.jsonl.zst` files; later appended activity is picked up on the next export. Corrupt or truncated compressed files appear as scan warnings or failed exports, and other conversations continue processing. SQLite databases and `history.jsonl` prompt history are not conversation transcript sources.

## Verify

```bash
./gradlew test installDist
```

The regular tests use synthetic logs and do not access personal conversations. To test the real Swing selection, preview, timezone, cancellation, and file-dialog workflows, use a graphical desktop:

```bash
./gradlew desktopTest
```

Or use a virtual display on Linux:

```bash
xvfb-run -a -s '-screen 0 1600x1000x24' ./gradlew desktopTest
```

The desktop test saves a screenshot with synthetic data to `build/desktop-smoke.png`. Test reports are under `build/reports/tests/`.
