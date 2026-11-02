package dev.barmy.hatch

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import org.tomlj.Toml

class HatchCreationTest {
    @TempDir lateinit var root: Path
    @Test fun `template parameters round trip without TOML injection`() {
        val o = HatchCreationOptions(author = "A \"quoted\" author\n[evil]", email = "x@example.org", licenses = "MIT, Apache-2.0", tests = false, ci = true, srcLayout = false)
        val result = Toml.parse(HatchCreation.config(o))
        assertFalse(result.hasErrors(), result.errors().toString())
        assertEquals(o.author, result.getString("template.name"))
        assertEquals(listOf("MIT", "Apache-2.0"), result.getArray("template.licenses.default")!!.toList())
        assertEquals(false, result.getBoolean("template.plugins.default.tests"))
        assertEquals(true, result.getBoolean("template.plugins.default.ci"))
        assertEquals(false, result.getBoolean("template.plugins.default.src-layout"))
    }
    @Test fun `description changes only the generated project table`() {
        val input = "[project]\nname = \"demo\"\ndescription = \"\"\n[tool.other]\ndescription = \"keep\"\n"
        val result = Toml.parse(HatchCreation.description(input, "New\n\"description\""))
        assertEquals("New\n\"description\"", result.getString("project.description"))
        assertEquals("keep", result.getString("tool.other.description"))
    }
    @Test fun `existing content is never overwritten`() {
        val staging = Files.createDirectory(root.resolve("staging"))
        Files.writeString(staging.resolve("pyproject.toml"), "new")
        val destination = Files.createDirectory(root.resolve("destination"))
        Files.writeString(destination.resolve("pyproject.toml"), "existing")
        assertThrows(IllegalStateException::class.java) { HatchCreation.transfer(staging, destination) }
        assertEquals("existing", Files.readString(destination.resolve("pyproject.toml")))
        assertTrue(Files.exists(staging.resolve("pyproject.toml")))
    }
    @Test fun `IDE metadata is allowed and preserved during transfer`() {
        val staging = Files.createDirectory(root.resolve("staging"))
        Files.writeString(staging.resolve("pyproject.toml"), "new")
        val destination = Files.createDirectory(root.resolve("destination"))
        Files.createDirectory(destination.resolve(".idea"))
        HatchCreation.transfer(staging, destination)
        assertEquals("new", Files.readString(destination.resolve("pyproject.toml")))
        assertTrue(Files.isDirectory(destination.resolve(".idea")))
    }
    @Test fun `environment setup needs an interpreter and names are constrained`() {
        assertNotNull(HatchCreation.validate(HatchCreationOptions(name = "../escape", environment = false)))
        assertNotNull(HatchCreation.validate(HatchCreationOptions(name = "demo")))
        assertNull(HatchCreation.validate(HatchCreationOptions(name = "demo", environment = false)))
        assertEquals("my-demo", HatchCreation.normalized("My_Demo"))
    }
}
