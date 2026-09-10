package frb.axeron.server.touch;

import android.util.Log;
import java.io.File;

/**
 * Game Nuke Touch Listener JNI Bridge for libtouch.so.
 * Must match the exact class name and method signatures expected by libtouch.so:
 *  - Java_frb_axeron_server_touch_TouchListener_nativeStart
 *  - Java_frb_axeron_server_touch_TouchListener_nativeStop
 *  - Java_frb_axeron_server_touch_TouchListener_nativeIsRunning
 *  - Java_frb_axeron_server_touch_TouchListener_nativeSetGrab
 *  - Java callback: dispatchNative(IIIIIIFFIIIIIJ)V
 */
public final class TouchListener {
    private static final String TAG = "GameNukeTouchListener";
    private static volatile boolean loaded = false;
    private static volatile TouchCallback sink = null;
    public static final TouchListener INSTANCE = new TouchListener();
    private static final TouchEvent pooledEvent = new TouchEvent(
        0, 0, 0, 0, 0, 0, 0.0f, 0.0f, 0, 0, 0, 0, 0, 0L,
        -1, -1, Float.NaN, Float.NaN, 0, 0, 0
    );

    public interface TouchCallback {
        void onTouchEvent(TouchEvent event);
    }

    private TouchListener() {}

    public static void setSink(TouchCallback callback) {
        sink = callback;
    }

    public static void dispatchNative(
        int deviceId, int slot, int trackingId, int action,
        int x, int y, float normX, float normY,
        int pressure, int touchMajor, int rawType, int rawCode, int rawValue,
        long timestamp
    ) {
        TouchCallback cb = sink;
        if (cb == null) return;
        synchronized (pooledEvent) {
            pooledEvent.populate(
                deviceId, slot, trackingId, action, x, y, normX, normY,
                pressure, touchMajor, rawType, rawCode, rawValue, timestamp
            );
            cb.onTouchEvent(pooledEvent);
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    public synchronized boolean load(String path) {
        if (loaded) return true;
        if (path == null) return false;
        File f = new File(path);
        if (!f.exists()) {
            Log.e(TAG, "Library file does not exist at path: " + path);
            return false;
        }
        try {
            System.load(f.getAbsolutePath());
            loaded = true;
            Log.i(TAG, "Successfully loaded " + path + " via System.load");
            return true;
        } catch (Throwable th) {
            Log.e(TAG, "System.load(" + path + ") failed", th);
            return false;
        }
    }

    public synchronized boolean loadLibrary(String name) {
        if (loaded) return true;
        try {
            System.loadLibrary(name);
            loaded = true;
            Log.i(TAG, "Successfully loaded " + name + " via System.loadLibrary");
            return true;
        } catch (Throwable th) {
            Log.e(TAG, "System.loadLibrary(" + name + ") failed", th);
            return false;
        }
    }

    public native boolean nativeIsRunning();
    public native void nativeSetGrab(boolean grab);
    public native int nativeStart();
    public native void nativeStop();
}
