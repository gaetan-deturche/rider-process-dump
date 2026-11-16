package dev.processdump

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import kotlinx.coroutines.CompletableDeferred
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DumpRefused(message: String) : Exception(message)

/** Every dump (menu action or MCP) runs as a cancellable IDE background task tracked here by job id. */
@Service(Service.Level.APP)
class DumpJobs {
    class Job(val id: String, val pid: Int, val exe: String, val kind: DumpKind, val file: File) {
        val startedAt = System.currentTimeMillis()
        val outcome = CompletableDeferred<DumpOutcome>()
        @Volatile var expectedBytes: Long? = null
        @Volatile var bytesWritten = 0L
        @Volatile var cancelRequested = false

        val target get() = "$exe (pid $pid)"
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val ids = AtomicInteger()

    fun job(id: String): Job? = jobs[id.trim()]

    fun start(project: Project?, pid: Int, kind: DumpKind, file: File): Job {
        val process = ProcessHandle.of(pid.toLong()).orElse(null)?.takeIf { it.isAlive }
            ?: throw DumpRefused("Process $pid is not running.")
        val exe = process.info().command().map { File(it).name }.orElse("pid $pid")
        val job = Job("dump-${ids.incrementAndGet()}", pid, exe, kind, file.absoluteFile)
        jobs[job.id] = job
        forgetOldJobs()
        ApplicationManager.getApplication().invokeLater { ProgressManager.getInstance().run(DumpTask(project, job)) }
        return job
    }

    private fun forgetOldJobs() {
        val finished = jobs.values.filter { it.outcome.isCompleted }.sortedBy { it.startedAt }
        finished.dropLast(KEPT_FINISHED_JOBS).forEach { jobs.remove(it.id) }
    }

    private class DumpTask(private val project: Project?, private val job: Job) :
        Task.Backgroundable(project, "Writing ${job.kind.label.lowercase()} dump of ${job.target}", true) {

        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            val refusal = checkSpace()
            val outcome = refusal ?: MiniDumpWriter.write(job.pid, job.kind, job.file) {
                job.bytesWritten = job.file.length()
                val expected = job.expectedBytes
                if (expected != null && expected > 0) {
                    indicator.isIndeterminate = false
                    indicator.fraction = (job.bytesWritten.toDouble() / expected).coerceIn(0.0, 1.0)
                }
                indicator.text2 = progressText(job)
                indicator.isCanceled || job.cancelRequested
            }
            finish(outcome)
        }

        override fun onCancel() = finish(DumpOutcome.Cancelled)

        override fun onThrowable(error: Throwable) = finish(DumpOutcome.Failed(error.message ?: error.toString()))

        // A full dump is about the process's committed memory; refuse up front rather than fill the drive.
        private fun checkSpace(): DumpOutcome? {
            val folder = job.file.parentFile ?: return null
            folder.mkdirs()
            val needed = if (job.kind == DumpKind.FULL) {
                val committed = MiniDumpWriter.committedBytes(job.pid)
                job.expectedBytes = committed
                (committed ?: 0L) + FULL_DUMP_MARGIN
            } else {
                MINI_DUMP_RESERVE
            }
            val free = folder.usableSpace
            if (free >= needed) return null
            return DumpOutcome.Failed(
                "Not enough space on ${folder.toPath().root}: needs about ${StringUtil.formatFileSize(needed)}, " +
                    "${StringUtil.formatFileSize(free)} free.",
            )
        }

        private fun finish(outcome: DumpOutcome) {
            if (!job.outcome.complete(outcome)) return
            if (outcome is DumpOutcome.Written) job.bytesWritten = outcome.bytes
            notify(outcome)
        }

        private fun notify(outcome: DumpOutcome) {
            val group = NotificationGroupManager.getInstance().getNotificationGroup("Process Dump")
            val kind = job.kind.label
            val notification = when (outcome) {
                is DumpOutcome.Written -> group.createNotification(
                    "$kind dump written",
                    StringUtil.escapeXmlEntities(
                        "${job.target} → ${outcome.file.path} (${StringUtil.formatFileSize(outcome.bytes)}, " +
                            "${StringUtil.formatDuration(outcome.millis)})",
                    ),
                    NotificationType.INFORMATION,
                ).apply {
                    addAction(NotificationAction.createSimple("Show in Explorer") { RevealFileAction.openFile(outcome.file.toPath()) })
                    addAction(NotificationAction.createSimple("Copy path") {
                        CopyPasteManager.getInstance().setContents(StringSelection(outcome.file.path))
                    })
                }
                DumpOutcome.Cancelled -> group.createNotification("$kind dump cancelled", job.target, NotificationType.INFORMATION)
                is DumpOutcome.Failed -> group.createNotification(
                    "$kind dump failed",
                    StringUtil.escapeXmlEntities("${job.target}: ${outcome.message}"),
                    NotificationType.ERROR,
                )
            }
            notification.notify(project)
        }
    }

    companion object {
        private const val KEPT_FINISHED_JOBS = 20
        private const val MINI_DUMP_RESERVE = 512L shl 20
        private const val FULL_DUMP_MARGIN = 1L shl 30

        fun getInstance(): DumpJobs = service()

        fun progressText(job: Job): String {
            val written = StringUtil.formatFileSize(job.bytesWritten)
            val expected = job.expectedBytes?.takeIf { it > 0 } ?: return "$written written"
            val percent = (job.bytesWritten * 100 / expected).coerceIn(0, 100)
            return "$written of ~${StringUtil.formatFileSize(expected)} written ($percent%)"
        }

        /** One-line job state for MCP replies. */
        fun describe(job: Job, outcome: DumpOutcome?): String {
            val head = "job=${job.id} · ${job.kind.id} dump of ${job.target}"
            return when (outcome) {
                null -> "RUNNING $head → ${job.file.path} · ${progressText(job)} · " +
                    "${StringUtil.formatDuration(System.currentTimeMillis() - job.startedAt)} elapsed. " +
                    "Poll process_dump_status(jobId=\"${job.id}\")."
                is DumpOutcome.Written -> "DONE $head → ${outcome.file.path} · " +
                    "${StringUtil.formatFileSize(outcome.bytes)} in ${StringUtil.formatDuration(outcome.millis)}. " +
                    "Open it with xdebug_memory_dump(dumpFilePath=\"${outcome.file.path.replace("\\", "\\\\")}\")."
                DumpOutcome.Cancelled -> "CANCELLED $head (partial file deleted)."
                is DumpOutcome.Failed -> "FAILED $head: ${outcome.message}"
            }
        }
    }
}
