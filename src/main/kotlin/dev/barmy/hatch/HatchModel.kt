package dev.barmy.hatch

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Data from `hatch env show --json`. Hatch resolves inheritance and extra scripts for us. */
data class HatchEnvironment(
    val name: String,
    val type: String,
    val scripts: List<String>,
    val description: String = "",
)

object HatchModel {
    fun parse(json: String): List<HatchEnvironment> {
        val root = JsonParser.parseString(json).asJsonObject
        return root.entrySet()
            .mapNotNull { (name, value) ->
                if (!value.isJsonObject) return@mapNotNull null
                val environment: JsonObject = value.asJsonObject
                val type = environment.get("type")
                    ?.takeIf { it.isJsonPrimitive }?.asString ?: "virtual"
                val description = environment.get("description")
                    ?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                val scripts = environment.get("scripts")
                    ?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.keySet()?.filterNot { it.startsWith("_") }?.sorted() ?: emptyList()
                HatchEnvironment(name, type, scripts, description)
            }
            .filterNot { it.name.startsWith("hatch-") } // Hatch reserves these for internal environments
            .sortedWith(compareBy({ it.name != "default" }, { it.name }))
    }
}
