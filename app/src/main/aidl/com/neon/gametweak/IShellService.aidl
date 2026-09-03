// IShellService.aidl
// AIDL interface for the privileged shell UserService.
// This service runs inside the Shizuku / iAdb process with ADB-shell UID (2000).
// IMPORTANT: The destroy() transaction code (16777114) must NOT be changed –
// it is the conventional "exit" code expected by Shizuku's service lifecycle.
package com.neon.gametweak;

import com.neon.gametweak.ShellResult;

interface IShellService {
    /** Called by Shizuku/iAdb when the user service should exit. Do NOT change the transaction code. */
    void destroy() = 16777114;

    /** Called internally when the app is done with this service. */
    void exit() = 1;

    /** Execute a shell command with a timeout and return the result. */
    ShellResult execCommand(String command, long timeoutMs) = 2;

    /** Returns true if the service process is alive and ready. */
    boolean ping() = 3;
}
