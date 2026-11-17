# Process Dump (Rider plugin)

Writes a Windows minidump (`.dmp`) of the process a debug session is attached to, running or
suspended, through dbghelp's `MiniDumpWriteDump`. Rider can open dumps (Run → Open Core Dump) but
has no way to save one from a live native session; this fills that gap.

## Dump kinds

| Kind | Contents | Typical size |
|---|---|---|
| `mini` | thread stacks and the memory they reference, handles, thread/module info, memory map | tens of MB |
| `full` | every committed, readable page | about the process's commit size (UE editor: 10–40 GB) |

A full dump is refused when the target drive has less free space than the process's committed
memory plus 1 GB.

## Where

- **Run menu** and the **Debug tool window ⋮ (More) menu** → *Write Process Dump* → *Write Mini Dump…* /
  *Write Full Dump…* (save dialog, background task with progress and Cancel, notification with
  *Show in Explorer* / *Copy path*).
- **MCP** (tools on the IDE's built-in MCP server, needs the bundled MCP Server plugin):
  - `write_process_dump(kind, path?, session?, pid?, waitSeconds=10)` — starts the dump; returns
    `DONE`/`FAILED` if it finishes within `waitSeconds` (max 15), else `RUNNING` with a job id.
  - `process_dump_status(jobId, waitSeconds=10, cancel=false)` — progress, result, or cancel.

Default path: `<solution>\Saved\Dumps\<exe>_<pid>_<yyyyMMdd-HHmmss>_<kind>.dmp`.

## Build

```powershell
$env:TMP='H:\Dev\tmp-gradle'; $env:TEMP='H:\Dev\tmp-gradle'
$env:JAVA_HOME='C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\jbr'
.\gradlew.bat buildPlugin test --console=plain "-Dorg.gradle.java.installations.paths=C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\jbr"
```

Output: `build/distributions/process-dump-<version>.zip` → Rider → Settings → Plugins → ⚙ →
*Install Plugin from Disk*.

## Updates

Releases are published from `v*` tags, and
`https://raw.githubusercontent.com/gaetan-deturche/rider-process-dump/main/updatePlugins.xml` is the
custom plugin repository. From 0.3.0 the plugin adds that repository and its own "Updates from"
source at startup, so after one install from disk, Rider offers new versions by itself.
