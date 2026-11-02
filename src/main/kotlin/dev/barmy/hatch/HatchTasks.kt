package dev.barmy.hatch

import java.nio.file.Files
import java.nio.file.Path

data class HatchTask(val label: String, val arguments: List<String>, val confirmation: String? = null)

object HatchProjects {
    fun isProject(directory: Path) = Files.isRegularFile(directory.resolve("pyproject.toml")) ||
        Files.isRegularFile(directory.resolve("hatch.toml"))
}

object HatchTasks {
    fun environment(env: HatchEnvironment): List<HatchTask> = listOf(
        HatchTask("Create ${env.name}", listOf("env", "create", env.name)),
        HatchTask("Remove ${env.name}", listOf("env", "remove", env.name), "Remove the Hatch environment '${env.name}'?"),
    ) + env.scripts.map { HatchTask("${env.name}:$it", listOf("run", "${env.name}:$it")) }

    val build = listOf(
        HatchTask("All distributions", listOf("build")),
        HatchTask("Wheel (.whl)", listOf("build", "-t", "wheel")),
        HatchTask("Source distribution (.tar.gz)", listOf("build", "-t", "sdist")),
    )
    val utilities = listOf(
        HatchTask("Show environments", listOf("env", "show")),
        HatchTask("Hatch version", listOf("--version")),
    )
}
