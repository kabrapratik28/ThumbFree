package io.github.kabrapratik28.thumbfree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.kabrapratik28.thumbfree.R
import io.github.kabrapratik28.thumbfree.core.text.CustomWords

/** Why a draft can't be saved as one entry. */
enum class EntryProblem { BLANK, SEVERAL, TOO_LONG, DUPLICATE }

/**
 * [draft] as one entry of [words], as CustomWords.parse spells it, or why it can't be one: blank, several (a comma or a
 * line break splits it), too long, or already in the list in any case. [editing] is the entry being edited, which its
 * new spelling may repeat.
 */
fun checkEntry(draft: String, words: List<String>, editing: String? = null): Pair<String?, EntryProblem?> {
    if (draft.isBlank()) return null to EntryProblem.BLANK
    if (draft.any { it == ',' || it == '\n' || it == '\r' }) return null to EntryProblem.SEVERAL
    val entry = CustomWords.parse(draft).singleOrNull() ?: return null to EntryProblem.TOO_LONG
    val taken = words.filter { it != editing }.map { it.lowercase() }.toHashSet()
    return if (entry.lowercase() in taken) null to EntryProblem.DUPLICATE else entry to null
}

/**
 * What adding [input] (a list split at commas or line breaks) to [words] gives: [words] the new list, [added] its new
 * entries, and what is left out: [duplicates] (of the list, or within [input]), [tooLong] (over CustomWords.MAX_CHARS)
 * and [notFitting] (past CustomWords.MAX_ENTRIES).
 */
data class WordsAdded(val words: List<String>, val added: List<String>, val duplicates: Int, val tooLong: Int, val notFitting: Int)

/** [input] added after [words] through CustomWords.parse, which keeps the list's own spelling of a repeat. */
fun addWords(words: List<String>, input: String): WordsAdded {
    val pieces = input.split(',', '\n', '\r').filter { it.isNotBlank() }
    val tooLong = pieces.count { CustomWords.parse(it).isEmpty() } // a non-blank piece parse keeps nothing of
    val typed = CustomWords.parse(input)
    val known = words.map { it.lowercase() }.toHashSet()
    val fresh = typed.count { it.lowercase() !in known }
    val merged = CustomWords.parse((words + input).joinToString("\n"))
    val added = merged.drop(words.size)
    return WordsAdded(merged, added, pieces.size - tooLong - fresh, tooLong, fresh - added.size)
}

/**
 * The Dictionary tab: the names and terms each take's text is corrected with (Settings.customWords, through
 * CustomWords). Nothing takes effect until Add (or Save while editing): a field for one entry, which says by the field
 * why it can't be added and when it is also a common word; Paste a list for many, with what it will add before it
 * does; the entries in the order they were added, each with Edit and Delete; and Try a phrase, a local preview of what
 * the list does to a sentence, by exact matches only when [exactOnly] (the chosen model's takes get only those).
 * [onWords] saves each change.
 */
@Composable
fun DictionaryScreen(words: List<String>, exactOnly: Boolean = false, onWords: (List<String>) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    var pasting by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf<List<String>?>(null) } // what the last Add added, for the line under it
    val risky = remember(words) { CustomWords.riskyEntries(words).toHashSet() }
    val (entry, problem) = checkEntry(draft, words)
    fun add() {
        val new = entry ?: return
        onWords(words + new)
        added = listOf(new)
        draft = ""
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            Column {
                ScreenTitle(R.string.ui_dictionary)
                Text(
                    stringResource(R.string.ui_dictionary_intro, stringResource(R.string.app_name)),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "add") {
            Column(Modifier.padding(top = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EntryField(draft, { draft = it; added = null }, entry, problem, Modifier.weight(1f), onDone = ::add)
                    Button(::add, Modifier.padding(top = 8.dp).heightIn(min = 48.dp), enabled = entry != null) {
                        Text(stringResource(R.string.ui_dictionary_add))
                    }
                }
                added?.let {
                    Text(
                        if (it.size == 1) stringResource(R.string.ui_dictionary_added_one, it[0])
                        else pluralStringResource(R.plurals.ui_dictionary_added, it.size, it.size),
                        Modifier.padding(start = 4.dp, top = 2.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton({ pasting = true }) { Text(stringResource(R.string.ui_dictionary_paste)) }
            }
        }
        if (words.isEmpty()) {
            item(key = "empty") { EmptyDictionary() }
        } else {
            item(key = "count") {
                val count = pluralStringResource(R.plurals.ui_words_count, words.size, words.size)
                Text(
                    if (words.size < CustomWords.MAX_ENTRIES) count else stringResource(R.string.ui_words_limit, count),
                    Modifier.padding(top = 12.dp, start = 4.dp), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            itemsIndexed(words, key = { _, word -> word }) { index, word ->
                Entry(word, word in risky, onEdit = { editing = word }) {
                    added = null
                    onWords(words.toMutableList().apply { removeAt(index) })
                }
            }
            item(key = "try") { TryAPhrase(words, exactOnly) }
        }
    }

    if (pasting) {
        PasteDialog(words, onAdd = { result -> onWords(result.words); added = result.added; pasting = false }, onDismiss = { pasting = false })
    }
    editing?.let { old ->
        EditDialog(
            old, words,
            onSave = { new -> onWords(words.map { if (it == old) new else it }); added = null; editing = null },
            onDismiss = { editing = null },
        )
    }
}

/**
 * The field for one entry, for Add and for Edit: by the field, why [problem] keeps it from being saved (a blank one says
 * nothing), or, for an [entry] that can be saved, that it is also a common word.
 */
@Composable
private fun EntryField(
    value: String, onValue: (String) -> Unit, entry: String?, problem: EntryProblem?, modifier: Modifier = Modifier, onDone: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val note = when (problem) {
        EntryProblem.DUPLICATE -> stringResource(R.string.ui_dictionary_problem_duplicate)
        EntryProblem.TOO_LONG -> stringResource(R.string.ui_dictionary_problem_long, CustomWords.MAX_CHARS)
        EntryProblem.SEVERAL -> stringResource(R.string.ui_dictionary_problem_several)
        EntryProblem.BLANK, null -> entry?.takeIf { CustomWords.riskyEntries(listOf(it)).isNotEmpty() }
            ?.let { stringResource(R.string.ui_words_risky_one, it, it.lowercase()) }
    }
    val error = problem != null && problem != EntryProblem.BLANK
    OutlinedTextField(
        value, onValue, modifier,
        label = { Text(stringResource(R.string.ui_dictionary_add_label)) },
        supportingText = note?.let { { Text(it, color = if (error) colors.error else colors.caution) } },
        isError = error, singleLine = true, shape = MaterialTheme.shapes.medium,
        // Names and brands: the keyboard must not "fix" them while they are typed.
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

/** One entry: the word, the common-word note when it is one, Edit and Delete. */
@Composable
private fun Entry(word: String, common: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp).semantics(mergeDescendants = true) {}) {
                Text(word, style = MaterialTheme.typography.bodyLarge)
                if (common) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(AppIcons.Info, contentDescription = null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.caution)
                        Text(
                            stringResource(R.string.ui_words_risky_one, word, word.lowercase()), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            IconButton(onEdit) { Icon(AppIcons.Edit, stringResource(R.string.ui_dictionary_edit, word)) }
            IconButton(onDelete) { Icon(AppIcons.Delete, stringResource(R.string.ui_dictionary_delete, word)) }
        }
    }
}

/** A sentence typed the way it might come out, and what the list makes of it (CustomWords.correct): only spelling. */
@Composable
private fun TryAPhrase(words: List<String>, exactOnly: Boolean) {
    var phrase by rememberSaveable { mutableStateOf("") }
    val corrected = remember(phrase, words, exactOnly) { CustomWords.correct(phrase, words, exactOnly) }
    Column(Modifier.padding(top = 16.dp)) {
        Text(stringResource(R.string.ui_dictionary_try), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.ui_dictionary_try_prompt), Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            phrase, { phrase = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ui_dictionary_try_label)) },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), shape = MaterialTheme.shapes.medium,
        )
        if (phrase.isNotBlank()) {
            Text(
                if (corrected == phrase) stringResource(R.string.ui_dictionary_try_same)
                else stringResource(R.string.ui_dictionary_try_result, stringResource(R.string.app_name), corrected),
                Modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            stringResource(R.string.ui_dictionary_try_note), Modifier.padding(start = 4.dp, top = 6.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyDictionary() {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp, start = 16.dp, end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(AppIcons.Book, MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer, size = 88)
        Text(stringResource(R.string.ui_dictionary_empty_title), Modifier.padding(top = 20.dp), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.ui_dictionary_empty_body), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Paste a list: many names or terms at once, and, before Add, how many it will add and skip. */
@Composable
private fun PasteDialog(words: List<String>, onAdd: (WordsAdded) -> Unit, onDismiss: () -> Unit) {
    var list by rememberSaveable { mutableStateOf("") }
    val result = remember(list, words) { addWords(words, list) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_dictionary_paste_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ui_dictionary_paste_body))
                OutlinedTextField(
                    list, { list = it }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ui_dictionary_paste_label)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    minLines = 4, maxLines = 8,
                )
                if (list.isNotBlank()) Text(pasteSummary(result), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton({ onAdd(result) }, enabled = result.added.isNotEmpty()) { Text(stringResource(R.string.ui_dictionary_add)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}

/** "12 will be added. 2 duplicates and 1 long entry will be skipped." */
@Composable
private fun pasteSummary(result: WordsAdded): String {
    val adding = if (result.added.isEmpty()) stringResource(R.string.ui_dictionary_nothing_new)
    else pluralStringResource(R.plurals.ui_dictionary_will_add, result.added.size, result.added.size)
    val skipped = buildList {
        if (result.duplicates > 0) add(pluralStringResource(R.plurals.ui_dictionary_skip_duplicates, result.duplicates, result.duplicates))
        if (result.tooLong > 0) add(pluralStringResource(R.plurals.ui_dictionary_skip_long, result.tooLong, result.tooLong))
        if (result.notFitting > 0) {
            add(pluralStringResource(R.plurals.ui_dictionary_skip_full, result.notFitting, result.notFitting, CustomWords.MAX_ENTRIES))
        }
    }
    if (skipped.isEmpty()) return adding
    val joined = if (skipped.size == 1) skipped[0]
    else stringResource(R.string.ui_dictionary_and, skipped.dropLast(1).joinToString(", "), skipped.last())
    return adding + " " + stringResource(R.string.ui_dictionary_will_skip, joined)
}

/** Edit one entry: Save only for a new spelling that could be added, said by the field as in Add. */
@Composable
private fun EditDialog(old: String, words: List<String>, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(old) }
    val (entry, problem) = checkEntry(value, words, editing = old)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_dictionary_edit_title)) },
        text = { EntryField(value, { value = it }, entry, problem, Modifier.fillMaxWidth(), onDone = { entry?.let(onSave) }) },
        confirmButton = { TextButton({ entry?.let(onSave) }, enabled = entry != null) { Text(stringResource(R.string.ui_dictionary_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}
