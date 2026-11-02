package dev.barmy.hatch

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.TreeSpeedSearch
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

private data class Entry(val title: String, val path: Path? = null, val task: HatchTask? = null) {
    override fun toString() = title
}

class HatchToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val settings = project.getService(HatchSettings::class.java)
    private val root = DefaultMutableTreeNode(Entry("Hatch"))
    private val model = DefaultTreeModel(root)
    private val tree = JTree(model)
    private val status = JLabel(" ")
    private val details = JTextArea(4, 30).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val refresh = JButton("Refresh")
    private val run = JButton("Run")
    private val save = JButton("Save configuration")
    private val unlink = JButton("Unlink")
    private val cached = mutableMapOf<Path, List<HatchEnvironment>>()
    private val stale = mutableSetOf<Path>()
    @Volatile private var disposed = false

    private fun paths(): List<Path> {
        val base = project.basePath?.let(Path::of)
        return (listOfNotNull(base) + settings.linkedPaths.mapNotNull {
            runCatching { Path.of(it).let { p -> if (p.isAbsolute) p else base?.resolve(p) }?.normalize() }.getOrNull()
        }).distinct()
    }

    init {
        val actions = com.intellij.openapi.actionSystem.DefaultActionGroup(
            hatchAction("Refresh", com.intellij.icons.AllIcons.Actions.Refresh) { discover() },
            hatchAction("Run", com.intellij.icons.AllIcons.Actions.Execute, { selected()?.task != null && selected()?.path !in stale }) { execute(false) },
            hatchAction("Link Project", com.intellij.icons.AllIcons.General.Add) { link() },
            hatchAction("Expand All", com.intellij.icons.AllIcons.Actions.Expandall) { var i = 0; while (i < tree.rowCount) tree.expandRow(i++) },
            hatchAction("Collapse All", com.intellij.icons.AllIcons.Actions.Collapseall) { for (i in tree.rowCount - 1 downTo 0) tree.collapseRow(i) },
            hatchAction("Hatch Settings", com.intellij.icons.AllIcons.General.Settings) { com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, HatchConfigurable::class.java) },
        )
        add(com.intellij.openapi.actionSystem.ActionManager.getInstance().createActionToolbar("HatchBrowser", actions, true).apply { targetComponent = tree }.component, BorderLayout.NORTH)
        val popup = com.intellij.openapi.actionSystem.DefaultActionGroup(
            hatchAction("Run", com.intellij.icons.AllIcons.Actions.Execute, { selected()?.task != null && selected()?.path !in stale }) { execute(false) },
            hatchAction("Save Configuration", com.intellij.icons.AllIcons.Actions.MenuSaveall, { selected()?.task?.confirmation == null && selected()?.task != null && selected()?.path !in stale }) { execute(true) },
            hatchAction("Unlink Project", com.intellij.icons.AllIcons.General.Remove, { unlink.isEnabled }) { unlink.doClick() },
        )
        com.intellij.openapi.actionSystem.ActionManager.getInstance().createActionPopupMenu("HatchTree", popup).let { tree.componentPopupMenu = it.component }
        tree.cellRenderer = object : com.intellij.ui.ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, focus: Boolean) {
                val entry = (value as? DefaultMutableTreeNode)?.userObject as? Entry ?: return
                append(entry.title)
                if (entry.path in stale) append("  (stale)", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
                icon = when {
                    entry.task?.confirmation != null -> com.intellij.icons.AllIcons.General.Remove
                    entry.task?.arguments?.firstOrNull() == "env" -> com.intellij.icons.AllIcons.Nodes.Folder
                    entry.task?.arguments?.firstOrNull() == "build" -> com.intellij.icons.AllIcons.Toolwindows.ToolWindowBuild
                    entry.task != null -> com.intellij.icons.AllIcons.Actions.Execute
                    entry.title == "Build" -> com.intellij.icons.AllIcons.Toolwindows.ToolWindowBuild
                    entry.title == "Environments" -> com.intellij.icons.AllIcons.Nodes.Folder
                    else -> com.intellij.icons.AllIcons.Nodes.Module
                }
                val latest = project.getService(HatchExecution::class.java).runs.firstOrNull { it.directory == entry.path?.toAbsolutePath()?.normalize() && it.task.arguments == entry.task?.arguments }
                if (latest != null) append("  ${latest.status}", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        project.getService(HatchExecution::class.java).listen(this) { tree.repaint() }
        tree.isRootVisible = false; tree.showsRootHandles = true; tree.toggleClickCount = 0
        TreeSpeedSearch.installOn(tree)
        tree.addTreeSelectionListener { updateActions() }
        HatchTreeActions.install(tree) { execute(false) }
        add(JScrollPane(tree), BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, 4)).apply {
            add(status, BorderLayout.NORTH)
            add(JButton("Show Details").apply { addActionListener { Messages.showInfoMessage(project, details.text, "Hatch Details") } }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        refresh.addActionListener { discover() }; run.addActionListener { execute(false) }
        save.addActionListener { execute(true) }
        unlink.addActionListener {
            val path = selected()?.path ?: return@addActionListener
            settings.linkedPaths = settings.linkedPaths.filterNot {
                val p = Path.of(it)
                (if (p.isAbsolute) p else Path.of(project.basePath.orEmpty()).resolve(p)).normalize() == path
            }.toMutableList()
            discover()
        }
        project.getService(HatchDiscovery::class.java).listen(this) { renderDiscovery() }
        updateActions()
        val discovery = project.getService(HatchDiscovery::class.java)
        if (discovery.busy || discovery.results.isNotEmpty()) renderDiscovery() else discover()
    }

    private fun treeKey(value: Any): String {
        val entry = (value as DefaultMutableTreeNode).userObject as Entry
        return "${entry.path}|${entry.title}|${entry.task?.arguments}"
    }
    private fun selected() = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Entry
    private fun updateActions() {
        val entry = selected()
        run.isEnabled = entry?.task != null && entry.path !in stale
        save.isEnabled = entry?.task?.let { it.confirmation == null } == true
        unlink.isEnabled = entry?.path != null && entry.path != project.basePath?.let(Path::of)
    }
    private fun link() = uiAction { linkLocked() }

    private fun linkLocked() {
        val file = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, null) ?: return
        val path = Path.of(file.path).normalize()
        if (!HatchProjects.isProject(path)) {
            Messages.showErrorDialog(project, "Choose a folder containing pyproject.toml or hatch.toml.", "Link Hatch Project"); return
        }
        if (path !in paths()) {
            val base = project.basePath?.let(Path::of)
            settings.linkedPaths.add(if (base != null && path.startsWith(base)) base.relativize(path).toString() else path.toString())
        }
        discover()
    }
    private fun execute(saveOnly: Boolean) = uiAction { executeLocked(saveOnly) }

    private fun executeLocked(saveOnly: Boolean) {
        val entry = selected() ?: return
        val task = entry.task ?: run {
            tree.selectionPath?.let { if (tree.isExpanded(it)) tree.collapsePath(it) else tree.expandPath(it) }
            return
        }
        val path = entry.path ?: return
        if (path in stale) { status.text = "Refresh this project successfully before running tasks."; return }
        if (!TrustedProjects.isProjectTrusted(project)) { status.text = "Trust this project before running Hatch."; return }
        if (task.confirmation != null && Messages.showYesNoDialog(project, task.confirmation,
                "Confirm Hatch Action", Messages.getWarningIcon()) != Messages.YES) return
        FileDocumentManager.getInstance().saveAllDocuments()
        try {
            val binary = settings.executable
            if (saveOnly) {
                HatchRunner.save(project, task.label, path, task.arguments)
                status.text = "Saved '${task.label}' in Run > Edit Configurations."
            } else {
                project.getService(HatchExecution::class.java).launch(task.label, path, binary, task)
                status.text = "Started '${task.label}'."
                details.text = "Project: $path\nExecutable: $binary\nArguments: ${task.arguments.joinToString(" ")}\nOutput and task status appear in Hatch Tasks."
            }
        } catch (error: Throwable) {
            reportFailure(error)
        }
    }
    private fun node(parent: DefaultMutableTreeNode, title: String, path: Path? = null, task: HatchTask? = null) =
        DefaultMutableTreeNode(Entry(title, path, task)).also(parent::add)
    private fun populate(parent: DefaultMutableTreeNode, path: Path, envs: List<HatchEnvironment>) {
        val environments = node(parent, "Environments", path)
        for (env in envs) {
            val group = node(environments, "${env.name} (${env.type})", path)
            HatchTasks.environment(env).forEach { node(group, it.label, path, it) }
        }
        val build = node(parent, "Build", path)
        HatchTasks.build.forEach { node(build, it.label, path, it) }
        val utilities = node(parent, "Utilities", path)
        HatchTasks.utilities.forEach { node(utilities, it.label, path, it) }
    }
    private fun discover() = uiAction { discoverLocked() }

    private fun discoverLocked() { project.getService(HatchDiscovery::class.java).refresh() }
    private fun renderDiscovery() {
        if (disposed || project.isDisposed) return
        val discovery = project.getService(HatchDiscovery::class.java)
        if (!TrustedProjects.isProjectTrusted(project)) { status.text = "Trust this project to discover Hatch tasks."; return }
        if (discovery.busy) { status.text = "Discovering Hatch tasks?"; return }
        val binary = settings.executable
        val results = discovery.results.map { it.directory to it.environments }
        val projects = results.map { it.first }
                val expanded = java.util.Collections.list(tree.getExpandedDescendants(javax.swing.tree.TreePath(root)) ?: java.util.Collections.emptyEnumeration()).map { it.path.map(::treeKey) }.toSet()
                val selection = tree.selectionPath?.path?.map(::treeKey)
                val firstLoad = root.childCount == 0
                refresh.isEnabled = true; root.removeAllChildren()
                var failures = 0
                val errors = mutableListOf<String>()
                results.forEach { (path, result) ->
                    val group = node(root, path.fileName?.toString() ?: path.toString(), path)
                    result.onSuccess { cached[path] = it; stale.remove(path); populate(group, path, it) }.onFailure {
                        stale.add(path)
                        cached[path]?.let { populate(group, path, it) }
                        failures++
                        errors.add("Project: $path\nExecutable: $binary\nCommand: env show --json\n${it.message ?: it}")
                        node(group, "Discovery failed — see details below", path)
                    }
                }
                if (projects.isEmpty()) node(root, "Link a folder containing pyproject.toml or hatch.toml")
                model.reload()
                fun restore(n: DefaultMutableTreeNode) {
                    val path = javax.swing.tree.TreePath(n.path)
                    val key = path.path.map(::treeKey)
                    if (key in expanded || (firstLoad && n.level <= 2)) tree.expandPath(path)
                    if (key == selection) tree.selectionPath = path
                    java.util.Collections.list(n.children()).forEach { restore(it as DefaultMutableTreeNode) }
                }
                restore(root)
                status.text = if (failures > 0) "$failures project(s) failed. Check executable and configuration."
                    else "${projects.size} project(s). Double-click a task to run."
                details.text = if (errors.isEmpty()) "Executable: $binary\nReady. Select a script or build task; double-click or press Enter to run."
                    else errors.joinToString("\n\n").take(30000)
                val metadataErrors = discovery.results.filter { it.environments.isSuccess && it.metadataError != null }
                if (metadataErrors.isNotEmpty()) {
                    status.text = "Tasks ready; run configuration setup needs attention."
                    details.append("\n" + metadataErrors.joinToString("\n") { "${it.directory}: ${it.metadataError}. Check Hatch metadata and Refresh." })
                }
                details.caretPosition = 0
                updateActions()
    }
    /** Swing events are on EDT but do not automatically acquire the IDE write-intent lock. */
    private fun uiAction(action: () -> Unit) {
        if (disposed || project.isDisposed) return
        hatchWriteIntentAction {
            try { action() } catch (error: Throwable) { reportFailure(error) }
        }
    }

    private fun reportFailure(error: Throwable) {
        if (error is ControlFlowException || error is Error) throw error
        Logger.getInstance(HatchToolWindowPanel::class.java).warn("Hatch UI action failed", error)
        status.text = "Hatch action failed — see details below."
        details.text = error.stackTraceToString()
        details.caretPosition = 0
        refresh.isEnabled = true
        Messages.showErrorDialog(project, "${error.javaClass.simpleName}: ${error.message}\n\nFull details are available in the Hatch tool window.", "Hatch Action Failed")
    }

    override fun dispose() { disposed = true }
}
