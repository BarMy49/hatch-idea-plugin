package dev.barmy.hatch

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

class HatchConfigurable(private val project: Project) : Configurable {
    private val settings get() = project.getService(HatchSettings::class.java)
    private val executable = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Hatch Executable"))
    }
    override fun getDisplayName() = "Hatch"
    override fun createComponent(): JComponent = FormBuilder.createFormBuilder().addLabeledComponent("Hatch executable:", executable).addComponentFillVertically(javax.swing.JPanel(), 0).panel
    override fun isModified() = executable.text != settings.executable
    override fun reset() { executable.text = settings.executable }
    override fun apply() {
        try { settings.executable = HatchExecutableInput.normalize(executable.text) }
        catch (error: IllegalArgumentException) { throw com.intellij.openapi.options.ConfigurationException(error.message) }
    }
}
