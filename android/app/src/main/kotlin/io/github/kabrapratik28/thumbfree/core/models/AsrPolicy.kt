package io.github.kabrapratik28.thumbfree.core.models

/**
 * Where :engine runs the speech engine's CPU work (its thread profile): [threads] compute threads, the engine thread
 * and its pool's workers, on [cpus] (null: wherever the process may run; [strict]: one CPU each), in a pool kept for
 * the loaded model when [persistent] (else a pool per graph, as ggml does by default).
 */
data class AsrProfile(val threads: Int, val cpus: List<Int>?, val strict: Boolean, val persistent: Boolean)

object AsrPolicy {
    // android.os.PowerManager's THERMAL_STATUS_ values.
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3

    /**
     * The profile for a thermal status, applied between jobs. At MODERATE, at most 4 threads; from SEVERE on, at most 3
     * and unpinned, so Android can move them to cooler cores. The app has no thermal stop or unload of its own, so
     * CRITICAL and above keep SEVERE's profile.
     */
    fun forThermal(status: Int, base: AsrProfile): AsrProfile = when {
        status >= THERMAL_SEVERE -> base.copy(threads = minOf(base.threads, 3), cpus = null, strict = false)
        status >= THERMAL_MODERATE -> base.copy(threads = minOf(base.threads, 4))
        else -> base
    }
}

/**
 * Engine switches for phone A/B runs, read from :engine's environment, which a debug build fills from
 * files/engine-env.txt (EngineService). Release builds see the defaults.
 */
data class EngineKnobs(
    val pool: Boolean = true, // THUMBFREE_ASR_POOL=0: a pool per graph, as ggml does by default
    // THUMBFREE_ASR_PIN=off|fast|strict. Fast by default: on the Pixel 10 (2026-09-26 A/B) it took 17% / 9% / 7% off
    // the 3.7 s / JFK / 29.4 s engine time, and with ADPF's both 50% / 29% / 15%.
    val pin: Pin = Pin.FAST,
    val threads: Int? = null, // THUMBFREE_ASR_THREADS=N instead of ThreadPolicy's count
    // THUMBFREE_ADPF=off|target|increase|both. Both by default: on the Pixel 10 it saved 136 ms / 134 ms on the 3.7 s
    // clip / JFK over the fast pin alone, with 27% / 6% less energy per call.
    val hints: Int = HINTS_BOTH,
    val hintRtf: Float = 0.1f, // THUMBFREE_ADPF_RTF: the hint's target, as a share of the audio's duration
    val vadCpus: VadCpus = VadCpus.ANY, // THUMBFREE_VAD_CPUS=any|slow: Silero's thread anywhere or on the slow cores
    val thermal: Boolean = true, // THUMBFREE_THERMAL=0: no thermal fallback
) {
    enum class Pin { OFF, FAST, STRICT }

    enum class VadCpus { ANY, SLOW }

    companion object {
        const val HINTS_OFF = 0
        const val HINTS_TARGET = 1 // target duration before each run, actual duration after
        const val HINTS_INCREASE = 2 // a workload increase just before each run (API 36)
        const val HINTS_BOTH = HINTS_TARGET or HINTS_INCREASE

        /** Unknown or malformed values keep the default. */
        fun parse(env: Map<String, String>): EngineKnobs {
            val d = EngineKnobs()
            fun v(name: String) = env[name]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            fun flag(name: String, default: Boolean) = when (v(name)) {
                "0", "off", "false" -> false
                "1", "on", "true" -> true
                else -> default
            }
            return EngineKnobs(
                pool = flag("THUMBFREE_ASR_POOL", d.pool),
                pin = when (v("THUMBFREE_ASR_PIN")) {
                    "off", "0" -> Pin.OFF
                    "fast", "on", "1" -> Pin.FAST
                    "strict" -> Pin.STRICT
                    else -> d.pin
                },
                threads = v("THUMBFREE_ASR_THREADS")?.toIntOrNull()?.takeIf { it in 1..ThreadPolicy.MAX_THREADS },
                hints = when (v("THUMBFREE_ADPF")) {
                    "off", "0" -> HINTS_OFF
                    "target" -> HINTS_TARGET
                    "increase" -> HINTS_INCREASE
                    "both" -> HINTS_BOTH
                    else -> d.hints
                },
                hintRtf = v("THUMBFREE_ADPF_RTF")?.toFloatOrNull()?.takeIf { it > 0f && it <= 2f } ?: d.hintRtf,
                vadCpus = when (v("THUMBFREE_VAD_CPUS")) {
                    "any" -> VadCpus.ANY
                    "slow" -> VadCpus.SLOW
                    else -> d.vadCpus
                },
                thermal = flag("THUMBFREE_THERMAL", d.thermal),
            )
        }
    }
}
