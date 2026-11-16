package dev.processdump

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager

abstract class WriteDumpAction(private val kind: DumpKind) : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val session = session(e)
        e.presentation.isEnabled = session != null && DebugTargets.pidOf(session) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = session(e) ?: return
        val pid = DebugTargets.pidOf(session) ?: return
        val title = "Write ${kind.label} Dump"
        val suggested = DumpPaths.defaultFile(project, pid, kind)
        suggested.parentFile.mkdirs()
        val descriptor = FileSaverDescriptor(title, "${kind.label} dump of ${session.sessionName} (pid $pid)", "dmp")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(suggested.parentFile.toPath(), suggested.name) ?: return
        try {
            DumpJobs.getInstance().start(project, pid, kind, target.file)
        } catch (refused: DumpRefused) {
            Messages.showErrorDialog(project, refused.message, title)
        }
    }

    private fun session(e: AnActionEvent): XDebugSession? =
        (e.getData(XDebugSession.DATA_KEY) ?: e.project?.let { XDebuggerManager.getInstance(it).currentSession })
            ?.takeIf { !it.isStopped }
}

class WriteMiniDumpAction : WriteDumpAction(DumpKind.MINI)

class WriteFullDumpAction : WriteDumpAction(DumpKind.FULL)
