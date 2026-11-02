package dev.barmy.hatch

import com.intellij.openapi.application.WriteIntentReadAction

/**
 * Swing callbacks do not always hold write intent (notably in PyCharm 262).
 * This API is experimental but required by document saving on supported builds.
 * Keep the Runnable overload: the ThrowableRunnable overload changed between 253 and 262.
 */
@Suppress("UnstableApiUsage")
internal fun hatchWriteIntentAction(action: () -> Unit) {
    val runnable = Runnable { action() }
    WriteIntentReadAction.run(runnable)
}
