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

/**
 * Sequential reader of the integer streams produced by `scripts/generate_unicode_tables.py`.
 *
 * Integers are encoded in base 46 with 92 printable ASCII symbols (0x21..0x7E without `"` and `$`):
 * symbols 0..45 are final digits, symbols 46..91 are continuation digits (most significant digit
 * first). Line breaks are ignored, so that the data can be wrapped in raw string literals.
 */
internal class EncodedDataReader(private val data: String) {
    private var position = 0

    /** Reads the next unsigned integer. */
    fun next(): Int {
        var result = 0
        while (true) {
            val c = data[position++]
            if (c == '\n' || c == '\r') continue
            var symbol = c.code - 0x21
            if (c > '"') symbol--
            if (c > '$') symbol--
            if (symbol < BASE) return result * BASE + symbol
            result = result * BASE + (symbol - BASE)
        }
    }

    /** Whether there is more data to read. */
    fun hasNext(): Boolean {
        while (position < data.length) {
            val c = data[position]
            if (c != '\n' && c != '\r') return true
            position++
        }
        return false
    }

    private companion object {
        const val BASE = 46
    }
}

/**
 * An immutable map from every code point to a small non-negative integer property value (< 128),
 * decoded from a run-length encoded table of `UnicodeTables.kt`.
 *
 * Lookups are O(1) for the BMP: Latin-1 has a direct table and the rest of the BMP uses a two-level
 * table of 64-code-point blocks, where blocks with a single value need no data at all.
 * Supplementary code points use a binary search over the runs of their 4096-code-point page.
 */
internal class UnicodePropertyTable(encoded: String) {
    private val latin1 = ByteArray(LATIN1_SIZE)

    /**
     * For each BMP block: `value.inv()` (negative) for uniform blocks, else an offset in [bmpData].
     */
    private val bmpIndex = IntArray(BMP_BLOCK_COUNT)
    private val bmpData: ByteArray
    private val supplementaryStarts: IntArray
    private val supplementaryValues: ByteArray

    /** For each supplementary page (and the end), the index of the run containing its start. */
    private val supplementaryPageRuns = IntArray(SUPPLEMENTARY_PAGE_COUNT + 1)

    init {
        val reader = EncodedDataReader(encoded)
        val valueCount = reader.next()
        val values = IntArray(valueCount) { reader.next() }
        val runCount = reader.next()
        // runStarts has a sentinel at the end so that runStarts[i + 1] is the end of run i.
        val runStarts = IntArray(runCount + 1)
        val runValues = ByteArray(runCount)
        var start = 0
        for (i in 0 until runCount) {
            val encodedRun = reader.next()
            runStarts[i] = start
            runValues[i] = values[encodedRun % valueCount].toByte()
            start += encodedRun / valueCount + 1
        }
        runStarts[runCount] = start
        check(start == MAX_CODE_POINT + 1) { "Corrupted Unicode property table" }

        // BMP: classify the blocks, then fill the data of the non-uniform ones.
        var run = 0
        var nonUniformBlocks = 0
        for (block in 0 until BMP_BLOCK_COUNT) {
            val blockStart = block shl BLOCK_SHIFT
            while (runStarts[run + 1] <= blockStart) run++
            if (runStarts[run + 1] >= blockStart + BLOCK_SIZE) {
                bmpIndex[block] = runValues[run].toInt().inv()
            } else {
                bmpIndex[block] = nonUniformBlocks shl BLOCK_SHIFT
                nonUniformBlocks++
            }
        }
        val data = ByteArray(nonUniformBlocks shl BLOCK_SHIFT)
        run = 0
        for (block in 0 until BMP_BLOCK_COUNT) {
            val offset = bmpIndex[block]
            if (offset < 0) continue
            val blockStart = block shl BLOCK_SHIFT
            for (i in 0 until BLOCK_SIZE) {
                while (runStarts[run + 1] <= blockStart + i) run++
                data[offset + i] = runValues[run]
            }
        }
        bmpData = data
        for (cp in 0 until LATIN1_SIZE) latin1[cp] = lookupBmp(cp).toByte()

        // Supplementary planes: the runs intersecting U+10000..U+10FFFF.
        var first = 0
        while (runStarts[first + 1] <= MIN_SUPPLEMENTARY_CODE_POINT) first++
        val starts = IntArray(runCount - first) { maxOf(runStarts[first + it], 0x10000) }
        supplementaryStarts = starts
        supplementaryValues = ByteArray(runCount - first) { runValues[first + it] }
        run = 0
        for (page in 0..SUPPLEMENTARY_PAGE_COUNT) {
            val pageStart = MIN_SUPPLEMENTARY_CODE_POINT + (page shl PAGE_SHIFT)
            while (run + 1 < starts.size && starts[run + 1] <= pageStart) run++
            supplementaryPageRuns[page] = run
        }
    }

    /** Returns the property value of [codePoint]; invalid code points map to 0. */
    operator fun get(codePoint: Int): Int {
        if (codePoint ushr 8 == 0) return latin1[codePoint].toInt()
        if (codePoint ushr 16 == 0) return lookupBmp(codePoint)
        return lookupSupplementary(codePoint)
    }

    private fun lookupBmp(codePoint: Int): Int {
        val block = bmpIndex[codePoint ushr BLOCK_SHIFT]
        return if (block < 0) block.inv() else bmpData[block + (codePoint and BLOCK_MASK)].toInt()
    }

    private fun lookupSupplementary(codePoint: Int): Int {
        if (codePoint < 0 || codePoint > MAX_CODE_POINT) return 0
        val starts = supplementaryStarts
        val page = (codePoint - MIN_SUPPLEMENTARY_CODE_POINT) ushr PAGE_SHIFT
        // The run of the code point is between the runs containing the start of its page and the
        // start of the next page.
        var low = supplementaryPageRuns[page]
        var high = supplementaryPageRuns[page + 1]
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (starts[mid] <= codePoint) low = mid else high = mid - 1
        }
        return supplementaryValues[low].toInt()
    }

    private companion object {
        const val LATIN1_SIZE = 0x100
        const val BLOCK_SHIFT = 6
        const val BLOCK_SIZE = 1 shl BLOCK_SHIFT
        const val BLOCK_MASK = BLOCK_SIZE - 1
        const val BMP_BLOCK_COUNT = 0x10000 shr BLOCK_SHIFT
        const val PAGE_SHIFT = 12
        const val SUPPLEMENTARY_PAGE_COUNT = (MAX_CODE_POINT + 1 - 0x10000) shr PAGE_SHIFT
    }
}

/**
 * A sorted code point -> int map for the small sparse tables (bidi brackets and mirroring), with
 * binary search lookups.
 */
internal class SparseCodePointMap(val keys: IntArray, val values: IntArray) {
    /** Index of [codePoint] in [keys], or -1. */
    fun indexOf(codePoint: Int): Int {
        var low = 0
        var high = keys.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val key = keys[mid]
            when {
                key < codePoint -> low = mid + 1
                key > codePoint -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }
}
