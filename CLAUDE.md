# rider-process-dump (agent notes)

Rider plugin **Process Dump** (id `dev.processdump`), created 2026-11-05. Kotlin frontend only (no
.NET backend, no RD protocol), platform RD 2026.3, since/until build 263 (moved from 2026.2 on
2026-11-16). Remote: **github gaetan-deturche/rider-process-dump (public, branch `main`)** since
2026-11-16. Ask the user before every commit, push or tag.

**Release / auto-update:** bump `pluginVersion`, commit, push, then `git tag v<ver> && git push origin
v<ver>` → `.github/workflows/release.yml` builds the zip, publishes a GitHub Release and regenerates
`updatePlugins.xml` on `main` (id, version, release-asset url, since/until from `gradle.properties`).
Rider gets updates through the custom plugin repository
`https://raw.githubusercontent.com/gaetan-deturche/rider-process-dump/main/updatePlugins.xml`
(Settings → Plugins → ⚙ → Manage Plugin Repositories); a plugin installed from disk never updates.
CI runs `buildPlugin` only (the tests dump a Windows process). Never run `gradlew --stop`: the plugin
repos share one Gradle daemon pool.

## Design

- **Writer** (`MiniDumpWriter.kt`): JNA (since 263 the bundled plugin `com.intellij.modules.jna`,
  hence the `<depends>` + `bundledPlugin`; before that it sat in core `util-8.jar`; it has `Kernel32`
  but no `MiniDumpWriteDump` binding) → own `DbgHelpLibrary` interface loaded from
  `%SystemRoot%\System32\dbghelp.dll` by full path. Runs from the IDE process against the target's
  PID; works while Rider's debugger holds the process suspended (threads are already stopped, the
  faulting thread's context is the exception site). No exception stream is written (the debugger's
  exception record isn't reachable), so WinDbg `.ecxr` has nothing to switch to; use the stacks.
- **Callback** is installed only for cancellation. `MINIDUMP_CALLBACK_INPUT` is `pack(4)` →
  `CallbackType` at offset **12** on x64 (not 16). Include/Module/Thread callbacks MUST return TRUE
  or threads/modules are dropped; the test parses the stream directory to catch a wrong offset.
- **PID**: `RiderCppDebugProcess.getProcessId(): IOptProperty<Long>` read reflectively (rider-cpp
  stays optional); fallback `BaseProcessHandler.process.pid()` (best effort for non-native sessions).
- **Jobs** (`DumpJobs`, app service): every dump, menu or MCP, runs as a cancellable
  `Task.Backgroundable`; MCP polls through `CompletableDeferred` with ≤15 s waits (the mcp-proxy's
  20 s response-header timeout). Full dumps are refused up front if free space < committed + 1 GB
  (`committedBytes` = `VirtualQueryEx` walk of MEM_COMMIT readable regions).
- **MCP**: `McpToolset` on the IDE's built-in MCP server (`com.intellij.mcpServer`, optional
  dependency via `process-dump-mcp.xml`), same mechanism as UnrealLink's `UnrealMcpToolset`. Tools
  surface in the proxy as `mcp__mcp-proxy__jetbrains__write_process_dump` /
  `..._process_dump_status`; project from `currentCoroutineContext().projectOrNull`.
- **Actions**: group `ProcessDump.Group` in `RunMenu` and `XDebugger.ToolWindow.TopToolbar3.Extra`.

## Build

Same environment as `H:\Dev\rider-mcp-plugin` (see its CLAUDE.md): TMP/TEMP outside AppData,
JAVA_HOME = IntelliJ IDEA 2026.1's JBR 25. Command in README.md. Tests spawn `ping.exe` and dump it;
`jna.boot.library.path` is pointed at the platform's `plugins/jna-plugin/lib/jna/amd64` in
`build.gradle.kts` (was `lib/jna/amd64` before 263).

**Platform bump checklist** (what broke 262 → 263): the SDK string from
`cache-redirector.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/rider/riderRD/maven-metadata.xml`;
bundled plugins renaming their folders (`mcpserver` → `mcpserver-plugin`, id unchanged) or splitting
out of core (JNA); platform APIs becoming product modules that need `bundledModule(...)` to compile
(XDebugger = `intellij.platform.debugger`; find the jar, then its `name` in `product-info.json`
`layout[]`); and the runtime-only lookups the compiler can't check: the
`RiderCppDebugProcess.getProcessId` reflection, and the action group ids in `plugin.xml`.
