package dev.barmy.hatch

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.*
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import java.nio.file.Path
import java.time.Instant

enum class HatchRunStatus { Starting, Running, Stopping, Succeeded, Failed, Cancelled }

class HatchRun(val label: String, val directory: Path, val task: HatchTask, val console: ConsoleView) {
    val started: Instant = Instant.now()
    var ended: Instant? = null
    var status = HatchRunStatus.Starting
    var exitCode: Int? = null
    var handler: KillableColoredProcessHandler? = null
    val active get() = ended == null
    override fun toString() = "$label — $status"
}

@Service(Service.Level.PROJECT)
class HatchExecution(private val project: Project) : Disposable {
    val runs = mutableListOf<HatchRun>()
    private val listeners = mutableListOf<() -> Unit>()
    var selected: HatchRun? = null
        private set
    private var disposed = false

    fun listen(parent: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(parent, Disposable { listeners.remove(listener) })
    }
    private fun changed() { listeners.toList().forEach { it() } }
    fun select(run: HatchRun) { selected = run; changed() }
    fun launch(label: String, directory: Path, executable: String, task: HatchTask) {
        val path = directory.toAbsolutePath().normalize()
        val existing = runs.firstOrNull { it.active && it.directory == path && it.task.arguments == task.arguments }
        if (existing != null) { select(existing); show(); return }
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        Disposer.register(this, console)
        val run = HatchRun(label, path, task, console)
        runs.add(0, run); selected = run; changed(); show()
        console.print("Project: $path\nExecutable: $executable\nArguments: ${task.arguments.joinToString(" ")}\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val handler = KillableColoredProcessHandler(HatchCommand.line(executable, path, task.arguments))
                ApplicationManager.getApplication().invokeLater {
                    if (disposed) { handler.destroyProcess(); return@invokeLater }
                    run.handler = handler
                    console.attachToProcess(handler)
                    handler.addProcessListener(object : ProcessAdapter() {
                        override fun processTerminated(event: ProcessEvent) {
                            ApplicationManager.getApplication().invokeLater {
                                if (disposed) return@invokeLater
                                run.exitCode = event.exitCode
                                run.status = if (run.status == HatchRunStatus.Stopping) HatchRunStatus.Cancelled
                                    else if (event.exitCode == 0) HatchRunStatus.Succeeded else HatchRunStatus.Failed
                                run.ended = Instant.now()
                                console.print("\n${run.status} (exit code ${event.exitCode})\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                                trim(); changed()
                            }
                        }
                    })
                    val stopping = run.status == HatchRunStatus.Stopping
                    if (!stopping) run.status = HatchRunStatus.Running
                    handler.startNotify()
                    if (stopping) handler.destroyProcess()
                    changed()
                }
            } catch (error: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    if (disposed) return@invokeLater
                    run.status = HatchRunStatus.Failed; run.ended = Instant.now()
                    console.print("Unable to launch Hatch: ${error.message}\nCheck the executable in Settings > Tools > Hatch.\n", ConsoleViewContentType.ERROR_OUTPUT)
                    trim(); changed()
                }
            }
        }
    }
    fun stop(run: HatchRun) {
        if (!run.active) return
        if (run.status == HatchRunStatus.Stopping) run.handler?.killProcess()
        else { run.status = HatchRunStatus.Stopping; run.handler?.destroyProcess() }
        changed()
    }
    fun clearFinished() {
        runs.filter { !it.active }.toList().forEach { runs.remove(it); Disposer.dispose(it.console) }
        if (selected !in runs) selected = runs.firstOrNull()
        changed()
    }
    private fun trim() {
        runs.filter { !it.active }.drop(50).forEach { runs.remove(it); Disposer.dispose(it.console) }
        if (selected !in runs) selected = runs.firstOrNull()
    }
    private fun show() { ToolWindowManager.getInstance(project).getToolWindow("Hatch Tasks")?.activate(null) }
    override fun dispose() {
        disposed = true
        runs.filter { it.active }.forEach { it.handler?.destroyProcess() }
        listeners.clear()
    }
}
