package dev.barmy.hatch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HatchModelTest {
    @Test
    fun `discovers default and custom environments with inherited scripts`() {
        // Representative JSON of Hatch `env show --json`, including expanded script lists.
        val json = """
            {
              "hatch-build": {"type": "virtual"},
              "lint": {"type": "virtual", "description": "Lint the project", "scripts": {
                  "check": ["ruff check ."], "format": ["ruff format ."], "_internal": ["echo hidden"]
              }},
              "default": {"type": "virtual", "scripts": {"test": ["pytest tests"]}}
            }
        """.trimIndent()
        val environments = HatchModel.parse(json)
        assertEquals(listOf("default", "lint"), environments.map { it.name })
        assertEquals(listOf("check", "format"), environments[1].scripts)
        assertEquals("Lint the project", environments[1].description)
        assertEquals(listOf("test"), environments[0].scripts)
    }

    @Test
    fun `handles missing script tables`() {
        assertEquals(emptyList<String>(), HatchModel.parse("""{"default":{"type":"virtual"}}""").single().scripts)
    }
}
