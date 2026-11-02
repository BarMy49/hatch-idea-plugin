package dev.barmy.hatch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.*
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.*
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.DirectoryProjectGenerator
import com.intellij.platform.ProjectGeneratorPeer
import com.intellij.ide.util.projectWizard.SettingsStep
import com.intellij.facet.ui.ValidationResult
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.jetbrains.python.sdk.PythonSdkType
import com.intellij.util.ui.FormBuilder
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*

@Service(Service.Level.APP)
@State(name = "HatchCreationPreferences", storages = [Storage("hatch-creation.xml")])
class HatchCreationPreferences : PersistentStateComponent<HatchCreationOptions> {
    private var options = HatchCreationOptions()
    override fun getState() = options
    override fun loadState(state: HatchCreationOptions) { options = state.copy() }
}

class HatchProjectGenerator : DirectoryProjectGenerator<HatchCreationOptions> {
    override fun getName() = "Hatch"
    override fun getDescription() = "Create a Python project with Hatch"
    override fun getLogo() = IconLoader.getIcon("/icons/hatch.svg", javaClass)
    override fun createPeer(): ProjectGeneratorPeer<HatchCreationOptions> = HatchCreationPeer()
    override fun validate(baseDirPath: String): ValidationResult = try {
        HatchCreation.destinationError(Path.of(baseDirPath))?.let(::ValidationResult) ?: ValidationResult.OK
    } catch (error: Exception) { ValidationResult(error.message ?: "Invalid project path") }
    override fun generateProject(project: Project, baseDir: VirtualFile, settings: HatchCreationOptions, module: Module) {
        ApplicationManager.getApplication().getService(HatchCreationPreferences::class.java).loadState(settings.copy(name = "", description = ""))
        project.getService(HatchSettings::class.java).executable = settings.executable
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Creating Hatch project", true) {
            override fun run(indicator: ProgressIndicator) {
                val staging = Files.createTempDirectory("hatch-project-")
                try {
                    val output = staging.resolve("project")
                    val config = staging.resolve("config.toml")
                    Files.writeString(config, HatchCreation.config(settings))
                    indicator.text = "Generating project files"
                    val args = mutableListOf("--config", config.toString(), "new", settings.name, output.toString())
                    if (settings.cli) args.add("--cli")
                    command(settings.executable, staging, args, indicator)
                    val metadata = output.resolve("pyproject.toml")
                    Files.writeString(metadata, HatchCreation.description(Files.readString(metadata), settings.description))
                    indicator.checkCanceled()
                    HatchCreation.transfer(output, Path.of(baseDir.path))
                } finally {
                    Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                }
                if (settings.environment) setupEnvironment(project, baseDir, settings, indicator)
            }
            override fun onSuccess() { finish(project, baseDir) }
            override fun onThrowable(error: Throwable) {
                finish(project, baseDir)
                Messages.showErrorDialog(project, error.stackTraceToString(), "Hatch Project Creation Failed")
            }
            override fun onCancel() { finish(project, baseDir) }
        })
    }
    private fun finish(project: Project, directory: VirtualFile) {
        if (project.isDisposed) return
        directory.refresh(true, true) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    directory.findChild("pyproject.toml")?.let { FileEditorManager.getInstance(project).openFile(it, true) }
                    project.getService(HatchDiscovery::class.java).refresh()
                }
            }
        }
    }
    private fun setupEnvironment(project: Project, directory: VirtualFile, options: HatchCreationOptions, indicator: ProgressIndicator) {
        indicator.text = "Creating Hatch environment and installing dependencies"
        try {
            command(options.executable, Path.of(directory.path), listOf("env", "create", "default"), indicator, mapOf("HATCH_PYTHON" to options.python))
            val env = command(options.executable, Path.of(directory.path), listOf("env", "find", "default"), indicator).trim()
            val interpreter = Path.of(env).resolve(if (com.intellij.openapi.util.SystemInfo.isWindows) "Scripts/python.exe" else "bin/python")
            check(Files.isRegularFile(interpreter)) { "Hatch interpreter not found: $interpreter" }
            ApplicationManager.getApplication().invokeAndWait {
                if (project.isDisposed) return@invokeAndWait
                var configuredSdk: com.intellij.openapi.projectRoots.Sdk? = null
                WriteAction.run<RuntimeException> {
                    val table = ProjectJdkTable.getInstance()
                    val sdk = table.allJdks.firstOrNull { it.homePath == interpreter.toString() } ?: table.createSdk("Hatch (${project.name})", PythonSdkType.getInstance()).also {
                        val modificator = it.sdkModificator
                        modificator.homePath = interpreter.toString(); modificator.commitChanges()
                        table.addJdk(it)
                    }
                    ProjectRootManager.getInstance(project).projectSdk = sdk
                    configuredSdk = sdk
                }
                configuredSdk?.let { PythonSdkType.getInstance().setupSdkPaths(it) }
            }
        } catch (cancelled: com.intellij.openapi.progress.ProcessCanceledException) { throw cancelled }
        catch (error: Exception) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && Messages.showYesNoDialog(project, "Project files were created. Environment setup failed:\n${error.message}\n\nRetry environment setup?", "Hatch Environment Setup", Messages.getErrorIcon()) == Messages.YES) {
                    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Setting up Hatch environment", true) {
                        override fun run(indicator: ProgressIndicator) { setupEnvironment(project, directory, options, indicator) }
                        override fun onSuccess() { finish(project, directory) }
                    })
                }
            }
        }
    }
    private fun command(binary: String, directory: Path, arguments: List<String>, indicator: ProgressIndicator, environment: Map<String, String> = emptyMap()): String {
        val process = CapturingProcessHandler(GeneralCommandLine(binary).withWorkDirectory(directory.toFile()).withParameters(arguments).withEnvironment(environment).withCharset(Charsets.UTF_8))
        val result = process.runProcessWithProgressIndicator(indicator)
        indicator.checkCanceled()
        check(result.exitCode == 0) { result.stderr.ifBlank { result.stdout.ifBlank { "Hatch exited with ${result.exitCode}" } } }
        return result.stdout
    }
}

private class HatchCreationPeer : ProjectGeneratorPeer<HatchCreationOptions> {
    private val defaults = ApplicationManager.getApplication().getService(HatchCreationPreferences::class.java).state.copy()
    private val name = JTextField()
    private val description = JTextField()
    private val author = JTextField(defaults.author)
    private val email = JTextField(defaults.email)
    private val licenses = JTextField(defaults.licenses)
    private val executable = TextFieldWithBrowseButton().apply { text = defaults.executable; addBrowseFolderListener(null, FileChooserDescriptorFactory.singleFile()) }
    private val python = TextFieldWithBrowseButton().apply { text = defaults.python; addBrowseFolderListener(null, FileChooserDescriptorFactory.singleFile()) }
    private val headers = JCheckBox("License headers", defaults.headers)
    private val tests = JCheckBox("Include tests", defaults.tests)
    private val ci = JCheckBox("GitHub Actions CI", defaults.ci)
    private val src = JCheckBox("Use src layout", defaults.srcLayout)
    private val cli = JCheckBox("Command line application", defaults.cli)
    private val environment = JCheckBox("Create Hatch environment and configure interpreter", defaults.environment)
    private val preview = JLabel(" ")
    private val panel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Package name:", name).addComponent(preview).addLabeledComponent("Description:", description)
        .addLabeledComponent("Author:", author).addLabeledComponent("Email:", email).addLabeledComponent("Licenses (SPDX, comma-separated):", licenses)
        .addComponent(headers).addComponent(tests).addComponent(ci).addComponent(src).addComponent(cli)
        .addLabeledComponent("Hatch executable:", executable).addComponent(environment).addLabeledComponent("Base Python executable:", python).panel
    private val listeners = mutableListOf<ProjectGeneratorPeer.SettingsListener>()
    private var checkValid: Runnable? = null
    init {
        val listener = object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = changed()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = changed()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = changed()
        }
        listOf(name, description, author, email, licenses, executable.textField, python.textField).forEach { it.document.addDocumentListener(listener) }
        listOf(headers, tests, ci, src, cli, environment).forEach { it.addActionListener { changed() } }
        if (defaults.author.isBlank() && defaults.email.isBlank()) loadDefaults()
    }
    private fun loadDefaults() {
        val binary = executable.text
        ApplicationManager.getApplication().executeOnPooledThread {
            val config = runCatching {
                val result = HatchCommand.capture(binary, Path.of(System.getProperty("user.home")), listOf("config", "show"))
                check(result.exitCode == 0 && !result.isTimeout)
                org.tomlj.Toml.parse(result.stdout).also { check(!it.hasErrors()) }
            }.getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (author.text == defaults.author) author.text = config.getString("template.name") ?: defaults.author
                if (email.text == defaults.email) email.text = config.getString("template.email") ?: defaults.email
                if (licenses.text == defaults.licenses) licenses.text = config.getArray("template.licenses.default")?.toList()?.joinToString(", ") ?: defaults.licenses
                if (headers.isSelected == defaults.headers) headers.isSelected = config.getBoolean("template.licenses.headers") ?: defaults.headers
                if (tests.isSelected == defaults.tests) tests.isSelected = config.getBoolean("template.plugins.default.tests") ?: defaults.tests
                if (ci.isSelected == defaults.ci) ci.isSelected = config.getBoolean("template.plugins.default.ci") ?: defaults.ci
                if (src.isSelected == defaults.srcLayout) src.isSelected = config.getBoolean("template.plugins.default.src-layout") ?: defaults.srcLayout
                changed()
            }
        }
    }
    private fun changed() {
        val distribution = HatchCreation.normalized(name.text)
        preview.text = "Distribution: $distribution   Import: ${distribution.replace('-', '_')}"
        python.isEnabled = environment.isSelected
        listeners.forEach { it.stateChanged(true) }
        checkValid?.run()
    }
    override fun getComponent(location: TextFieldWithBrowseButton, checkValid: Runnable): JComponent {
        this.checkValid = checkValid
        return panel
    }
    @Suppress("OVERRIDE_DEPRECATION") // Required fallback for older directory-project wizard hosts.
    override fun getComponent(): JComponent = panel
    override fun buildUI(settingsStep: SettingsStep) { settingsStep.addSettingsComponent(panel) }
    override fun getSettings() = HatchCreationOptions(name.text.trim(), description.text, author.text, email.text, licenses.text, headers.isSelected, tests.isSelected, ci.isSelected, src.isSelected, cli.isSelected, HatchExecutableInput.normalize(executable.text), python.text.trim(), environment.isSelected)
    override fun validate(): ValidationInfo? = try { HatchCreation.validate(settings)?.let { ValidationInfo(it, panel) } } catch (error: IllegalArgumentException) { ValidationInfo(error.message.orEmpty(), panel) }
    override fun isBackgroundJobRunning() = false
    @Suppress("OVERRIDE_DEPRECATION")
    override fun addSettingsListener(listener: ProjectGeneratorPeer.SettingsListener) { listeners.add(listener) }
}
