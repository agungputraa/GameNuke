package com.neon.gametweak

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Consistent user-facing result feedback for actionable controls. */
object NukeToast {
    enum class Outcome(val prefix: String) {
        SUCCESS("SUCCESS • "),
        ERROR("ERROR • "),
        UNSUPPORTED("UNSUPPORTED • "),
    }

    fun success(context: Context, message: String, long: Boolean = false) =
        show(context, Outcome.SUCCESS, message, long)

    fun error(context: Context, message: String, long: Boolean = false) =
        show(context, Outcome.ERROR, message, long)

    fun unsupported(context: Context, message: String, long: Boolean = false) =
        show(context, Outcome.UNSUPPORTED, message, long)

    fun fromResult(context: Context, success: Boolean, message: String, long: Boolean = false) =
        show(context, if (success) Outcome.SUCCESS else Outcome.ERROR, message, long)

    private fun show(context: Context, outcome: Outcome, message: String, long: Boolean) {
        val app = context.applicationContext
        val clean = message.trim().ifBlank { if (outcome == Outcome.SUCCESS) "Action completed" else "Action could not be completed" }
        val text = (outcome.prefix + clean).take(190)
        val action = {
            Toast.makeText(app, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else Handler(Looper.getMainLooper()).post { action() }
    }
}
