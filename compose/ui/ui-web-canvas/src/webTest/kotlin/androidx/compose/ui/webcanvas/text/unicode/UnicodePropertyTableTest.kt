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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnicodePropertyTableTest {

    @Test
    fun readerDecodesVariableLengthIntegers() {
        val reader = EncodedDataReader("!P\nR!" + encode(12345, 0x10FFFF))
        assertEquals(0, reader.next())
        assertEquals(45, reader.next())
        assertEquals(46, reader.next()) // newlines are ignored
        assertEquals(12345, reader.next())
        assertEquals(0x10FFFF, reader.next())
        assertFalse(reader.hasNext())
        val signed = EncodedDataReader(encode(0, 1, 2, 3, 4))
        assertContentEquals(intArrayOf(0, -1, 1, -2, 2), IntArray(5) { signed.nextSigned() })
    }

    @Test
    fun tableLookups() {
        // Runs: [0, 0x41) -> 0, [0x41, 0x5B) -> 1, [0x5B, 0x1234) -> 0, [0x1234] -> 2,
        // [0x1235, 0x10000) -> 0, [0x10000, 0x20000) -> 3, [0x20000, 0x10FFFF) -> 0, 0x10FFFF -> 4
        val table =
            UnicodePropertyTable(
                encodeTable(
                    0 to 0x41,
                    1 to 0x1A,
                    0 to 0x1234 - 0x5B,
                    2 to 1,
                    0 to 0x10000 - 0x1235,
                    3 to 0x10000,
                    0 to 0x10FFFF - 0x20000,
                    4 to 1,
                )
            )
        assertEquals(0, table[0])
        assertEquals(0, table[0x40])
        assertEquals(1, table[0x41])
        assertEquals(1, table[0x5A])
        assertEquals(0, table[0x5B])
        assertEquals(0, table[0x1233])
        assertEquals(2, table[0x1234])
        assertEquals(0, table[0x1235])
        assertEquals(0, table[0xFFFF])
        assertEquals(3, table[0x10000])
        assertEquals(3, table[0x1FFFF])
        assertEquals(0, table[0x20000])
        assertEquals(0, table[0x10FFFE])
        assertEquals(4, table[0x10FFFF])
        assertEquals(0, table[-1])
        assertEquals(0, table[0x110000])
        assertEquals(0, table[Int.MIN_VALUE])
    }

    @Test
    fun corruptedTablesAreRejected() {
        assertFailsWith<IllegalStateException> { UnicodePropertyTable(encodeTable(1 to 0x10000)) }
    }

    @Test
    fun sparseMap() {
        val map = SparseCodePointMap(intArrayOf(3, 7, 0x10000), intArrayOf(30, 70, 100))
        assertEquals(0, map.indexOf(3))
        assertEquals(1, map.indexOf(7))
        assertEquals(2, map.indexOf(0x10000))
        assertEquals(-1, map.indexOf(4))
        assertEquals(-1, map.indexOf(0x10001))
    }

    @Test
    fun generatedTablesHaveTheExpectedVersion() {
        assertTrue(UNICODE_VERSION.isNotEmpty())
    }

    /** Encodes runs of (value, length) like generate_unicode_tables.py. */
    private fun encodeTable(vararg runs: Pair<Int, Int>): String {
        val values = runs.map { it.first }.distinct().sorted()
        val numbers = mutableListOf(values.size)
        numbers += values
        numbers += runs.size
        for ((value, length) in runs) numbers += (length - 1) * values.size + values.indexOf(value)
        return encode(*numbers.toIntArray())
    }

    private fun encode(vararg numbers: Int): String = buildString {
        for (number in numbers) {
            val digits = mutableListOf<Int>()
            var n = number
            do {
                digits.add(0, n % 46)
                n /= 46
            } while (n > 0)
            for ((i, digit) in digits.withIndex()) {
                append(symbol(if (i < digits.size - 1) 46 + digit else digit))
            }
        }
    }

    private fun symbol(value: Int): Char {
        var code = 0x21 + value
        if (code >= '"'.code) code++
        if (code >= '$'.code) code++
        return code.toChar()
    }
}
