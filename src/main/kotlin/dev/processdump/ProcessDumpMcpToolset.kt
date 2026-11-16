package dev.processdump

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.project.Project
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

@Suppress("FunctionName")
class ProcessDumpMcpToolset : McpToolset {

    @McpTool(name = "write_process_dump")
    @McpDescription(
        "Writes a Windows minidump (.dmp) of the process a debug session is attached to (or of any pid), with " +
            "dbghelp MiniDumpWriteDump. Works while the process runs or is suspended at a breakpoint or exception. " +
            "Runs as an IDE background task the user can see and cancel. Refuses when the target drive lacks space. " +
            "Returns DONE/FAILED when the dump finishes within waitSeconds, else RUNNING with a job id for " +
            "process_dump_status. Open the result with xdebug_memory_dump.",
    )
    suspend fun write_process_dump(
        @McpDescription(
            "Required. 'mini' = thread stacks and the memory they reference, handles, thread and module info (tens of MB). " +
                "'full' = all committed memory (about the process's commit size; a UE editor is 10-40 GB and takes minutes).",
        )
        kind: String,
        @McpDescription("Output .dmp path. Default <solution>\\Saved\\Dumps\\<exe>_<pid>_<yyyyMMdd-HHmmss>_<kind>.dmp. An existing file is never overwritten.")
        path: String? = null,
        @McpDescription("Debug session name (case-insensitive substring), needed when several sessions are active.")
        session: String? = null,
        @McpDescription("Dump this process id instead of a debug session's process.")
        pid: Int? = null,
        @McpDescription("Seconds to wait for the dump to finish before returning RUNNING (0-15, default 10).")
        waitSeconds: Int = 10,
    ): String {
        val dumpKind = DumpKind.parse(kind) ?: mcpFail("kind must be 'mini' or 'full' (got '$kind').")
        val project = currentCoroutineContext().projectOrNull
        val targetPid = pid ?: sessionPid(project, session)
        val file = path?.let { File(it) } ?: DumpPaths.defaultFile(project, targetPid, dumpKind)
        if (file.exists()) mcpFail("${file.path} already exists; pass another path.")
        val job = try {
            DumpJobs.getInstance().start(project, targetPid, dumpKind, file)
        } catch (refused: DumpRefused) {
            mcpFail(refused.message ?: "Dump refused.")
        }
        return report(job, waitSeconds)
    }

    @McpTool(name = "process_dump_status")
    @McpDescription(
        "Reports a dump job started by write_process_dump: RUNNING with bytes written (and % for full dumps), " +
            "DONE with path and size, CANCELLED or FAILED. Waits up to waitSeconds for the job to finish. " +
            "cancel=true stops a running job and deletes the partial file.",
    )
    suspend fun process_dump_status(
        @McpDescription("Job id returned by write_process_dump, e.g. dump-3.")
        jobId: String,
        @McpDescription("Seconds to wait for the job to finish (0-15, default 10).")
        waitSeconds: Int = 10,
        @McpDescription("Cancel the job.")
        cancel: Boolean = false,
    ): String {
        val job = DumpJobs.getInstance().job(jobId) ?: mcpFail("No dump job '$jobId'.")
        if (cancel) job.cancelRequested = true
        return report(job, waitSeconds)
    }

    private suspend fun report(job: DumpJobs.Job, waitSeconds: Int): String {
        val outcome = withTimeoutOrNull(waitSeconds.coerceIn(0, MAX_WAIT_SECONDS) * 1000L) { job.outcome.await() }
        return DumpJobs.describe(job, outcome)
    }

    // Never guesses between sessions: zero or several matches fail with the list.
    private fun sessionPid(project: Project?, filter: String?): Int {
        val candidates = DebugTargets.activeSessions(project)
            .filter { filter.isNullOrBlank() || it.sessionName.contains(filter.trim(), ignoreCase = true) }
            .mapNotNull { session -> DebugTargets.pidOf(session)?.let { session to it } }
        candidates.singleOrNull()?.let { return it.second }
        val listing = DebugTargets.activeSessions(null).joinToString("\n") {
            "  - \"${it.sessionName}\" pid=${DebugTargets.pidOf(it) ?: "?"} (${it.project.basePath})"
        }.ifEmpty { "  (none)" }
        val problem = if (candidates.isEmpty()) "No debug session with a process matches" else "Several debug sessions match"
        mcpFail("$problem; pass 'session' or 'pid'. Active sessions:\n$listing")
    }

    private companion object {
        const val MAX_WAIT_SECONDS = 15
    }
}
