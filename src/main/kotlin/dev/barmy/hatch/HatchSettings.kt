package dev.barmy.hatch

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** The Hatch binary is configurable per IntelliJ project, independently of its Python SDK. */
@Service(Service.Level.PROJECT)
@State(name = "HatchTasksSettings", storages = [Storage("hatch-tasks.xml")])
class HatchSettings : PersistentStateComponent<HatchSettings.State> {
    data class State(var executable: String = "hatch", var linkedPaths: MutableList<String> = mutableListOf(), var autoConfigurations: Boolean = true, var generatedIdentities: MutableList<String> = mutableListOf())

    @Volatile private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state.copy(executable = HatchExecutableInput.normalize(state.executable), linkedPaths = state.linkedPaths.toMutableList(), generatedIdentities = state.generatedIdentities.toMutableList())
    }

    var autoConfigurations: Boolean
        get() = state.autoConfigurations
        set(value) { state = state.copy(autoConfigurations = value) }
    val generatedIdentities: MutableList<String> get() = state.generatedIdentities

    var linkedPaths: MutableList<String>
        get() = state.linkedPaths
        set(value) { state.linkedPaths = value }

    var executable: String
        get() = state.executable
        set(value) { state = state.copy(executable = HatchExecutableInput.normalize(value)) }
}
