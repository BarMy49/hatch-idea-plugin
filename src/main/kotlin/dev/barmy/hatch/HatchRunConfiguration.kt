package dev.barmy.hatch

import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.configurations.*
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.runners.*
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.icons.AllIcons
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
    init { addFactory(object : ConfigurationFactory(this) {
        override fun getId() = "Hatch"
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            HatchRunConfiguration(project, this, "Hatch")
        override fun isEditableInDumbMode() = true
    }) }
}

class HatchRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<RunConfigurationOptions>(project, factory, name) {
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
        directory = element.getAttributeValue("hatchDirectory") ?: project.basePath.orEmpty()
        executable = element.getAttributeValue("hatchExecutable").orEmpty()
        arguments = element.getChildren("hatchArg").map { it.getAttributeValue("value").orEmpty() }.toMutableList()
        environment = element.getChildren("hatchEnv").associate {
            it.getAttributeValue("name").orEmpty() to it.getAttributeValue("value").orEmpty()
        }.toMutableMap()
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
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

class HatchProgramRunner : DefaultProgramRunner() {
    override fun getRunnerId() = "HatchProgramRunner"
    override fun canRun(executorId: String, profile: RunProfile) =
        executorId == DefaultRunExecutor.EXECUTOR_ID && profile is HatchRunConfiguration

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val result = state.execute(environment.executor, this) ?: return null
        return RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
    }
}

object HatchRunner {
    fun configuration(project: Project, label: String, directory: Path, arguments: List<String>): com.intellij.execution.RunnerAndConfigurationSettings {
        val type = ConfigurationTypeUtil.findConfigurationType(HatchConfigurationType::class.java)
        return RunManager.getInstance(project).createConfiguration(label, type.configurationFactories.single()).also {
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

    fun save(project: Project, label: String, directory: Path, arguments: List<String>) {
        val manager = RunManager.getInstance(project)
        val settings = configuration(project, label, directory, arguments)
        manager.addConfiguration(settings)
        manager.selectedConfiguration = settings
    }
}
