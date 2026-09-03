package com.neon.gametweak

import android.os.Parcel
import android.os.Parcelable

/**
 * Parcelable result returned by IShellService.execCommand().
 * Must live in the same package as ShellResult.aidl.
 */
data class ShellResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        exitCode = parcel.readInt(),
        output = parcel.readString().orEmpty(),
        timedOut = parcel.readInt() != 0,
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(exitCode)
        parcel.writeString(output)
        parcel.writeInt(if (timedOut) 1 else 0)
    }

    override fun describeContents(): Int = 0

    fun toCommandResult(): NukeCommandResult = NukeCommandResult(exitCode, output, timedOut)

    companion object CREATOR : Parcelable.Creator<ShellResult> {
        override fun createFromParcel(parcel: Parcel) = ShellResult(parcel)
        override fun newArray(size: Int): Array<ShellResult?> = arrayOfNulls(size)
    }
}
