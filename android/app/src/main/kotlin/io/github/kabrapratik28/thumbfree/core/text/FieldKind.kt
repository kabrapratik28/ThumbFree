package io.github.kabrapratik28.thumbfree.core.text

/** android.text.InputType's bit layout, copied so core.* need not import android.* (CorePurityTest). */
object FieldKind {
    const val TYPE_CLASS_TEXT = 0x1
    const val TYPE_CLASS_NUMBER = 0x2
    const val TYPE_CLASS_PHONE = 0x3
    const val TYPE_CLASS_DATETIME = 0x4
    const val CLASS_MASK = 0xf
    const val VARIATION_MASK = 0xff0

    /** Text variations 0x80 password, 0x90 visible password, 0xe0 web password; number variation 0x10 password; or the node says so. */
    fun isPassword(inputType: Int, nodeIsPassword: Boolean): Boolean {
        if (nodeIsPassword) return true
        val variation = inputType and VARIATION_MASK
        return when (inputType and CLASS_MASK) {
            TYPE_CLASS_TEXT -> variation == 0x80 || variation == 0x90 || variation == 0xe0
            TYPE_CLASS_NUMBER -> variation == 0x10
            else -> false
        }
    }

    /** False for the number, phone and datetime classes and for passwords (variations or the node flag). Class 0 stays
     *  dictatable: some web editors report no class on their node, and a non-editable TYPE_NULL view is excluded by `editable`. */
    fun isDictatable(inputType: Int, nodeIsPassword: Boolean): Boolean =
        !isPassword(inputType, nodeIsPassword) &&
            (inputType and CLASS_MASK) !in setOf(TYPE_CLASS_NUMBER, TYPE_CLASS_PHONE, TYPE_CLASS_DATETIME)

    /** Password variations plus text variations 0x10 URI, 0x20 email, 0xd0 web email: insert exactly, no spacing or case changes. */
    fun isExactText(inputType: Int): Boolean {
        val variation = inputType and VARIATION_MASK
        return isPassword(inputType, false) ||
            ((inputType and CLASS_MASK) == TYPE_CLASS_TEXT && (variation == 0x10 || variation == 0x20 || variation == 0xd0))
    }
}
