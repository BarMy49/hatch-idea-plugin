package dev.barmy.hatch

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HatchBasePythonTest {
    @TempDir lateinit var project: Path
    @Test fun `existing interpreter mode leaves project untouched`() {
        val options = HatchCreationOptions(python = "existing-python")
        val result = HatchBasePython.prepare(options, project, true) { _, _ -> fail("Should not create an environment") }
        assertEquals("existing-python", result)
        assertFalse(Files.exists(project.resolve(".venv")))
    }
    @Test fun `local environment uses selected Python and literal path arguments`() {
        val options = HatchCreationOptions(python = "Python With Spaces/python.exe", createVenv = true)
        val commands = mutableListOf<Pair<String, List<String>>>()
        val result = HatchBasePython.prepare(options, project, true) { binary, arguments ->
            commands.add(binary to arguments)
            complete(true)
        }
        assertEquals(listOf(options.python to listOf("-m", "venv", project.resolve(".venv").toString())), commands)
        assertEquals(project.resolve(".venv/Scripts/python.exe").toString(), result)
        HatchBasePython.prepare(options, project, true) { _, _ -> fail("Completed environment should be reused on retry") }
    }
    @Test fun `interrupted creation can be repaired on retry on POSIX`() {
        val options = HatchCreationOptions(python = "/usr/bin/python3", createVenv = true)
        assertThrows(IllegalStateException::class.java) {
            HatchBasePython.prepare(options, project, false) { _, _ -> throw IllegalStateException("Interrupted") }
        }
        val result = HatchBasePython.prepare(options, project, false) { _, _ -> complete(false) }
        assertEquals(project.resolve(".venv/bin/python").toString(), result)
    }
    @Test fun `unowned environment and mismatched base are never modified`() {
        val options = HatchCreationOptions(python = "python", createVenv = true)
        Files.createDirectory(project.resolve(".venv"))
        val keep = project.resolve(".venv/keep.txt")
        Files.writeString(keep, "existing")
        assertThrows(IllegalStateException::class.java) {
            HatchBasePython.prepare(options, project, true) { _, _ -> fail("Must not run Python") }
        }
        assertEquals("existing", Files.readString(keep))
        Files.writeString(project.resolve(".venv/.hatch-tasks-base"), "different-python")
        assertThrows(IllegalStateException::class.java) {
            HatchBasePython.prepare(options, project, true) { _, _ -> fail("Must not change base on retry") }
        }
    }
    @Test fun `Hatch installation uses only local Python and verifies the local executable`() {
        val options = HatchCreationOptions(python = "seed-python", createVenv = true)
        val python = HatchBasePython.prepare(options, project, true) { _, _ -> complete(true) }
        val commands = mutableListOf<Pair<String, List<String>>>()
        val hatch = project.resolve(".venv/Scripts/hatch.exe")
        val result = HatchBasePython.installHatch(python, project, true) { binary, args ->
            commands.add(binary to args)
            if (args.contains("install")) Files.writeString(hatch, "fixture")
        }
        assertEquals(hatch.toString(), result)
        assertEquals(python, commands.first().first)
        assertEquals(listOf("-m", "pip", "--isolated", "install", "--disable-pip-version-check", "hatch"), commands.first().second)
        assertEquals(hatch.toString() to listOf("--version"), commands.last())
    }
    @Test fun `failed local install propagates without falling back to system Hatch`() {
        val options = HatchCreationOptions(python = "seed-python", createVenv = true)
        val python = HatchBasePython.prepare(options, project, false) { _, _ -> complete(false) }
        val commands = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            HatchBasePython.installHatch(python, project, false) { binary, _ ->
                commands.add(binary); throw IllegalStateException("Network failure")
            }
        }
        assertEquals(listOf(python), commands)
    }
    @Test fun `local Hatch needs a new venv but does not need system Hatch or task environment setup`() {
        assertNotNull(HatchCreation.validate(HatchCreationOptions(name = "demo", python = "python", installHatch = true)))
        assertNull(HatchCreation.validate(HatchCreationOptions(name = "demo", python = "python", executable = "", createVenv = true, installHatch = true, environment = false)))
    }
    @Test fun `transfer preserves wizard-owned bootstrap venv only when explicitly enabled`() {
        val options = HatchCreationOptions(python = "seed-python", createVenv = true)
        HatchBasePython.prepare(options, project, false) { _, _ -> complete(false) }
        val staging = Files.createTempDirectory("hatch-transfer-")
        try {
            Files.writeString(staging.resolve("pyproject.toml"), "new")
            assertNotNull(HatchCreation.destinationError(project))
            assertNull(HatchCreation.destinationError(project, preserveBaseVenv = true))
            HatchCreation.transfer(staging, project, preserveBaseVenv = true)
            assertTrue(Files.isRegularFile(project.resolve(".venv/bin/python")))
            assertEquals("new", Files.readString(project.resolve("pyproject.toml")))
        } finally { Files.deleteIfExists(staging) }
    }
    private fun complete(windows: Boolean) {
        val interpreter = project.resolve(if (windows) ".venv/Scripts/python.exe" else ".venv/bin/python")
        Files.createDirectories(interpreter.parent)
        Files.writeString(interpreter, "fixture")
        Files.writeString(project.resolve(".venv/pyvenv.cfg"), "fixture")
    }
}
