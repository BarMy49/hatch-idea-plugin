package dev.barmy.hatch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.*
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.DirectoryProjectGenerator
import com.intellij.platform.ProjectGeneratorPeer
import com.intellij.ide.util.projectWizard.SettingsStep
import com.intellij.facet.ui.ValidationResult
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
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
        HatchCreation.destinationError(Path.of(baseDirPath), preserveBaseVenv = true)?.let(::ValidationResult) ?: ValidationResult.OK
    } catch (error: Exception) { ValidationResult(error.message ?: "Invalid project path") }
    override fun generateProject(project: Project, baseDir: VirtualFile, settings: HatchCreationOptions, module: Module) {
        ApplicationManager.getApplication().getService(HatchCreationPreferences::class.java).loadState(settings.copy(name = "", description = ""))
        if (!settings.installHatch) project.getService(HatchSettings::class.java).executable = settings.executable
        val effective = settings.copy()
        var filesCreated = false
        var pythonResolved = !settings.downloadPython
        var hatchInstalled = false
        fun startSetup() { ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Creating Hatch project", true) {
            override fun run(indicator: ProgressIndicator) {
                val destination = Path.of(baseDir.path)
                if (!filesCreated) check(HatchCreation.destinationError(destination, effective.createVenv) == null) {
                    HatchCreation.destinationError(destination, effective.createVenv).orEmpty()
                }
                if (!pythonResolved) {
                    indicator.text = "Downloading Python ${effective.pythonVersion}"
                    effective.python = HatchPythonSdk.install(effective.pythonVersion)
                    pythonResolved = true
                    indicator.checkCanceled()
                }
                if (effective.installHatch && !hatchInstalled) {
                    indicator.text = "Creating project-local .venv"
                    val python = HatchBasePython.prepare(effective, destination, com.intellij.openapi.util.SystemInfo.isWindows) { binary, args ->
                        command(binary, destination, args, indicator)
                    }
                    indicator.text = "Installing Hatch into .venv"
                    effective.executable = HatchBasePython.installHatch(python, destination, com.intellij.openapi.util.SystemInfo.isWindows) { binary, args ->
                        command(binary, destination, args, indicator)
                    }
                    hatchInstalled = true
                    ApplicationManager.getApplication().invokeAndWait {
                        project.getService(HatchSettings::class.java).executable = effective.executable
                    }
                }
                if (!filesCreated) {
                    val staging = Files.createTempDirectory("hatch-project-")
                    try {
                        val output = staging.resolve("project")
                        val config = staging.resolve("config.toml")
                        Files.writeString(config, HatchCreation.config(settings))
                        indicator.text = "Generating project files"
                        val args = mutableListOf("--config", config.toString(), "new", settings.name, output.toString())
                        if (settings.cli) args.add("--cli")
                        command(effective.executable, staging, args, indicator)
                        val metadata = output.resolve("pyproject.toml")
                        var contents = HatchCreation.description(Files.readString(metadata), settings.description)
                    contents = HatchCreation.authorMetadata(contents, settings.author, settings.email)
                        if (effective.createVenv) contents = HatchCreation.projectEnvironment(contents)
                        Files.writeString(metadata, contents)
                        indicator.checkCanceled()
                        HatchCreation.transfer(output, destination, preserveBaseVenv = effective.createVenv)
                        filesCreated = true
                    ApplicationManager.getApplication().invokeLater({
                        if (!project.isDisposed) hatchWriteIntentAction { HatchRunner.setupCreated(project, destination, effective) }
                    }, com.intellij.openapi.application.ModalityState.nonModal())
                    } finally {
                        Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
                    }
                }
                if (effective.environment || effective.createVenv) setupEnvironment(project, module, baseDir, effective, indicator)
            }
            override fun onSuccess() { finish(project, baseDir) }
            override fun onThrowable(error: Throwable) {
                finish(project, baseDir)
                if (project.isDisposed) return
                val message = (if (filesCreated) "Project files were created. Environment setup failed:" else "Project creation failed:") +
                    "\n${error.message}\n\nCompleted files and the project .venv are retained. Retry the remaining setup?"
                if (Messages.showYesNoDialog(project, message, "Hatch Project Setup", Messages.getErrorIcon()) == Messages.YES) startSetup()
            }
            override fun onCancel() { finish(project, baseDir) }
        }) }
        startSetup()
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
    private fun setupEnvironment(project: Project, module: Module, directory: VirtualFile, options: HatchCreationOptions, indicator: ProgressIndicator) {
        val root = Path.of(directory.path)
        indicator.text = "Preparing project Python interpreter"
        val basePython = HatchBasePython.prepare(options, root, com.intellij.openapi.util.SystemInfo.isWindows) { binary, arguments ->
            command(binary, root, arguments, indicator)
        }
        val interpreter = if (options.createVenv) Path.of(basePython) else {
            indicator.text = "Creating Hatch environment and installing dependencies"
            command(options.executable, root, listOf("env", "create", "default"), indicator, mapOf("HATCH_PYTHON" to basePython))
            val env = command(options.executable, root, listOf("env", "find", "default"), indicator).trim()
            Path.of(env).resolve(if (com.intellij.openapi.util.SystemInfo.isWindows) "Scripts/python.exe" else "bin/python")
        }
        check(Files.isRegularFile(interpreter)) { "Hatch interpreter not found: $interpreter" }
        if (options.createVenv && options.environment) {
            indicator.text = "Synchronizing dependencies in .venv"
            // env create skips an existing venv; run triggers project/dependency synchronization.
            command(options.executable, root, listOf("run", "default:python", "-c", "import sys; print(sys.executable)"), indicator,
                mapOf("HATCH_PYTHON" to options.python))
            val located = Path.of(command(options.executable, root, listOf("env", "find", "default"), indicator).trim())
            check(located.toAbsolutePath().normalize() == root.resolve(".venv").toAbsolutePath().normalize()) {
                "Hatch's default environment does not resolve to the project .venv: $located"
            }
        }
        indicator.text = "Configuring project interpreter"
        val version = command(interpreter.toString(), root, listOf("-c", "import platform; print(platform.python_version())"), indicator).trim()
        HatchPythonSdk.configure(project, module, interpreter, version)
    }
    private fun command(binary: String, directory: Path, arguments: List<String>, indicator: ProgressIndicator, environment: Map<String, String> = emptyMap()): String {
        val process = CapturingProcessHandler(GeneralCommandLine(binary).withWorkDirectory(directory.toFile()).withParameters(arguments).withEnvironment(environment).withCharset(Charsets.UTF_8))
        val result = process.runProcessWithProgressIndicator(indicator)
        indicator.checkCanceled()
        check(result.exitCode == 0) {
            // Hatch/pip can split the summary and the backend traceback across streams.
            // Preserve both so an editable-metadata failure includes its actual cause.
            "Process exited with ${result.exitCode}\n" +
                listOf(result.stderr, result.stdout).filter { it.isNotBlank() }.joinToString("\n")
        }
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
    private val pythonSource = JComboBox(arrayOf("Use installed Python", "Download Python")).apply { selectedIndex = if (defaults.downloadPython) 1 else 0 }
    private val pythonVersions = JComboBox<HatchPythonSdk.Download>()
    private val downloadStatus = JLabel(" ")
    private val reloadDownloads = JButton("Refresh versions")
    private var loadingDownloads = false
    private var downloadsLoaded = false
    private val headers = JCheckBox("License headers", defaults.headers)
    private val tests = JCheckBox("Include tests", defaults.tests)
    private val ci = JCheckBox("GitHub Actions CI", defaults.ci)
    private val src = JCheckBox("Use src layout", defaults.srcLayout)
    private val cli = JCheckBox("Command line application", defaults.cli)
    private val environment = JCheckBox("Set up Hatch environment and install project dependencies", defaults.environment)
    private val createVenv = JCheckBox("Create project .venv for the IDE and Hatch default environment", defaults.createVenv || defaults.downloadPython)
    private val installHatch = JCheckBox("Install Hatch into .venv and use it for this project", defaults.installHatch)
    private val preview = JLabel(" ")
    private val panel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Package name:", name).addComponent(preview).addLabeledComponent("Description:", description)
        .addLabeledComponent("Author:", author).addLabeledComponent("Email:", email).addLabeledComponent("Licenses (SPDX, comma-separated):", licenses)
        .addComponent(headers).addComponent(tests).addComponent(ci).addComponent(src).addComponent(cli)
        .addLabeledComponent("Hatch executable:", executable).addComponent(environment)
        .addLabeledComponent("Python source:", pythonSource).addLabeledComponent("Python executable:", python)
        .addLabeledComponent("Download version:", pythonVersions).addComponent(downloadStatus).addComponent(reloadDownloads)
        .addComponent(createVenv).addComponent(installHatch)
        .addComponent(JLabel("Project .venv is shared by the IDE and Hatch's default environment.")).panel
    private val listeners = mutableListOf<ProjectGeneratorPeer.SettingsListener>()
    private var checkValid: Runnable? = null
    init {
        val listener = object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = changed()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = changed()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = changed()
        }
        listOf(name, description, author, email, licenses, executable.textField, python.textField).forEach { it.document.addDocumentListener(listener) }
        listOf(headers, tests, ci, src, cli, environment, createVenv, installHatch).forEach { it.addActionListener { changed() } }
        pythonSource.addActionListener { changed() }
        pythonVersions.addActionListener { changed() }
        reloadDownloads.addActionListener { loadDownloads() }
        changed()
        if (!installHatch.isSelected && defaults.author.isBlank() && defaults.email.isBlank()) loadDefaults()
    }
    private fun loadDownloads() {
        if (loadingDownloads) return
        loadingDownloads = true
        val previous = (pythonVersions.selectedItem as? HatchPythonSdk.Download)?.version ?: defaults.pythonVersion
        downloadStatus.text = "Loading available Python versions…"
        changed()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { HatchPythonSdk.downloads() }
            ApplicationManager.getApplication().invokeLater {
                downloadsLoaded = true
                loadingDownloads = false
                pythonVersions.model = DefaultComboBoxModel(result.getOrDefault(emptyList()).toTypedArray())
                if (previous.isNotBlank()) pythonVersions.selectedItem = result.getOrNull()?.firstOrNull { it.version == previous }
                downloadStatus.text = when {
                    result.isFailure -> "Could not load versions: ${result.exceptionOrNull()?.message}. Refresh to retry."
                    pythonVersions.itemCount == 0 -> "No Python downloads available for this IDE/platform. Use installed Python."
                    pythonVersions.selectedItem == null -> "The previous version is unavailable. Choose a version."
                    else -> "Python will be installed using the IDE's Python installer."
                }
                changed()
            }
        }
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
        val download = pythonSource.selectedIndex == 1
        if (download) createVenv.isSelected = true
        createVenv.isEnabled = !download
        if (!createVenv.isSelected) installHatch.isSelected = false
        val distribution = HatchCreation.normalized(name.text)
        preview.text = "Distribution: $distribution   Import: ${distribution.replace('-', '_')}"
        python.isEnabled = !download && (environment.isSelected || createVenv.isSelected)
        pythonVersions.isEnabled = download && !loadingDownloads
        reloadDownloads.isEnabled = download && !loadingDownloads
        installHatch.isEnabled = createVenv.isSelected
        executable.isEnabled = !installHatch.isSelected
        listeners.forEach { it.stateChanged(true) }
        checkValid?.run()
        if (download && !downloadsLoaded && !loadingDownloads) loadDownloads()
    }
    override fun getComponent(location: TextFieldWithBrowseButton, checkValid: Runnable): JComponent {
        this.checkValid = checkValid
        return panel
    }
    @Suppress("OVERRIDE_DEPRECATION") // Required fallback for older directory-project wizard hosts.
    override fun getComponent(): JComponent = panel
    override fun buildUI(settingsStep: SettingsStep) { settingsStep.addSettingsComponent(panel) }
    override fun getSettings() = HatchCreationOptions(name.text.trim(), description.text, author.text, email.text, licenses.text, headers.isSelected, tests.isSelected, ci.isSelected, src.isSelected, cli.isSelected, if (installHatch.isSelected) "" else HatchExecutableInput.normalize(executable.text), python.text.trim(), environment.isSelected, createVenv.isSelected, installHatch.isSelected,
        downloadPython = pythonSource.selectedIndex == 1, pythonVersion = (pythonVersions.selectedItem as? HatchPythonSdk.Download)?.version.orEmpty())
    override fun validate(): ValidationInfo? = try { HatchCreation.validate(settings)?.let { ValidationInfo(it, panel) } } catch (error: IllegalArgumentException) { ValidationInfo(error.message.orEmpty(), panel) }
    override fun isBackgroundJobRunning() = pythonSource.selectedIndex == 1 && loadingDownloads
    @Suppress("OVERRIDE_DEPRECATION")
    override fun addSettingsListener(listener: ProjectGeneratorPeer.SettingsListener) { listeners.add(listener) }
}
