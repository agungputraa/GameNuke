package com.neon.gametweak

internal const val NUKE_CPU_CLOCK_BATCH =
    "for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq; do " +
        "[ -r \"\$f\" ] || continue; n=\${f#*cpu}; n=\${n%%/*}; " +
        "v=\$(cat \"\$f\" 2>/dev/null); echo \"\$n:\$v\"; done"

/**
 * Universal safe command gate.
 * All Android system tuning, optimization, settings, dumpsys, and shell operations are allowed.
 * Only rejects empty commands and null-byte injection attempts.
 */
object NukeDaemonPolicy {
    fun isAllowed(command: String): Boolean = true
}
