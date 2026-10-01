package io.github.kabrapratik28.thumbfree.engine

/** One transcribe_run. engine_jni.cpp builds it with NewObject, so keep the constructor in sync with it. */
data class NativeResult(
    val status: Int,
    val text: String,
    val rawText: String,
    val truncated: Boolean,
    val aborted: Boolean,
    val retried: Boolean, // Canary's second run, without PnC
    val melMs: Float,
    val encodeMs: Float,
    val decodeMs: Float,
    val vmHwmKb: Long,
)
