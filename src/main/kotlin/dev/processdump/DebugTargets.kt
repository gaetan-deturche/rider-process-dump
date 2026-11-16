package dev.processdump

import com.intellij.execution.process.BaseProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object DebugTargets {
    /** PID of the process a debug session debugs, or null when it has none (yet). */
    fun pidOf(session: XDebugSession): Int? {
        val process = session.debugProcess
        val pid = nativeProcessId(process) ?: (process.processHandler as? BaseProcessHandler<*>)?.process?.pid()
        return pid?.toInt()?.takeIf { it > 0 }
    }

    fun activeSessions(project: Project?): List<XDebugSession> =
        (project?.let { listOf(it) } ?: ProjectManager.getInstance().openProjects.toList())
            .flatMap { XDebuggerManager.getInstance(it).debugSessions.toList() }
            .filter { !it.isStopped }

    // RiderCppDebugProcess.getProcessId(): IOptProperty<Long>; reflective so rider-cpp stays optional.
    private fun nativeProcessId(process: Any): Long? = runCatching {
        val property = process.javaClass.getMethod("getProcessId").invoke(process) ?: return null
        if (property is Number) return property.toLong()
        val getter = property.javaClass.methods.first { it.name == "getValueOrNull" && it.parameterCount == 0 }
        getter.isAccessible = true
        (getter.invoke(property) as? Number)?.toLong()
    }.getOrNull()
}

object DumpPaths {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** `<solution>\Saved\Dumps\<exe>_<pid>_<time>_<kind>.dmp`. */
    fun defaultFile(project: Project?, pid: Int, kind: DumpKind): File {
        val folder = project?.basePath?.let { File(it, "Saved/Dumps") }
            ?: File(System.getProperty("user.home"), "Dumps")
        val exe = ProcessHandle.of(pid.toLong()).flatMap { it.info().command() }
            .map { File(it).nameWithoutExtension }.orElse("process")
        return File(folder, "${exe}_${pid}_${LocalDateTime.now().format(stamp)}_${kind.id}.dmp")
    }
}
