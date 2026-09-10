package frb.axeron.server.touch

/**
 * High-performance touch event model matching the exact memory layout
 * and dispatch parameters emitted by libtouch.so.
 */
class TouchEvent(
    var deviceId: Int = 0,
    var slot: Int = 0,
    var trackingId: Int = 0,
    var action: Int = 0,
    var x: Int = 0,
    var y: Int = 0,
    var normX: Float = 0f,
    var normY: Float = 0f,
    var pressure: Int = 0,
    var touchMajor: Int = 0,
    var rawType: Int = 0,
    var rawCode: Int = 0,
    var rawValue: Int = 0,
    var timestamp: Long = 0L,
    var dispX: Int = -1,
    var dispY: Int = -1,
    var dispXf: Float = Float.NaN,
    var dispYf: Float = Float.NaN,
    var rotation: Int = 0,
    var displayWidth: Int = 0,
    var displayHeight: Int = 0
) {
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_MOVE = 1
        const val ACTION_UP = 2
        const val ACTION_FRAME = 3
        const val UNKNOWN_COORD = Int.MIN_VALUE
    }

    fun populate(
        devId: Int,
        s: Int,
        trackId: Int,
        act: Int,
        posX: Int,
        posY: Int,
        nX: Float,
        nY: Float,
        press: Int,
        tMajor: Int,
        rType: Int,
        rCode: Int,
        rVal: Int,
        ts: Long
    ) {
        this.deviceId = devId
        this.slot = s
        this.trackingId = trackId
        this.action = act
        this.x = posX
        this.y = posY
        this.normX = nX
        this.normY = nY
        this.pressure = press
        this.touchMajor = tMajor
        this.rawType = rType
        this.rawCode = rCode
        this.rawValue = rVal
        this.timestamp = ts
        this.dispX = -1
        this.dispY = -1
        this.dispXf = Float.NaN
        this.dispYf = Float.NaN
        this.rotation = 0
        this.displayWidth = 0
        this.displayHeight = 0
    }

    fun copy(): TouchEvent = TouchEvent(
        deviceId, slot, trackingId, action, x, y, normX, normY,
        pressure, touchMajor, rawType, rawCode, rawValue, timestamp,
        dispX, dispY, dispXf, dispYf, rotation, displayWidth, displayHeight
    )
}
