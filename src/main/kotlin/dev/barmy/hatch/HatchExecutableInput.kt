package dev.barmy.hatch

import javax.swing.JTextField

/** One commit path for Apply, Enter, Refresh and task execution. */
class HatchExecutableInput(private val field: JTextField, private val settings: HatchSettings) {
    fun commit(): String {
        val value = normalize(field.text)
        settings.executable = value
        field.text = value
        return value
    }

    companion object {
        fun normalize(text: String): String {
            val trimmed = text.trim()
            val value = if (trimmed.length >= 2 &&
                ((trimmed.first() == '"' && trimmed.last() == '"') ||
                 (trimmed.first() == '\'' && trimmed.last() == '\''))) trimmed.substring(1, trimmed.length - 1) else trimmed
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                "Enter one executable path, without line breaks."
            }
            return value.ifBlank { "hatch" }
        }
    }
}
