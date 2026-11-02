package dev.barmy.hatch

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

class HatchConfigurationPlannerTest {
    private val directory = Path.of("demo").toAbsolutePath()
    @Test fun `metadata name and exact script arguments are preserved`() {
        val plans = HatchConfigurationPlanner.plans(directory, "hatch-task-demo", listOf(HatchEnvironment("default", "virtual", listOf("start", "test", "check"))))
        assertEquals(listOf("run hatch-task-demo", "test hatch-task-demo"), plans.map { it.name })
        assertEquals(listOf("run", "default:start"), plans.first().arguments)
    }
    @Test fun `preferred aliases win and matrix configurations are qualified`() {
        val plans = HatchConfigurationPlanner.plans(directory, "demo", listOf(HatchEnvironment("tests.py3.11", "virtual", listOf("run", "start", "test", "tests"))))
        assertEquals(listOf("run demo [tests.py3.11]", "test demo [tests.py3.11]"), plans.map { it.name })
        assertEquals(listOf("run", "tests.py3.11:run"), plans.first().arguments)
        assertEquals(listOf("run", "tests.py3.11:test"), plans.last().arguments)
    }
    @Test fun `unrecognized scripts produce no guessed commands`() {
        assertTrue(HatchConfigurationPlanner.plans(directory, "demo", listOf(HatchEnvironment("default", "virtual", listOf("lint", "contest", "restart")))).isEmpty())
    }
    @Test fun `identity survives renaming and path normalization and is XML safe`() {
        val first = HatchConfigurationPlan("run demo", directory.resolve("sub/.."), listOf("run", "default:start"))
        val renamed = first.copy(name = "custom", directory = directory)
        assertEquals(first.identity, renamed.identity)
        assertNotEquals(first.identity, first.copy(arguments = listOf("run", "default:test")).identity)
        assertFalse(first.identity.any { it.code < 32 })
    }
    @Test fun `settings retain automatic setup and deletion suppression across reload`() {
        val settings = HatchSettings()
        settings.autoConfigurations = false
        settings.generatedIdentities.add("deleted-task")
        val loaded = HatchSettings()
        loaded.loadState(settings.state)
        assertFalse(loaded.autoConfigurations)
        assertEquals(listOf("deleted-task"), loaded.generatedIdentities)
        settings.generatedIdentities.clear()
        assertEquals(listOf("deleted-task"), loaded.generatedIdentities)
    }
}
