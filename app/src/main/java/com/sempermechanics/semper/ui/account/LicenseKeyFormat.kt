package com.sempermechanics.semper.ui.account

/**
 * The licence key's shape, `SEMP-XXXX-XXXX-XXXX-XXXX`
 * (`generate_key`, `backend/app/licenses.py`), for the activation field.
 *
 * The backend hashes the key after `canonicalize` strips everything that is
 * not an ASCII letter or digit and uppercases the rest, so case, spaces and
 * dashes never decide whether a key matches. This side does the same before
 * it formats or checks, and checks only the shape: the prefix and the length.
 * Which characters a key may hold is the backend's to decide, so a key the
 * server would take is never refused here.
 */
object LicenseKeyFormat {

    const val PREFIX = "SEMP"
    const val GROUPS = 4
    const val GROUP_LENGTH = 4
    const val SEPARATOR = '-'

    /** Letters and digits in a whole key, the prefix included. */
    const val KEY_CHARS = PREFIX.length + GROUPS * GROUP_LENGTH

    /** A typed or pasted key is one of these. */
    sealed interface Check {
        /** A key of the right shape, written as the backend prints it. */
        data class Valid(val key: String) : Check

        /** Nothing but separators or spaces. */
        data object Empty : Check

        /** The key does not start with [PREFIX]. */
        data object MissingPrefix : Check

        /** The prefix is there, but not four groups of four after it. */
        data object WrongLength : Check
    }

    /** [raw]'s ASCII letters and digits, uppercased: the backend's `canonicalize`. */
    fun canonical(raw: CharSequence): String = buildString {
        for (c in raw) if (isKeyChar(c)) append(c.uppercaseChar())
    }

    /**
     * [raw] as the field shows it: uppercased, trimmed of anything that is not
     * a letter or digit, with a dash after the prefix and between groups.
     *
     * While the prefix is still being typed (`S`, `SE`, …) it is left alone.
     * A key typed without the prefix is grouped without one, so [check] can
     * say what is missing rather than the field inventing it. Idempotent: a
     * formatted key formats to itself.
     */
    fun format(raw: CharSequence): String {
        val chars = canonical(raw)
        return when {
            chars.startsWith(PREFIX) ->
                PREFIX + chars.drop(PREFIX.length).chunked(GROUP_LENGTH).joinToString("") { "$SEPARATOR$it" }
            PREFIX.startsWith(chars) -> chars
            else -> chars.chunked(GROUP_LENGTH).joinToString(SEPARATOR.toString())
        }
    }

    /** Whether [raw] has a licence key's shape; a [Check.Valid] carries it formatted. */
    fun check(raw: CharSequence): Check {
        val chars = canonical(raw)
        return when {
            chars.isEmpty() -> Check.Empty
            !chars.startsWith(PREFIX) -> Check.MissingPrefix
            chars.length != KEY_CHARS -> Check.WrongLength
            else -> Check.Valid(format(chars))
        }
    }

    /**
     * The key to put in the field from the EMM's `enrollmentInvite`
     * ([com.sempermechanics.semper.data.policy.ManagedConfig.enrollmentInvite]),
     * or null to leave the field empty.
     *
     * The key is documented as a short-lived invite code, never a licence key.
     * No route redeems an invite code yet, so only a value with a licence key's
     * shape is used, and only to fill the field: the user still reads it and
     * taps Activate. Anything else is ignored.
     */
    fun prefillFrom(enrollmentInvite: String?): String? =
        (enrollmentInvite?.let(::check) as? Check.Valid)?.key

    /** How many key characters (letters and digits) [text] holds before [end]. */
    fun keyCharsBefore(text: CharSequence, end: Int): Int {
        var count = 0
        for (i in 0 until end.coerceIn(0, text.length)) if (isKeyChar(text[i])) count++
        return count
    }

    /**
     * Where the cursor goes in [formatted] so it sits after the same [keyChars]
     * letters and digits it followed before the text was reformatted.
     */
    fun cursorAfter(formatted: CharSequence, keyChars: Int): Int {
        var seen = 0
        var index = 0
        while (index < formatted.length && seen < keyChars) {
            if (isKeyChar(formatted[index])) seen++
            index++
        }
        return index
    }

    private fun isKeyChar(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'
}
