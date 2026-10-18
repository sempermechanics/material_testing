package com.sempermechanics.semper.ui.account

import com.sempermechanics.semper.ui.account.LicenseKeyFormat.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The activation field's key handling: what it shows as the user types or
 * pastes, what it refuses before sending, and what it takes from the EMM's
 * `enrollmentInvite`. The key shape is the backend's `generate_key`
 * (`backend/app/licenses.py`); its `canonicalize` ignores case and separators.
 */
class LicenseKeyFormatTest {

    private val key = "SEMP-U8BX-4KQ2-ZP7M-W3RT"

    @Test
    fun `canonical keeps ASCII letters and digits, uppercased, as the backend hashes them`() {
        assertEquals("SEMPU8BX4KQ2ZP7MW3RT", LicenseKeyFormat.canonical("  semp-u8bx 4kq2_zp7m.w3rt\n"))
        // Non-ASCII letters are dropped, as the backend's [^A-Za-z0-9] drops them.
        assertEquals("SMP", LicenseKeyFormat.canonical("SÉMP"))
        assertEquals("", LicenseKeyFormat.canonical(" - _ "))
    }

    @Test
    fun `format uppercases, trims and puts a dash after the prefix and between groups`() {
        assertEquals(key, LicenseKeyFormat.format("semp u8bx4kq2 zp7mw3rt "))
        assertEquals(key, LicenseKeyFormat.format("SEMPU8BX4KQ2ZP7MW3RT"))
        assertEquals(key, LicenseKeyFormat.format("--SEMP--U8BX--4KQ2--ZP7M--W3RT--"))
    }

    @Test
    fun `format builds the key a character at a time without a trailing dash`() {
        assertEquals("", LicenseKeyFormat.format(""))
        assertEquals("S", LicenseKeyFormat.format("s"))
        assertEquals("SEM", LicenseKeyFormat.format("sem"))
        assertEquals("SEMP", LicenseKeyFormat.format("semp"))
        assertEquals("SEMP-U", LicenseKeyFormat.format("sempu"))
        assertEquals("SEMP-U8BX", LicenseKeyFormat.format("SEMP-U8BX"))
        assertEquals("SEMP-U8BX-4", LicenseKeyFormat.format("SEMP-U8BX4"))
        // A backspace over a dash leaves the groups to be laid out again.
        assertEquals("SEMP-U8BX", LicenseKeyFormat.format("SEMP-U8BX-"))
    }

    @Test
    fun `format is idempotent`() {
        listOf("", "s", "SEMP", "SEMP-U8BX-4", key, "ABCD-EFGH", "SEMP-U8BX-4KQ2-ZP7M-W3RT-XY").forEach {
            val once = LicenseKeyFormat.format(it)
            assertEquals(it, once, LicenseKeyFormat.format(once))
        }
    }

    @Test
    fun `a key typed without the prefix is grouped but not given one`() {
        assertEquals("U8BX-4KQ2-ZP7M-W3RT", LicenseKeyFormat.format("u8bx4kq2zp7mw3rt"))
        assertEquals("SABC-D", LicenseKeyFormat.format("sabcd"))
    }

    @Test
    fun `check accepts a key of the right shape and returns it formatted`() {
        assertEquals(Check.Valid(key), LicenseKeyFormat.check(key))
        assertEquals(Check.Valid(key), LicenseKeyFormat.check(" semp-u8bx-4kq2-zp7m-w3rt "))
        assertEquals(Check.Valid(key), LicenseKeyFormat.check("SEMPU8BX4KQ2ZP7MW3RT"))
    }

    @Test
    fun `check leaves which characters a key may hold to the backend`() {
        // 0, 1, O and I are not in generate_key's alphabet, but the server is
        // the one to say a key does not exist; the shape is all this checks.
        assertEquals(Check.Valid("SEMP-0000-1111-OOOO-IIII"), LicenseKeyFormat.check("semp 0000 1111 oooo iiii"))
    }

    @Test
    fun `check names what is wrong with a key of the wrong shape`() {
        assertEquals(Check.Empty, LicenseKeyFormat.check(""))
        assertEquals(Check.Empty, LicenseKeyFormat.check("  - - "))
        assertEquals(Check.MissingPrefix, LicenseKeyFormat.check("U8BX-4KQ2-ZP7M-W3RT"))
        assertEquals(Check.MissingPrefix, LicenseKeyFormat.check("SEM"))
        assertEquals(Check.WrongLength, LicenseKeyFormat.check("SEMP"))
        assertEquals(Check.WrongLength, LicenseKeyFormat.check("SEMP-U8BX-4KQ2-ZP7M"))
        assertEquals(Check.WrongLength, LicenseKeyFormat.check("SEMP-U8BX-4KQ2-ZP7M-W3R"))
        assertEquals(Check.WrongLength, LicenseKeyFormat.check("$key-X"))
    }

    @Test
    fun `the enrollment invite fills the field only when it has a licence key's shape`() {
        assertEquals(key, LicenseKeyFormat.prefillFrom(key))
        assertEquals(key, LicenseKeyFormat.prefillFrom("semp-u8bx-4kq2-zp7m-w3rt"))
        assertNull(LicenseKeyFormat.prefillFrom(null))
        assertNull(LicenseKeyFormat.prefillFrom(""))
        // An invite code, as the EMM description asks for: not a key, ignored.
        assertNull(LicenseKeyFormat.prefillFrom("inv_7Hq2kZ9x"))
        assertNull(LicenseKeyFormat.prefillFrom("U8BX-4KQ2-ZP7M-W3RT"))
        assertNull(LicenseKeyFormat.prefillFrom("SEMP-U8BX-4KQ2"))
    }

    @Test
    fun `the cursor stays after the same key characters across a reformat`() {
        // Typed "u" at the end of "SEMP": the field now reads "SEMPu", cursor 5.
        val typed = "SEMPu"
        val formatted = LicenseKeyFormat.format(typed)
        val keyChars = LicenseKeyFormat.keyCharsBefore(typed, 5)
        assertEquals(5, keyChars)
        assertEquals("SEMP-U", formatted)
        assertEquals(6, LicenseKeyFormat.cursorAfter(formatted, keyChars))

        // A character inserted mid-key: the cursor follows it, past the new dash.
        val inserted = "SEMP-U8BXZ-4KQ2"
        val after = LicenseKeyFormat.format(inserted)
        assertEquals("SEMP-U8BX-Z4KQ-2", after)
        assertEquals(11, LicenseKeyFormat.cursorAfter(after, LicenseKeyFormat.keyCharsBefore(inserted, 10)))
    }

    @Test
    fun `cursor positions are clamped to the text`() {
        assertEquals(0, LicenseKeyFormat.keyCharsBefore("SEMP", -3))
        assertEquals(4, LicenseKeyFormat.keyCharsBefore("SEMP", 99))
        assertEquals(0, LicenseKeyFormat.cursorAfter("SEMP-U", 0))
        assertEquals(6, LicenseKeyFormat.cursorAfter("SEMP-U", 9))
    }
}
