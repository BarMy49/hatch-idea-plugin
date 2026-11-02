package dev.barmy.hatch

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.*
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.*

fun hatchAction(text: String, icon: javax.swing.Icon, enabled: () -> Boolean = { true }, perform: () -> Unit) =
    object : AnAction(text, text, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() }
        override fun actionPerformed(e: AnActionEvent) = perform()
    }

class HatchOutputFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HatchOutputPanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false).apply { setDisposer(panel) })
    }
}

class HatchOutputPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val service = project.getService(HatchExecution::class.java)
    private val items = DefaultListModel<HatchRun>()
    private val list = JBList(items)
    private val output = JPanel(BorderLayout())
    private val summary = JLabel()
    private var displayed: HatchRun? = null
    private var updating = false
    private val timer = Timer(1000) { updateSummary() }
    init {
        val actions = DefaultActionGroup(
            hatchAction("Stop (click again to force)", AllIcons.Actions.Suspend, { service.selected?.active == true }) { service.selected?.let(service::stop) },
            hatchAction("Rerun", AllIcons.Actions.Restart, { service.selected?.active == false }) { rerun() },
            hatchAction("Clear Finished", AllIcons.Actions.GC) { service.clearFinished() },
        )
        add(ActionManager.getInstance().createActionToolbar("HatchOutput", actions, true).apply { targetComponent = this@HatchOutputPanel }.component, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JBScrollPane(list), output).apply { resizeWeight = .25 }, BorderLayout.CENTER)
        list.addListSelectionListener { if (!updating) list.selectedValue?.let(service::select) }
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component {
                super.getListCellRendererComponent(list, value, index, selected, focus)
                val run = value as? HatchRun
                icon = when (run?.status) {
                    HatchRunStatus.Succeeded -> AllIcons.General.InspectionsOK
                    HatchRunStatus.Failed -> AllIcons.General.Error
                    HatchRunStatus.Cancelled -> AllIcons.Actions.Suspend
                    else -> AllIcons.Actions.Execute
                }
                toolTipText = run?.directory?.toString()
                return this
            }
        }
        service.listen(this) { refresh() }; refresh(); timer.start()
    }
    private fun refresh() {
        updating = true
        items.clear(); service.runs.forEach(items::addElement)
        list.setSelectedValue(service.selected, true)
        updateSummary()
        if (displayed === service.selected && output.componentCount > 0) { updating = false; list.repaint(); return }
        displayed = service.selected
        output.removeAll()
        service.selected?.let { run ->
            output.add(run.console.component, BorderLayout.CENTER)
            output.add(ActionManager.getInstance().createActionToolbar("HatchConsole", DefaultActionGroup(*run.console.createConsoleActions()), false).apply { targetComponent = output }.component, BorderLayout.WEST)
            output.add(JLabel("${run.directory} • ${run.status} • ${java.time.Duration.between(run.started, run.ended ?: java.time.Instant.now()).seconds}s"), BorderLayout.SOUTH)
        } ?: output.add(JLabel("Run a task from the Hatch tool window.", SwingConstants.CENTER))
        updating = false; output.revalidate(); output.repaint()
    }
    private fun updateSummary() {
        service.selected?.let { run -> summary.text = "${run.directory} ? ${run.status} ? ${java.time.Duration.between(run.started, run.ended ?: java.time.Instant.now()).seconds}s" }
    }
    private fun rerun() = WriteIntentReadAction.run(Runnable {
        val run = service.selected ?: return@Runnable
        if (!TrustedProjects.isProjectTrusted(project)) return@Runnable
        val confirmation = run.task.confirmation
        if (confirmation != null && Messages.showYesNoDialog(project, confirmation, "Confirm Hatch Action", Messages.getWarningIcon()) != Messages.YES) return@Runnable
        FileDocumentManager.getInstance().saveAllDocuments()
        service.launch(run.label, run.directory, project.getService(HatchSettings::class.java).executable, run.task)
    })
    override fun dispose() { timer.stop() }
}
