package dev.barmy.hatch

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** No shell interpolation: arguments are passed to Hatch separately. */
object HatchCommand {
    fun line(executable: String, directory: Path, args: List<String>): GeneralCommandLine =
        GeneralCommandLine(executable)
            .withWorkDirectory(directory.toFile())
            .withCharset(StandardCharsets.UTF_8)
            .withParameters(args)

    /** Must run on a background thread, never on Swing's EDT. */
    fun capture(executable: String, directory: Path, args: List<String>, timeoutMs: Int = 20_000): ProcessOutput {
        val handler = CapturingProcessHandler(line(executable, directory, args))
        return handler.runProcess(timeoutMs)
    }
}
