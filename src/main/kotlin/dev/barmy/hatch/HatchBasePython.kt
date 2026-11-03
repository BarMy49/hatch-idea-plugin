package dev.barmy.hatch

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Creates the wizard-owned project environment used by the IDE and Hatch's default environment. */
internal object HatchBasePython {
    fun isOwned(project: Path): Boolean = !Files.isSymbolicLink(project.resolve(".venv")) &&
        Files.isRegularFile(project.resolve(".venv/.hatch-tasks-base"), LinkOption.NOFOLLOW_LINKS)

    fun installHatch(python: String, project: Path, windows: Boolean, run: (String, List<String>) -> Unit): String {
        check(isOwned(project)) { "Hatch can only be installed into the wizard-created .venv." }
        run(python, listOf("-m", "pip", "--isolated", "install", "--disable-pip-version-check", "hatch"))
        val hatch = project.resolve(if (windows) ".venv/Scripts/hatch.exe" else ".venv/bin/hatch")
        check(Files.isRegularFile(hatch)) { "Hatch installation did not create $hatch" }
        run(hatch.toString(), listOf("--version"))
        return hatch.toString()
    }

    fun prepare(options: HatchCreationOptions, project: Path, windows: Boolean, run: (String, List<String>) -> Unit): String {
        if (!options.createVenv) return options.python
        val directory = project.resolve(".venv")
        val marker = directory.resolve(".hatch-tasks-base")
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            check(!Files.isSymbolicLink(directory) && Files.isRegularFile(marker) && Files.readString(marker) == options.python) {
                "The project's .venv already exists and was not created by this wizard with the selected Python. It will not be modified."
            }
        } else {
            Files.createDirectory(directory)
            Files.writeString(marker, options.python)
        }
        val interpreter = directory.resolve(if (windows) "Scripts/python.exe" else "bin/python")
        // Repeating venv on a wizard-owned partial environment repairs interrupted creation.
        if (!Files.isRegularFile(interpreter) || !Files.isRegularFile(directory.resolve("pyvenv.cfg"))) {
            run(options.python, listOf("-m", "venv", directory.toString()))
        }
        check(Files.isRegularFile(interpreter) && Files.isRegularFile(directory.resolve("pyvenv.cfg"))) {
            "Python did not create a complete virtual environment at $directory"
        }
        return interpreter.toString()
    }
}
