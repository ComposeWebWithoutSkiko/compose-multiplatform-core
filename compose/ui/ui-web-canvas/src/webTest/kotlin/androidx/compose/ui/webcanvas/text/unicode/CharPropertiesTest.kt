/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.webcanvas.text.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CharPropertiesTest {

    @Test
    fun whitespace() {
        for (c in listOf(' ', '\t', '\n', '\u00A0', '\u2007', '\u3000', '\u2028', '\u0085')) {
            assertTrue(isWhitespace(c.code), "U+${c.code.toString(16)}")
        }
        assertFalse(isWhitespace('a'.code))
        assertFalse(isWhitespace(0x200B)) // ZERO WIDTH SPACE is not White_Space
        assertFalse(isWhitespace(0xFEFF))
    }

    @Test
    fun emoji() {
        assertTrue(isEmojiPresentation(0x1F600))
        assertFalse(isEmojiPresentation(0x263A)) // WHITE SMILING FACE defaults to text
        assertFalse(isEmojiPresentation('1'.code))
        assertTrue(isExtendedPictographic(0x00A9)) // COPYRIGHT SIGN
        assertTrue(isExtendedPictographic(0x1F600))
        assertTrue(isExtendedPictographic(0x1FFFD)) // unassigned, reserved for emoji
        assertFalse(isExtendedPictographic('a'.code))
        assertTrue(isUnassignedExtendedPictographic(0x1FFFD))
        assertFalse(isUnassignedExtendedPictographic(0x1F600))
        assertFalse(isUnassignedExtendedPictographic(0x0378)) // unassigned, not pictographic
    }

    @Test
    fun codePoints() {
        // Built at runtime: Kotlin/JS does not preserve unpaired surrogates in string literals.
        val text = buildString {
            append("a\uD83D\uDE00")
            append(Char(0xD800))
        }
        assertEquals('a'.code, codePointAt(text, 0))
        assertEquals(0x1F600, codePointAt(text, 1))
        assertEquals(0xDE00, codePointAt(text, 2)) // a low surrogate on its own
        assertEquals(0xD800, codePointAt(text, 3)) // unpaired high surrogate
        assertEquals(0x1F600, codePointBefore(text, 3))
        assertEquals(0xD83D, codePointBefore(text, 2))
        assertEquals(2, charCount(0x1F600))
        assertEquals(1, charCount('a'.code))
        assertTrue(isInsideSurrogatePair(text, 2))
        assertFalse(isInsideSurrogatePair(text, 1))
        assertFalse(isInsideSurrogatePair(text, 4))
    }
}
