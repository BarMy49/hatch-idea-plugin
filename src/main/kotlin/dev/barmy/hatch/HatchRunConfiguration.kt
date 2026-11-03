package dev.barmy.hatch

import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.configurations.*
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.runners.*
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import org.jdom.Element
import java.awt.BorderLayout
import java.awt.GridLayout
import java.nio.file.Path
import javax.swing.*

class HatchConfigurationType : ConfigurationTypeBase(
    "HatchRunConfiguration", "Hatch", "Run a Hatch script or build task", com.intellij.openapi.util.IconLoader.getIcon("/icons/hatch.svg", HatchConfigurationType::class.java),
), DumbAware {
    init { listOf("Hatch" to listOf("run", "default:python"), "Hatch Test" to listOf("test"), "Hatch Build" to listOf("build")).forEach { (id, args) -> addFactory(object : ConfigurationFactory(this) {
        override fun getId() = id
        override fun getName() = if (id == "Hatch") "Hatch Command" else id
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            HatchRunConfiguration(project, this, id).also { it.arguments = args.toMutableList() }
        override fun isEditableInDumbMode() = true
    }) } }
}

class HatchRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<RunConfigurationOptions>(project, factory, name) {
    var generatedIdentity: String = ""
    var directory: String = project.basePath.orEmpty()
    var arguments: MutableList<String> = mutableListOf("build")
    // Empty means inherit the current project Hatch setting.
    var executable: String = ""
    var environment: MutableMap<String, String> = linkedMapOf()

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = HatchRunEditor()

    override fun checkConfiguration() {
        if (directory.isBlank() || !java.io.File(directory).isDirectory)
            throw RuntimeConfigurationError("Choose an existing Hatch project directory")
        if (arguments.isEmpty()) throw RuntimeConfigurationError("Enter Hatch arguments, for example run default:test")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            override fun startProcess() = KillableColoredProcessHandler(
                HatchCommand.line(
                    executable.ifBlank { project.getService(HatchSettings::class.java).executable },
                    Path.of(directory), arguments,
                ).withEnvironment(this@HatchRunConfiguration.environment),
            ).also { com.intellij.execution.process.ProcessTerminatedListener.attach(it) }
        }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        generatedIdentity = element.getAttributeValue("hatchGeneratedIdentity").orEmpty()
        directory = element.getAttributeValue("hatchDirectory") ?: project.basePath.orEmpty()
        executable = element.getAttributeValue("hatchExecutable").orEmpty()
        arguments = element.getChildren("hatchArg").map { it.getAttributeValue("value").orEmpty() }.toMutableList()
        environment = element.getChildren("hatchEnv").associate {
            it.getAttributeValue("name").orEmpty() to it.getAttributeValue("value").orEmpty()
        }.toMutableMap()
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        if (generatedIdentity.isNotEmpty()) element.setAttribute("hatchGeneratedIdentity", generatedIdentity)
        element.setAttribute("hatchDirectory", directory)
        element.setAttribute("hatchExecutable", executable)
        arguments.forEach { element.addContent(Element("hatchArg").setAttribute("value", it)) }
        environment.forEach { (key, value) -> element.addContent(Element("hatchEnv").setAttribute("name", key).setAttribute("value", value)) }
    }

    override fun clone(): HatchRunConfiguration = (super.clone() as HatchRunConfiguration).also {
        it.arguments = arguments.toMutableList()
        it.environment = environment.toMutableMap()
    }
}

private class HatchRunEditor : SettingsEditor<HatchRunConfiguration>() {
    private val directory = JTextField()
    private val arguments = JTextField()
    private val executable = JTextField()
    private val environment = JTextArea(5, 30)
    private val panel = JPanel(BorderLayout(8, 8)).apply {
        add(JPanel(GridLayout(0, 1, 4, 4)).apply {
            add(JLabel("Hatch project directory (absolute path):")); add(directory)
            add(JLabel("Arguments (for example: run default:test -q):")); add(arguments)
            add(JLabel("Hatch executable (blank inherits project setting):")); add(executable)
            add(JLabel("Environment variables, one NAME=value per line:"))
        }, BorderLayout.NORTH)
        add(JScrollPane(environment), BorderLayout.CENTER)
    }
    override fun createEditor(): JComponent = panel
    override fun resetEditorFrom(configuration: HatchRunConfiguration) {
        directory.text = configuration.directory
        arguments.text = ParametersListUtil.join(configuration.arguments)
        executable.text = configuration.executable
        environment.text = configuration.environment.entries.joinToString("\n") { "${it.key}=${it.value}" }
    }
    override fun applyEditorTo(configuration: HatchRunConfiguration) {
        val variables = linkedMapOf<String, String>()
        environment.text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) throw com.intellij.openapi.options.ConfigurationException("Expected NAME=value: $line")
            variables[line.substring(0, separator).trim()] = line.substring(separator + 1)
        }
        configuration.directory = directory.text.trim()
        configuration.arguments = ParametersListUtil.parse(arguments.text).toMutableList()
        configuration.executable = executable.text.trim()
        configuration.environment = variables
    }
}

class HatchProgramRunner : AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId() = "HatchProgramRunner"
    override fun canRun(executorId: String, profile: RunProfile) =
        executorId == DefaultRunExecutor.EXECUTOR_ID && profile is HatchRunConfiguration

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): org.jetbrains.concurrency.Promise<com.intellij.execution.ui.RunContentDescriptor?> {
        val result = state.execute(environment.executor, this)
        return org.jetbrains.concurrency.resolvedPromise(
            result?.let { RunContentBuilder(it, environment).showRunContent(environment.contentToReuse) },
        )
    }
}

object HatchRunner {
    fun setupCreated(project: Project, directory: Path, options: HatchCreationOptions) {
        val settings = project.getService(HatchSettings::class.java)
        if (!settings.autoConfigurations) return
        val plans = HatchConfigurationPlanner.creationPlans(directory, options)
        val manager = RunManager.getInstance(project)
        for (plan in plans) {
            val config = existing(project, plan) ?: if (plan.identity !in settings.generatedIdentities) create(project, plan) else null
            if (plan.identity !in settings.generatedIdentities) settings.generatedIdentities.add(plan.identity)
            if (manager.selectedConfiguration == null && config != null) manager.selectedConfiguration = config
        }
    }

    fun configuration(project: Project, label: String, directory: Path, arguments: List<String>): com.intellij.execution.RunnerAndConfigurationSettings {
        val type = ConfigurationTypeUtil.findConfigurationType(HatchConfigurationType::class.java)
        return RunManager.getInstance(project).createConfiguration(label, type.configurationFactories.first { it.id == when (arguments.firstOrNull()) { "test" -> "Hatch Test"; "build" -> "Hatch Build"; else -> "Hatch" } }).also {
            val config = it.configuration as HatchRunConfiguration
            config.directory = directory.toString()
            config.arguments = arguments.toMutableList()
        }
    }

    fun run(project: Project, label: String, directory: Path, arguments: List<String>) {
        val manager = RunManager.getInstance(project)
        val settings = configuration(project, label, directory, arguments)
        manager.setTemporaryConfiguration(settings)
        manager.selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    private fun existing(project: Project, plan: HatchConfigurationPlan) = RunManager.getInstance(project).allSettings.firstOrNull {
        val config = it.configuration as? HatchRunConfiguration
        config != null && (config.generatedIdentity == plan.identity ||
            runCatching { HatchConfigurationPlan.identity(Path.of(config.directory), config.arguments) == plan.identity }.getOrDefault(false))
    }

    private fun create(project: Project, plan: HatchConfigurationPlan): com.intellij.execution.RunnerAndConfigurationSettings {
        val manager = RunManager.getInstance(project)
        val names = manager.allSettings.map { it.name }.toSet()
        var name = plan.name
        if (name in names) name += " [${plan.directory.toAbsolutePath().normalize()}]"
        val base = name
        var suffix = 2
        while (name in names) name = "$base ($suffix)".also { suffix++ }
        return configuration(project, name, plan.directory, plan.arguments).also {
            (it.configuration as HatchRunConfiguration).generatedIdentity = plan.identity
            manager.addConfiguration(it)
        }
    }

    fun setup(project: Project, results: List<HatchDiscoveryResult>) {
        val settings = project.getService(HatchSettings::class.java)
        val manager = RunManager.getInstance(project)
        val selectedBefore = manager.selectedConfiguration
        val plans = results.flatMap { result ->
            val name = result.packageName
            if (name == null) emptyList() else HatchConfigurationPlanner.plans(result.directory, name, result.environments.getOrDefault(emptyList()))
        }
        val duplicates = results.mapNotNull { it.packageName }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        var preferred: com.intellij.execution.RunnerAndConfigurationSettings? = null
        for (raw in plans) {
            val packageName = results.first { it.directory == raw.directory }.packageName
            val plan = if (packageName in duplicates) raw.copy(name = raw.name + " [${raw.directory}]") else raw
            val found = existing(project, plan)
            val configuration = found ?: if (plan.identity !in settings.generatedIdentities) create(project, plan) else null
            if (plan.identity !in settings.generatedIdentities) settings.generatedIdentities.add(plan.identity)
            if (configuration != null && raw.directory == project.basePath?.let { Path.of(it).toAbsolutePath().normalize() } &&
                raw.arguments.getOrNull(1)?.startsWith("default:") == true &&
                (preferred == null || raw.name.startsWith("run "))) preferred = configuration
        }
        if (selectedBefore != null) manager.selectedConfiguration = selectedBefore
        else if (preferred != null) manager.selectedConfiguration = preferred
    }

    fun save(project: Project, label: String, directory: Path, arguments: List<String>) {
        val discovery = project.getService(HatchDiscovery::class.java)
        val result = discovery.results.firstOrNull { it.directory == directory.toAbsolutePath().normalize() }
        val plan = result?.packageName?.let { name ->
            HatchConfigurationPlanner.plans(directory, name, result.environments.getOrDefault(emptyList())).firstOrNull { it.arguments == arguments }
                ?: HatchConfigurationPlan("$label $name", directory, arguments)
        } ?: HatchConfigurationPlan("$label ${directory.fileName}", directory, arguments)
        val saved = existing(project, plan) ?: create(project, plan)
        val settings = project.getService(HatchSettings::class.java)
        if (plan.identity !in settings.generatedIdentities) settings.generatedIdentities.add(plan.identity)
        RunManager.getInstance(project).selectedConfiguration = saved
    }
}
