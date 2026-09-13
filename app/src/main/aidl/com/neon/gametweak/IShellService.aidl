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

    /** Start kernel touch listener via libwandev.so. Returns device count or negative on error. */
    int touchStart(String libPath) = 4;

    /** Stop kernel touch listener and release hardware grab safely. */
    void touchStop() = 5;

    /** Returns true if the touch listener is active and running. */
    boolean isTouchRunning() = 6;

    /** Update touch sensitivity and curve configuration. */
    void touchConfigure(float sx, float sy, int area, int curve, boolean smooth, float minCutoff, float beta) = 7;

    /** Directly control hardware touch grab. */
    void touchSetGrab(boolean grab) = 8;

    /** Directly inject multi-touch motion event for Macro Studio and parallel gestures. */
    boolean injectMotionEvent(int action, int pointerId, float x, float y, float pressure) = 9;

    /** Directly inject tap event for Macro Studio (handles down -> hold -> up cleanly). */
    boolean injectTap(float x, float y, long durationMs) = 10;

    /** Directly inject hold event for Macro Studio. */
    boolean injectHold(float x, float y, long durationMs) = 11;

    /** Update macro pin targets in kernel touch listener for sub-millisecond multi-touch triggers. */
    void setMacroPins(String pinsConfig) = 12;

    /** Directly inject swipe / drag gesture for Macro Studio (SWIPE macro + test-fire). */
    boolean injectSwipe(float x1, float y1, float x2, float y2, long durationMs) = 13;
}
