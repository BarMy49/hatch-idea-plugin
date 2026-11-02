package dev.barmy.hatch

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path

data class HatchCreationOptions(
    var name: String = "", var description: String = "", var author: String = "", var email: String = "",
    var licenses: String = "MIT", var headers: Boolean = true, var tests: Boolean = true,
    var ci: Boolean = false, var srcLayout: Boolean = true, var cli: Boolean = false,
    var executable: String = "hatch", var python: String = "", var environment: Boolean = true,
)

object HatchCreation {
    fun normalized(name: String) = name.lowercase().replace(Regex("[-_.]+"), "-")
    fun validate(options: HatchCreationOptions): String? = when {
        !options.name.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?")) -> "Enter a Python distribution name using letters, numbers, dots, underscores or hyphens."
        options.executable.isBlank() -> "Choose the Hatch executable."
        options.environment && options.python.isBlank() -> "Choose the base Python executable, or disable environment setup."
        options.licenses.split(',').any { it.isNotBlank() && !it.trim().matches(Regex("[A-Za-z0-9.+-]+")) } -> "Enter comma-separated SPDX identifiers, or leave licenses empty."
        else -> null
    }
    private fun quote(value: String): String = Gson().toJson(value)
    fun config(o: HatchCreationOptions): String = """
        [template]
        name = ${quote(o.author)}
        email = ${quote(o.email)}
        [template.licenses]
        headers = ${o.headers}
        default = [${o.licenses.split(',').map(String::trim).filter(String::isNotEmpty).joinToString(", ") { quote(it) }}]
        [template.plugins.default]
        tests = ${o.tests}
        ci = ${o.ci}
        src-layout = ${o.srcLayout}
    """.trimIndent()

    fun description(toml: String, description: String): String {
        var project = false
        var replaced = false
        val updated = toml.lines().map { line ->
            if (line.trim().startsWith("[")) project = line.trim() == "[project]"
            if (project && line.trimStart().startsWith("description =")) {
                check(!replaced) { "Duplicate project description" }; replaced = true
                "description = ${quote(description)}"
            } else line
        }.joinToString("\n")
        check(replaced) { "Hatch template did not generate a project description" }
        check(!org.tomlj.Toml.parse(updated).hasErrors()) { "Generated project metadata is invalid" }
        return updated
    }
    fun destinationError(path: Path): String? {
        if (!Files.exists(path)) return null
        if (!Files.isDirectory(path)) return "Destination is not a directory."
        return Files.list(path).use { entries ->
            if (entries.anyMatch { it.fileName.toString() != ".idea" }) "Choose an empty directory; existing project files will not be overwritten." else null
        }
    }
    fun transfer(source: Path, destination: Path) {
        check(destinationError(destination) == null) { destinationError(destination).orEmpty() }
        Files.createDirectories(destination)
        val moved = mutableListOf<Path>()
        try {
            Files.list(source).use { entries -> entries.forEach { file ->
                check(!Files.exists(destination.resolve(file.fileName))) { "Destination changed during creation: ${file.fileName}" }
                Files.move(file, destination.resolve(file.fileName))
                moved.add(file.fileName)
            } }
        } catch (error: Exception) {
            moved.asReversed().forEach { name ->
                runCatching { Files.move(destination.resolve(name), source.resolve(name)) }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }
}
