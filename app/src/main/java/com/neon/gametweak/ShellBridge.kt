package com.neon.gametweak

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small, bounded shell facade that never reports success without checking the command result. */
object ShellBridge {
    suspend fun result(
        adbManager: AdbManager,
        command: String,
        timeoutMs: Long = 2500L,
    ): NukeCommandResult = withContext(Dispatchers.IO) {
        runCatching { adbManager.executeCommand(command, "/", timeoutMs) }
            .getOrElse { NukeCommandResult(-1, it.message.orEmpty()) }
    }

    suspend fun read(adbManager: AdbManager, command: String, timeoutMs: Long = 2500L): String {
        return result(adbManager, command, timeoutMs).output.trim().take(64 * 1024)
    }

    suspend fun exec(adbManager: AdbManager, command: String, timeoutMs: Long = 2500L): Boolean {
        return result(adbManager, command, timeoutMs).isSuccess
    }
}
