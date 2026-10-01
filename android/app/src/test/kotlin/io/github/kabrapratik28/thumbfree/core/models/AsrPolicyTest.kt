package io.github.kabrapratik28.thumbfree.core.models

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.models.EngineKnobs.Pin
import io.github.kabrapratik28.thumbfree.core.models.EngineKnobs.VadCpus
import org.junit.Test

class AsrPolicyTest {
    private val pinned = AsrProfile(threads = 5, cpus = listOf(2, 3, 4, 5, 6), strict = false, persistent = true)

    @Test
    fun thermalFallback() {
        // NONE and LIGHT keep the profile.
        assertThat(AsrPolicy.forThermal(0, pinned)).isEqualTo(pinned)
        assertThat(AsrPolicy.forThermal(1, pinned)).isEqualTo(pinned)
        // MODERATE: 4 threads, still on the fast cores.
        assertThat(AsrPolicy.forThermal(2, pinned)).isEqualTo(pinned.copy(threads = 4))
        // SEVERE and above: 3 threads, unpinned, so Android can pick cooler cores.
        val severe = AsrProfile(threads = 3, cpus = null, strict = false, persistent = true)
        for (status in 3..6) assertThat(AsrPolicy.forThermal(status, pinned)).isEqualTo(severe)
        // A strict pin also goes at SEVERE; a profile already small stays so.
        assertThat(AsrPolicy.forThermal(3, pinned.copy(strict = true))).isEqualTo(severe)
        assertThat(AsrPolicy.forThermal(2, pinned.copy(threads = 2))).isEqualTo(pinned.copy(threads = 2))
    }

    @Test
    fun knobDefaults() {
        val knobs = EngineKnobs.parse(emptyMap())
        assertThat(knobs).isEqualTo(EngineKnobs())
        assertThat(knobs.pool).isTrue()
        assertThat(knobs.pin).isEqualTo(Pin.FAST)
        assertThat(knobs.threads).isNull()
        assertThat(knobs.hints).isEqualTo(EngineKnobs.HINTS_BOTH)
        assertThat(knobs.vadCpus).isEqualTo(VadCpus.ANY)
        assertThat(knobs.thermal).isTrue()
    }

    @Test
    fun knobParsing() {
        val knobs = EngineKnobs.parse(
            mapOf(
                "THUMBFREE_ASR_POOL" to "0", "THUMBFREE_ASR_PIN" to "Strict", "THUMBFREE_ASR_THREADS" to "3",
                "THUMBFREE_ADPF" to "both", "THUMBFREE_ADPF_RTF" to "0.2", "THUMBFREE_VAD_CPUS" to "slow",
                "THUMBFREE_THERMAL" to "off", "UNRELATED" to "x",
            ),
        )
        assertThat(knobs).isEqualTo(EngineKnobs(false, Pin.STRICT, 3, EngineKnobs.HINTS_BOTH, 0.2f, VadCpus.SLOW, false))
        assertThat(EngineKnobs.parse(mapOf("THUMBFREE_ASR_PIN" to "fast")).pin).isEqualTo(Pin.FAST)
        assertThat(EngineKnobs.parse(mapOf("THUMBFREE_ASR_PIN" to "off")).pin).isEqualTo(Pin.OFF)
        assertThat(EngineKnobs.parse(mapOf("THUMBFREE_ADPF" to "increase")).hints).isEqualTo(EngineKnobs.HINTS_INCREASE)
        assertThat(EngineKnobs.parse(mapOf("THUMBFREE_ADPF" to "target")).hints).isEqualTo(EngineKnobs.HINTS_TARGET)
        assertThat(EngineKnobs.parse(mapOf("THUMBFREE_ADPF" to "off")).hints).isEqualTo(EngineKnobs.HINTS_OFF)
    }

    @Test
    fun malformedKnobsKeepTheDefaults() {
        val knobs = EngineKnobs.parse(
            mapOf(
                "THUMBFREE_ASR_POOL" to "maybe", "THUMBFREE_ASR_PIN" to "all", "THUMBFREE_ASR_THREADS" to "12",
                "THUMBFREE_ADPF" to "max", "THUMBFREE_ADPF_RTF" to "-1", "THUMBFREE_VAD_CPUS" to "big",
                "THUMBFREE_THERMAL" to "",
            ),
        )
        assertThat(knobs).isEqualTo(EngineKnobs())
        assertThat(EngineKnobs.parse(mapOf("THUMBFREE_ASR_THREADS" to "0")).threads).isNull()
    }
}
