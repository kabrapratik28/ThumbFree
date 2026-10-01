package io.github.kabrapratik28.thumbfree.app

import java.lang.reflect.Modifier

/**
 * Robolectric runs every test class in one sandbox, so AppGraph outlives a test. This keeps every field a test can set,
 * lateinit ones not set yet included, and [restore] puts them all back. A lateinit cannot be unset from Kotlin.
 */
internal class AppGraphSnapshot {
    private val fields = AppGraph::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) }
        .onEach { it.isAccessible = true }
    private val values = fields.map { it.get(null) }

    fun restore() = fields.zip(values).forEach { (field, value) -> field.set(null, value) }
}
