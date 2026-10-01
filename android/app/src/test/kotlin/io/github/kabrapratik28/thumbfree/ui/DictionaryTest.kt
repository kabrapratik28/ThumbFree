package io.github.kabrapratik28.thumbfree.ui

import com.google.common.truth.Truth.assertThat
import io.github.kabrapratik28.thumbfree.core.text.CustomWords
import org.junit.Test

// The Dictionary tab's rules: Add and Save check one entry by the field; Paste a list says what it will add and skip.
// CustomWords.parse keeps the list's shape either way.
class DictionaryTest {
    @Test
    fun oneEntryIsCheckedBeforeItIsSaved() {
        val words = listOf("GitHub", "Anika")

        assertThat(checkEntry("  Dr.   Nakamura ", words)).isEqualTo("Dr. Nakamura" to null) // as parse spells it
        assertThat(checkEntry("   ", words)).isEqualTo(null to EntryProblem.BLANK)
        assertThat(checkEntry("github", words)).isEqualTo(null to EntryProblem.DUPLICATE) // in any case
        assertThat(checkEntry("x".repeat(61), words)).isEqualTo(null to EntryProblem.TOO_LONG)
        assertThat(checkEntry("Priya, Will", words)).isEqualTo(null to EntryProblem.SEVERAL)
    }

    // An edit may change an entry's own case, but not repeat another entry.
    @Test
    fun anEditMayRepeatOnlyItself() {
        val words = listOf("github", "Anika")

        assertThat(checkEntry("GitHub", words, editing = "github")).isEqualTo("GitHub" to null)
        assertThat(checkEntry("anika", words, editing = "github")).isEqualTo(null to EntryProblem.DUPLICATE)
    }

    @Test
    fun aPastedListAddsItsNewEntriesAndCountsTheRest() {
        val added = addWords(listOf("GitHub"), "Kubernetes, github\nDr. Nakamura,, ${"x".repeat(61)}\n  Anika  , kubernetes")

        assertThat(added.words).containsExactly("GitHub", "Kubernetes", "Dr. Nakamura", "Anika").inOrder()
        assertThat(added.added).containsExactly("Kubernetes", "Dr. Nakamura", "Anika").inOrder()
        assertThat(added.duplicates).isEqualTo(2) // github (in the list) and kubernetes (twice in the paste)
        assertThat(added.tooLong).isEqualTo(1)
        assertThat(added.notFitting).isEqualTo(0)
    }

    @Test
    fun aFullListLeavesTheRestOut() {
        val full = (1..CustomWords.MAX_ENTRIES - 1).map { "word$it" }

        val added = addWords(full, "Anika, Priya, Will")

        assertThat(added.added).containsExactly("Anika")
        assertThat(added.notFitting).isEqualTo(2)
        assertThat(added.words).hasSize(CustomWords.MAX_ENTRIES)
    }
}
