package dev.barmy.hatch

import java.nio.file.Path

data class HatchConfigurationPlan(val name: String, val directory: Path, val arguments: List<String>) {
    val identity: String get() = identity(directory, arguments)
    companion object {
        fun identity(directory: Path, arguments: List<String>) = directory.toAbsolutePath().normalize().toString() + "|" + com.google.gson.Gson().toJson(arguments)
    }
}

object HatchConfigurationPlanner {
    fun plans(directory: Path, packageName: String, environments: List<HatchEnvironment>): List<HatchConfigurationPlan> =
        environments.flatMap { env ->
            listOf("run" to listOf("run", "start"), "test" to listOf("test", "tests")).mapNotNull { (verb, candidates) ->
                val script = candidates.firstOrNull { it in env.scripts } ?: return@mapNotNull null
                val suffix = if (env.name == "default") "" else " [${env.name}]"
                HatchConfigurationPlan("$verb $packageName$suffix", directory, listOf("run", "${env.name}:$script"))
            }
        }
}
