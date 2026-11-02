package dev.barmy.hatch

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HatchTasksTest {
    @Test fun `real Hatch output resolves matrices and filters internal environments`() {
        val json = javaClass.getResource("/hatch-1.18.2-envs.json")!!.readText()
        val envs = HatchModel.parse(json)
        assertEquals(listOf("default", "lint", "tests.py3.11", "tests.py3.12"), envs.map { it.name })
        val tasks = HatchTasks.environment(envs.first { it.name == "tests.py3.11" })
        assertEquals(listOf("run", "tests.py3.11:test"), tasks.last().arguments)
        assertEquals(listOf("env", "create", "tests.py3.11"), tasks.first().arguments)
        assertNotNull(tasks[1].confirmation)
    }

    @Test fun `build targets have separate CLI arguments`() {
        assertEquals(listOf("build", "-t", "wheel"), HatchTasks.build[1].arguments)
        assertEquals(listOf("build", "-t", "sdist"), HatchTasks.build[2].arguments)
    }

    @Test fun `project detection accepts separate Hatch config`(@TempDir root: Path) {
        assertFalse(HatchProjects.isProject(root))
        Files.createFile(root.resolve("hatch.toml"))
        assertTrue(HatchProjects.isProject(root))
        Files.delete(root.resolve("hatch.toml"))
        Files.createDirectory(root.resolve("pyproject.toml"))
        assertFalse(HatchProjects.isProject(root))
    }

    @Test fun `malformed discovery does not produce empty success`() {
        assertThrows(Exception::class.java) { HatchModel.parse("this is not JSON") }
        assertThrows(Exception::class.java) { HatchModel.parse("[]") }
    }
}
