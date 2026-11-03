package dev.barmy.hatch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.jetbrains.python.PythonInfo
import com.jetbrains.python.psi.LanguageLevel
import com.jetbrains.python.sdk.ModuleOrProject
import com.jetbrains.python.sdk.PythonSdkAdditionalData
import com.jetbrains.python.sdk.PythonSdkType
import com.jetbrains.python.sdk.add.v2.DetectedSelectableInterpreter
import com.jetbrains.python.sdk.add.v2.PathHolder
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Python's wizard API changed in 262. Keep reflective calls confined to this adapter.
 * Both branches delegate SDK initialization (including flavor/additional data) to Python.
 */
internal object HatchPythonSdk {
    data class Download(val version: String, val title: String, internal val sdk: Any) {
        override fun toString() = title
    }

    private fun type(name: String) = Class.forName(name, true, PythonSdkType::class.java.classLoader)
    private fun invoke(method: Method, receiver: Any?, vararg args: Any?): Any? = try {
        method.invoke(receiver, *args)
    } catch (error: InvocationTargetException) { throw error.targetException }

    fun downloads(): List<Download> {
        val candidates = invoke(type("com.jetbrains.python.sdk.PySdkToInstallKt").getMethod("getSdksToInstall"), null) as List<*>
        return candidates.filterNotNull().map { sdk ->
            val installation = invoke(sdk.javaClass.getMethod("getInstallation"), sdk)!!
            val release = invoke(installation.javaClass.getMethod("getRelease"), installation)!!
            val version = invoke(release.javaClass.getMethod("getVersion"), release) as String
            Download(version, invoke(sdk.javaClass.getMethod("getName"), sdk) as String, sdk)
        }.filter { it.version.matches(Regex("\\d+\\.\\d+(?:\\.\\d+)?")) }
            .sortedWith(compareByDescending<Download> { it.version.substringBefore('.').toInt() }
                .thenByDescending { it.version.split('.')[1].toInt() }
                .thenByDescending { it.version.split('.').getOrNull(2)?.toInt() ?: 0 })
    }

    fun install(version: String): String {
        val candidate = downloads().firstOrNull { it.version == version }
            ?: error("Python $version is no longer available from this IDE. Choose another version in the wizard.")
        val common = type("com.jetbrains.python.sdk.add.v2.CommonKt")
        val method = common.methods.single { it.name == "installBaseSdk" }
        val sdk = if (method.parameterCount == 1) invoke(method, null, candidate.sdk)
        else invoke(method, null, candidate.sdk, allSdks())
        val path = (sdk as? Sdk)?.homePath ?: error("Python installation did not complete. Retry or choose an installed Python.")
        check(Files.isRegularFile(Path.of(path))) { "Installed Python was not found at $path" }
        return path
    }

    private fun allSdks(): List<Sdk> = ReadAction.compute<List<Sdk>, RuntimeException> {
        ProjectJdkTable.getInstance().allJdks.toList()
    }

    fun sameInterpreter(home: String?, interpreter: Path): Boolean = home != null &&
        runCatching { Path.of(home).toAbsolutePath().normalize() == interpreter.toAbsolutePath().normalize() }.getOrDefault(false)

    fun configure(project: Project, module: Module, interpreter: Path, version: String) {
        val existing = allSdks().filter { it.sdkType is PythonSdkType && sameInterpreter(it.homePath, interpreter) }
        val sdk = existing.firstOrNull { it.sdkAdditionalData is PythonSdkAdditionalData } ?: create(project, interpreter, version)
        check(sdk.sdkAdditionalData is PythonSdkAdditionalData) { "Python SDK setup returned an incomplete interpreter" }
        ApplicationManager.getApplication().invokeAndWait {
            if (project.isDisposed || module.isDisposed) return@invokeAndWait
            hatchWriteIntentAction {
                WriteAction.run<RuntimeException> {
                    val table = ProjectJdkTable.getInstance()
                    val registered = table.allJdks.firstOrNull {
                        it.sdkType is PythonSdkType && sameInterpreter(it.homePath, interpreter) && it.sdkAdditionalData is PythonSdkAdditionalData
                    } ?: sdk.also {
                        // Discard only this plugin's broken entry for this exact interpreter.
                        existing.filter { old -> old.name == "Hatch (${project.name})" && old.sdkAdditionalData == null }
                            .forEach(table::removeJdk)
                        if (table.allJdks.none { old -> old === it }) table.addJdk(it)
                    }
                    ProjectRootManager.getInstance(project).projectSdk = registered
                    val model = ModuleRootManager.getInstance(module).modifiableModel
                    try { model.inheritSdk(); model.commit() } finally { if (!model.isDisposed) model.dispose() }
                }
            }
        }
    }

    private fun create(project: Project, interpreter: Path, version: String): Sdk = runBlockingCancellable {
        val local = invoke(type("com.intellij.platform.eel.provider.EelProviderUtil").getMethod("getLocalEel"), null)!!
        val fsType = try { type("com.jetbrains.python.sdk.add.v2.EelFileSystem") }
        catch (_: ClassNotFoundException) { type("com.jetbrains.python.sdk.add.v2.FileSystem\$Eel") }
        val fs = fsType.constructors.single { it.parameterCount == 1 }.newInstance(local)
        val selected = DetectedSelectableInterpreter(PathHolder.Eel(interpreter),
            PythonInfo(LanguageLevel.fromPythonVersionSafe(version) ?: error("Unrecognized Python version: $version"), false), false)
        val setup = type("com.jetbrains.python.sdk.add.v2.CommonKt").methods.single {
            it.name == "setupSdk" && it.parameterTypes.first().name.endsWith("PythonSelectableInterpreter")
        }
        val args = if (setup.parameterCount == 7)
            arrayOf(selected, ModuleOrProject.ProjectOnly(project), allSdks().filter { it.sdkAdditionalData != null }, fs, null, true)
        else arrayOf(selected, ModuleOrProject.ProjectOnly(project), fs, null, true)
        val result = suspendCoroutineUninterceptedOrReturn<Any> { continuation -> invoke(setup, null, *args, continuation)!! }
        val success = invoke(result.javaClass.getMethod("getSuccessOrNull"), result)
        success as? Sdk ?: error("Python SDK setup failed: ${invoke(result.javaClass.getMethod("getErrorOrNull"), result)}")
    }
}
