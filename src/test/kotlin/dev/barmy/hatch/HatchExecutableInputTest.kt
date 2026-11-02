package dev.barmy.hatch

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.swing.JTextField
import javax.swing.SwingUtilities

class HatchExecutableInputTest {
    @Test fun `refresh commits the edited executable on the same settings instance`() {
        SwingUtilities.invokeAndWait {
            val settings = HatchSettings()
            val field = JTextField(settings.executable)
            val input = HatchExecutableInput(field, settings)
            field.text = "  \"C:\\My Projects\\demo\\.venv\\Scripts\\hatch.exe\"  "
            val first = input.commit()
            assertEquals("C:\\My Projects\\demo\\.venv\\Scripts\\hatch.exe", first)
            assertEquals(first, settings.executable)
            assertEquals(first, settings.getState().executable)
            field.text = "C:\\Other\\hatch.exe"
            assertEquals("C:\\Other\\hatch.exe", input.commit())
            assertEquals("C:\\Other\\hatch.exe", settings.executable)
        }
    }

    @Test fun `blank executable falls back and loaded settings remain editable`() {
        val settings = HatchSettings()
        settings.loadState(HatchSettings.State("\"C:\\Tools\\hatch.exe\""))
        assertEquals("C:\\Tools\\hatch.exe", settings.executable)
        settings.executable = " "
        assertEquals("hatch", settings.executable)
    }

    @Test fun `multiline clipboard content is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            HatchExecutableInput.normalize("hatch.exe\necho unwanted")
        }
    }
}
