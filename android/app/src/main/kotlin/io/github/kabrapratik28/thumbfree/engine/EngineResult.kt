package io.github.kabrapratik28.thumbfree.engine

import android.os.Parcel
import android.os.Parcelable

/**
 * One transcribe over Binder. Text, flags and timings are meaningful only for status 0, 13 and 18. [retried]: the text is
 * from Canary's second run without PnC, after the first gave none. [vadRejected]: Silero heard no speech in the chunk,
 * so it has no text (status 0).
 */
class EngineResult(
    val status: Int,
    val text: String,
    val rawText: String,
    val truncated: Boolean,
    val aborted: Boolean,
    val retried: Boolean,
    val encodeMs: Float,
    val vmHwmKb: Long,
    val vadRejected: Boolean = false,
) : Parcelable {
    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(status)
        dest.writeString(text)
        dest.writeString(rawText)
        dest.writeBoolean(truncated)
        dest.writeBoolean(aborted)
        dest.writeBoolean(retried)
        dest.writeFloat(encodeMs)
        dest.writeLong(vmHwmKb)
        dest.writeBoolean(vadRejected)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<EngineResult> {
            override fun createFromParcel(source: Parcel) = EngineResult(
                source.readInt(), source.readString()!!, source.readString()!!, source.readBoolean(),
                source.readBoolean(), source.readBoolean(), source.readFloat(), source.readLong(), source.readBoolean(),
            )

            override fun newArray(size: Int) = arrayOfNulls<EngineResult>(size)
        }
    }
}
