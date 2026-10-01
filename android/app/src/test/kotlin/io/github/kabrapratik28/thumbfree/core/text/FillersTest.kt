package io.github.kabrapratik28.thumbfree.core.text

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class FillersTest {
    // F(text, lang): filler removal then normalize. The language is an ISO 639-1 code, or null when unknown.
    private data class F(val text: String, val language: String?, val custom: List<String>? = null)

    private fun check(rows: Map<F, String>) {
        assertThat(rows.mapValues { (f, _) -> normalize(removeFillers(f.text, f.language, f.custom)) })
            .containsExactlyEntriesIn(rows)
    }

    @Test
    fun goldens() = check(
        // A7 to A32 (A31 is its own test below), adapted from MIT-licensed code; see THIRD_PARTY_NOTICES.md.
        mapOf(
            F("So uhm I was thinking uh about this", "en") to "So I was thinking about this", // A7 test_filter_filler_words
            F("UHM this is UH a test", "en") to "this is a test", // A8 test_filter_filler_words_case_insensitive
            F("Well, uhm, I think, uh. that's right", "en") to "Well, I think, that's right", // A9 test_filter_filler_words_with_punctuation
            F("Hello    world   test", "en") to "Hello world test", // A10 test_filter_cleans_whitespace
            F("  Hello world  ", "en") to "Hello world", // A11 test_filter_trims
            F("  Uhm, so I was, uh, thinking about this  ", "en") to "so I was, thinking about this", // A12 test_filter_combined
            F("This is a completely normal sentence.", "en") to "This is a completely normal sentence.", // A13 test_filter_preserves_valid_text
            F("w wh wh wh wh wh wh wh wh wh why", "en") to "w wh why", // A14 test_filter_stutter_collapse
            F("I I I I think so so so so", "en") to "I think so", // A15 test_filter_stutter_short_words
            F("Check data doc doc doc doc documentation.", "en") to "Check data doc documentation.", // A16 test_filter_stutter_longer_words
            F("No NO no NO no", "en") to "No", // A17 test_filter_stutter_mixed_case
            F("no no is fine", "en") to "no no is fine", // A18 test_filter_stutter_preserves_two_repetitions
            F("um I think um this is good", "en") to "I think this is good", // A19 test_filter_english_removes_um
            F("um gato bonito", "pt") to "um gato bonito", // A20 test_filter_portuguese_preserves_um
            F("ha sido un buen día", "es") to "ha sido un buen día", // A21 test_filter_spanish_preserves_ha
            F("um gato bonito", "pt-BR") to "um gato bonito", // A22 test_filter_language_code_with_region
            F("okay so I think right this works", "en", listOf("okay", "right")) to "so I think this works", // A23 test_filter_custom_filler_words_override
            F("So uhm I was thinking uh about this", "en", emptyList()) to "So uhm I was thinking uh about this", // A24 test_filter_custom_filler_words_empty_disables
            F("uh I think uhm this works", "xx") to "I think this works", // A25 test_filter_unknown_language_still_removes_universal_fillers
            F("um I think this works", "xx") to "um I think this works", // A26 test_filter_unknown_language_does_not_remove_um
            F("uhh bueno hmm creo que um ha llegado", null) to "bueno creo que um ha llegado", // A27 test_filter_unknown_evidence_removes_universal_keeps_gated
            F("хм я думаю ммм это работает", null) to "я думаю это работает", // A27 test_filter_unknown_evidence_removes_universal_keeps_gated
            F("äh ich glaube ähm das passt", null) to "äh ich glaube ähm das passt", // A28 test_filter_german_gated_fillers_require_evidence
            F("äh ich glaube ähm das passt", "de") to "ich glaube das passt", // A28 test_filter_german_gated_fillers_require_evidence
            F("the screw is 5 mm long", "en") to "the screw is 5 mm long", // A29 test_filter_preserves_millimetre_unit
            F("um I think this works", "en") to "I think this works", // A30 test_filter_detected_evidence_unlocks_gated_fillers (ModelDetected)
            F("euh je pense que ça marche", "fr") to "je pense que ça marche", // A30 test_filter_detected_evidence_unlocks_gated_fillers (TextDetected)
            F("customword should be removed but um should remain", null, listOf("customword")) to "should be removed but um should remain", // A32 test_filter_custom_words_apply_without_language_evidence
        ),
    )

    @Test
    fun masterSwitchOffRemovesNothing() {
        // A31 test_filter_master_toggle_disables_custom_and_builtin_removal (no normalize).
        val text = "um customword I think"
        assertThat(removeFillers(text, "en", listOf("customword"), enabled = false)).isEqualTo(text)
    }

    @Test
    fun derivedEdgeCases() = check(
        // D9 to D14: edge cases worked out from the regex.
        mapOf(
            F("That's it uh.", "en") to "That's it", // D9 the filler takes the period
            F("Yes, um.", "en") to "Yes,", // D10 same
            F("Really hmm?", "en") to "Really ?", // D11 ? is not eaten
            F("I think uh, yes", "en") to "I think yes", // D12 the filler takes the comma
            F("Uh-huh, I agree", "en") to "-huh, I agree", // D13 "uh" is a whole word before "-"
            F("(um) okay", "en") to "() okay", // D14 brackets stay
        ),
    )

    @Test(timeout = 10_000)
    fun oddUnicodeNeverThrowsOrHangs() {
        // Cleanup fails open, so a crash here would quietly skip cleanup in the app. Random mixes of fillers,
        // whitespace that only Rust counts, marks, joiners, lone surrogate halves and emoji, with odd custom
        // lists (an empty custom word would loop forever in a naive matcher).
        val pieces = listOf(
            "uh", "UHM", "um", "Äh", "хм", "ММ", "euh", ",", ".", "?", "-", "_", " ", "\n",
            "\u0085", "\u00A0", "\u001C", "\u0301", "\u200D", "\uD83D", "\uDE00", "😀", "你好", "²",
        )
        val customs = listOf(null, emptyList(), listOf(""), listOf(" "), listOf("\uD83D"), listOf("a."))
        val random = Random(7)
        repeat(20_000) {
            val text = List(random.nextInt(10)) { pieces.random(random) }.joinToString("")
            normalize(removeFillers(text, listOf(null, "en", "de", "fr").random(random), customs.random(random)))
        }
    }
}
