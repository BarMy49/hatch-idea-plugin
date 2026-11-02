package dev.barmy.hatch

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.nio.file.Path
import javax.swing.Timer

class HatchStartup : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) project.getService(HatchDiscovery::class.java).refresh() }
    }
}

data class HatchDiscoveryResult(val directory: Path, val environments: Result<List<HatchEnvironment>>, val packageName: String?, val metadataError: String?)

@Service(Service.Level.PROJECT)
class HatchDiscovery(private val project: Project) : Disposable {
    private val settings get() = project.getService(HatchSettings::class.java)
    private val listeners = mutableListOf<() -> Unit>()
    var results: List<HatchDiscoveryResult> = emptyList()
        private set
    var busy = false
        private set
    private var generation = 0
    private var disposed = false
    private var trusted = TrustedProjects.isProjectTrusted(project)
    private val debounce = Timer(600) { refresh() }.apply { isRepeats = false }
    private val trustTimer = Timer(1000) {
        val current = TrustedProjects.isProjectTrusted(project)
        if (current != trusted) { trusted = current; refresh() }
    }.apply { start() }
    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { event -> paths().any { path -> event.path == path.resolve("pyproject.toml").toString().replace('\\', '/') || event.path == path.resolve("hatch.toml").toString().replace('\\', '/') } })
                    ApplicationManager.getApplication().invokeLater { if (!disposed) debounce.restart() }
            }
        })
    }
    fun paths(): List<Path> {
        val base = project.basePath?.let(Path::of)
        return (listOfNotNull(base) + settings.linkedPaths.mapNotNull { value ->
            runCatching { Path.of(value).let { if (it.isAbsolute) it else base?.resolve(it) } }.getOrNull()
        }).map { it.toAbsolutePath().normalize() }.distinct()
    }
    fun listen(parent: Disposable, listener: () -> Unit) {
        listeners.add(listener); Disposer.register(parent) { listeners.remove(listener) }
    }
    private fun changed() = listeners.toList().forEach { it() }
    fun refresh() {
        if (disposed || project.isDisposed) return
        val request = ++generation
        if (!TrustedProjects.isProjectTrusted(project)) { busy = false; changed(); return }
        hatchWriteIntentAction { FileDocumentManager.getInstance().saveAllDocuments() }
        val executable = settings.executable
        val directories = paths().filter(HatchProjects::isProject)
        busy = true; changed()
        ApplicationManager.getApplication().executeOnPooledThread {
            val discovered = directories.map { directory ->
                val environments = runCatching { HatchModel.parse(capture(executable, directory, listOf("env", "show", "--json"))) }
                val metadata = if (environments.isSuccess) runCatching {
                    capture(executable, directory, listOf("project", "metadata", "name")).trim().also { require(it.isNotBlank() && !it.contains('\n')) { "Invalid package name" } }
                } else Result.failure(IllegalStateException("Task discovery failed"))
                HatchDiscoveryResult(directory, environments, metadata.getOrNull(), metadata.exceptionOrNull()?.message)
            }
            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed || request != generation || !TrustedProjects.isProjectTrusted(project)) return@invokeLater
                results = discovered; busy = false
                hatchWriteIntentAction { if (settings.autoConfigurations) HatchRunner.setup(project, discovered) }
                changed()
            }
        }
    }
    private fun capture(executable: String, directory: Path, arguments: List<String>): String {
        val output = HatchCommand.capture(executable, directory, arguments)
        check(!output.isTimeout) { "Hatch discovery timed out after 20 seconds" }
        check(output.exitCode == 0) { output.stderr.ifBlank { output.stdout.ifBlank { "Exit code ${output.exitCode}" } } }
        return output.stdout
    }
    override fun dispose() { disposed = true; generation++; debounce.stop(); trustTimer.stop(); listeners.clear() }
}
