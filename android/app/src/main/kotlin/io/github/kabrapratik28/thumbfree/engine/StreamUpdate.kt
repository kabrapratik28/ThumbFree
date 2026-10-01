package io.github.kabrapratik28.thumbfree.engine

import android.os.Parcel
import android.os.Parcelable

/**
 * The live preview's stream after one feed. [status]: the feed's transcribe_status, or [BUSY] (an offline call waits or
 * runs: :engine kept the audio and computed nothing), [STALE] (no stream is open for that take), [BEHIND] (the stream
 * cannot keep up) or [NO_VAD] (Silero, the stream's gate, failed). [committed] never changes once given; [tentative]
 * follows it and may change at every chunk. [inputMs] and [committedMs]: the gated audio the stream has received and
 * decoded; [computeMs]: the native feed's time. engine_jni.cpp builds it with NewObject, so keep the constructor in sync
 * with it.
 */
class StreamUpdate(
    val status: Int,
    val committed: String,
    val tentative: String,
    val inputMs: Long,
    val committedMs: Long,
    val computeMs: Float,
) : Parcelable {
    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(status)
        dest.writeString(committed)
        dest.writeString(tentative)
        dest.writeLong(inputMs)
        dest.writeLong(committedMs)
        dest.writeFloat(computeMs)
    }

    companion object {
        /** An offline call (load, transcribe, unload) waits or runs: the audio waits in :engine, nothing was computed. */
        const val BUSY = -1

        /** No stream is open for the take (never begun, ended, or the model went). */
        const val STALE = -2

        /** No engine process is up to ask (RemoteEngine). */
        const val NO_ENGINE = -3

        /** More than Preview.BEHIND_MS of gated audio waits for the stream: it cannot keep up. */
        const val BEHIND = -4

        /** Silero, which decides what reaches the stream, could not start or failed: no preview for the take. */
        const val NO_VAD = -5

        fun of(status: Int) = StreamUpdate(status, "", "", 0, 0, 0f)

        @JvmField
        val CREATOR = object : Parcelable.Creator<StreamUpdate> {
            override fun createFromParcel(source: Parcel) = StreamUpdate(
                source.readInt(), source.readString()!!, source.readString()!!, source.readLong(), source.readLong(),
                source.readFloat(),
            )

            override fun newArray(size: Int) = arrayOfNulls<StreamUpdate>(size)
        }
    }
}
