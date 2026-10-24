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

import androidx.compose.ui.webcanvas.text.unicode.BidiClass.AL
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.AN
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.B
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.BN
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.CS
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.EN
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.ES
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.ET
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.FSI
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.L
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.LRE
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.LRI
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.LRO
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.NSM
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.ON
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.PDF
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.PDI
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.R
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.RLE
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.RLI
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.RLO
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.S
import androidx.compose.ui.webcanvas.text.unicode.BidiClass.WS

/**
 * The Unicode Bidirectional Algorithm ([UAX #9](https://www.unicode.org/reports/tr9/)) applied to
 * `text[start, end)`: resolves the embedding level of every UTF-16 code unit (rules P2-P3, X1-X10,
 * W1-W7, N0-N2 and I1-I2) and provides the per-line rules L1 and L2.
 *
 * The range may contain several bidi paragraphs: following rule P1 it is split after every
 * paragraph separator (Bidi_Class B, e.g. `\n`, `\r`, U+2029; `\r\n` counts as one separator) and
 * every paragraph is resolved independently. With an explicit [requestedLevel] all paragraphs get
 * that level; with [LEVEL_DEFAULT_LTR] or [LEVEL_DEFAULT_RTL] every paragraph determines its own
 * level from its first strong character (like ICU and Android).
 *
 * Both code units of a surrogate pair always get the same level. Characters removed by rule X9
 * (Bidi_Class BN, e.g. ZWJ, and the explicit embedding controls LRE, RLE, LRO, RLO, PDF) get the
 * level of the preceding character (or the paragraph level at the start of a paragraph), so they
 * never split a run, as recommended by section 5.2 of UAX #9.
 *
 * Instances are immutable. The constructor is not thread-safe (it reuses internal buffers), which
 * is fine on the single-threaded web targets.
 *
 * @param text the text; it must not change while this object is in use.
 * @param start start (inclusive) of the range of [text] to process.
 * @param end end (exclusive) of the range of [text] to process.
 * @param requestedLevel [LEVEL_LTR], [LEVEL_RTL], [LEVEL_DEFAULT_LTR] (rules P2-P3, falling back to
 *   LTR when there is no strong character) or [LEVEL_DEFAULT_RTL] (falling back to RTL).
 */
internal class BidiParagraph(
    val text: CharSequence,
    val start: Int = 0,
    val end: Int = text.length,
    requestedLevel: Int = LEVEL_DEFAULT_LTR,
) {
    /**
     * The resolved embedding levels, one per UTF-16 code unit: `levels[i]` is the level of
     * `text[start + i]`. These are the levels before the line-dependent rule L1, see [lineLevels].
     * Do not modify.
     */
    val levels: ByteArray

    /** Number of bidi paragraphs in the range (at least 1, even for an empty range). */
    val paragraphCount: Int

    /** Exclusive end (absolute text index) of each paragraph. */
    private val paragraphLimits: IntArray

    private val paragraphLevels: ByteArray

    /**
     * The original Bidi_Class of every code unit, needed by rule L1, or null if every paragraph is
     * trivially left-to-right (all levels 0), in which case L1 is a no-op.
     */
    private val classes: ByteArray?

    /** True if every level is 0, i.e. the text needs no bidi reordering at all. */
    val isLeftToRight: Boolean

    init {
        require(start in 0..end && end <= text.length) {
            "Invalid range [$start, $end) for text of length ${text.length}"
        }
        require(requestedLevel in LEVEL_DEFAULT_RTL..LEVEL_RTL) {
            "Invalid requested level $requestedLevel"
        }
        val length = end - start
        levels = ByteArray(length)

        // Rule P1: split into paragraphs after each paragraph separator.
        var count = 0
        var limits = IntArray(1)
        var i = start
        while (i < end) {
            val c = text[i]
            i++
            if (isParagraphSeparator(c)) {
                if (c == '\r' && i < end && text[i] == '\n') i++
                if (i < end) {
                    if (count == limits.size) limits = limits.copyOf(count * 2)
                    limits[count++] = i
                }
            }
        }
        if (count == limits.size) limits = limits.copyOf(count + 1)
        limits[count++] = end
        paragraphCount = count
        paragraphLimits = limits
        paragraphLevels = ByteArray(count)

        var classesOrNull: ByteArray? = null
        val resolver = BidiResolver.instance
        var from = start
        for (p in 0 until count) {
            val to = limits[p]
            paragraphLevels[p] = resolver.resolveParagraph(text, from, to, requestedLevel).toByte()
            if (!resolver.isTriviallyLeftToRight) {
                val classesArray = classesOrNull ?: ByteArray(length).also { classesOrNull = it }
                resolver.writeResults(levels, classesArray, from - start)
            }
            from = to
        }
        resolver.trimBuffers()
        classes = classesOrNull
        isLeftToRight = classesOrNull == null || levels.all { it.toInt() == 0 }
    }

    /** The paragraph embedding level of the first paragraph: 0 for LTR, 1 for RTL. */
    val paragraphLevel: Int
        get() = paragraphLevels[0].toInt()

    /** The paragraph embedding level (0 or 1) of the paragraph containing text index [index]. */
    fun paragraphLevelAt(index: Int): Int = paragraphLevels[paragraphIndexOf(index)].toInt()

    /** Index of the paragraph containing text index [index] (clamped to the valid paragraphs). */
    fun paragraphIndexOf(index: Int): Int {
        var low = 0
        var high = paragraphCount - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (paragraphLimits[mid] <= index) low = mid + 1 else high = mid
        }
        return low
    }

    /** Start (inclusive, absolute text index) of paragraph number [paragraph]. */
    fun paragraphStart(paragraph: Int): Int =
        if (paragraph == 0) start else paragraphLimits[paragraph - 1]

    /** End (exclusive, absolute text index) of paragraph number [paragraph]. */
    fun paragraphEnd(paragraph: Int): Int = paragraphLimits[paragraph]

    /** The resolved level (before rule L1) of the code unit at text index [index]. */
    fun levelAt(index: Int): Int = levels[index - start].toInt()

    /**
     * Returns the end (exclusive) of the run of code units with the same resolved level that starts
     * at text index [index], limited to [limit]. Iterating with this splits a paragraph into its
     * directional runs (e.g. for measuring), before line breaking and rule L1.
     */
    fun levelRunEnd(index: Int, limit: Int = end): Int {
        require(index in start until limit && limit <= end) {
            "Invalid run start $index or limit $limit for bidi range [$start, $end)"
        }
        val level = levels[index - start]
        var i = index + 1
        while (i < limit && levels[i - start] == level) i++
        return i
    }

    /**
     * Returns the levels of the line `text[lineStart, lineEnd)` after applying rule L1: segment
     * separators (tabs), paragraph separators, and any sequence of whitespace, isolate formatting
     * characters and characters removed by X9 that precedes them or ends the line are reset to the
     * paragraph level. `result[i]` is the level of `text[lineStart + i]`.
     */
    fun lineLevels(lineStart: Int, lineEnd: Int): ByteArray {
        checkLine(lineStart, lineEnd)
        val result = levels.copyOfRange(lineStart - start, lineEnd - start)
        val classes = classes ?: return result
        var paragraph = paragraphIndexOf(lineEnd - 1)
        var level = paragraphLevels[paragraph]
        var levelStart = paragraphStart(paragraph)
        // Scan backwards: `resetting` is true while we are in a sequence of whitespace that ends
        // the line or precedes a segment/paragraph separator.
        var resetting = true
        for (i in lineEnd - 1 downTo lineStart) {
            if (i < levelStart) {
                paragraph--
                level = paragraphLevels[paragraph]
                levelStart = paragraphStart(paragraph)
            }
            when (classes[i - start].toInt()) {
                S,
                B -> {
                    result[i - lineStart] = level
                    resetting = true
                }
                WS,
                LRI,
                RLI,
                FSI,
                PDI,
                BN,
                LRE,
                RLE,
                LRO,
                RLO,
                PDF -> if (resetting) result[i - lineStart] = level
                else -> resetting = false
            }
        }
        return result
    }

    /**
     * Returns the runs of the line `text[lineStart, lineEnd)` in visual order (left to right),
     * after applying rules L1 and L2. Each run is a maximal sequence of code units with the same
     * level, encoded as three consecutive values `start, end, level` (absolute text indices, end
     * exclusive). A run with an odd level is right-to-left: its characters are displayed in reverse
     * order (glyph shaping and mirroring, rule L4, are up to the renderer).
     */
    fun visualRuns(lineStart: Int, lineEnd: Int): IntArray =
        visualRunsOf(lineLevels(lineStart, lineEnd), lineStart)

    /**
     * Returns the absolute text indices of the line `text[lineStart, lineEnd)` in visual order
     * (left to right) according to rules L1 and L2. Code units of a surrogate pair stay in logical
     * order (high, low) within right-to-left runs, i.e. code points are reordered, not code units.
     */
    fun visualOrder(lineStart: Int, lineEnd: Int): IntArray {
        val runs = visualRuns(lineStart, lineEnd)
        val result = IntArray(lineEnd - lineStart)
        var out = 0
        var r = 0
        while (r < runs.size) {
            val runStart = runs[r]
            val runEnd = runs[r + 1]
            if (runs[r + 2] and 1 == 0) {
                for (i in runStart until runEnd) result[out++] = i
            } else {
                var i = runEnd
                while (i > runStart) {
                    i--
                    if (i > runStart && text[i].isLowSurrogate() && text[i - 1].isHighSurrogate()) {
                        result[out++] = i - 1
                        result[out++] = i
                        i--
                    } else {
                        result[out++] = i
                    }
                }
            }
            r += 3
        }
        return result
    }

    private fun checkLine(lineStart: Int, lineEnd: Int) {
        require(lineStart in start..lineEnd && lineEnd <= end) {
            "Invalid line [$lineStart, $lineEnd) for bidi range [$start, $end)"
        }
    }

    companion object {
        /** Left-to-right paragraph level. */
        const val LEVEL_LTR: Int = 0

        /** Right-to-left paragraph level. */
        const val LEVEL_RTL: Int = 1

        /** Paragraph level from the first strong character (P2-P3), LTR if there is none. */
        const val LEVEL_DEFAULT_LTR: Int = -1

        /** Paragraph level from the first strong character (P2-P3), RTL if there is none. */
        const val LEVEL_DEFAULT_RTL: Int = -2

        /** The maximum explicit embedding level (max_depth of UAX #9). */
        const val MAX_DEPTH: Int = 125

        /**
         * Applies rule L2 to the [levels] of a line: returns the runs in visual order encoded as in
         * [visualRuns], where the index of `levels[i]` is `offset + i`.
         */
        fun visualRunsOf(levels: ByteArray, offset: Int = 0): IntArray {
            val length = levels.size
            if (length == 0) return IntArray(0)
            // Logical runs.
            var runCount = 1
            for (i in 1 until length) if (levels[i] != levels[i - 1]) runCount++
            val runStarts = IntArray(runCount + 1)
            var maxLevel = 0
            var minOddLevel = Int.MAX_VALUE
            var r = 0
            for (i in 0 until length) {
                val level = levels[i].toInt()
                if (i == 0 || level != levels[i - 1].toInt()) runStarts[r++] = i
                if (level > maxLevel) maxLevel = level
                if (level and 1 != 0 && level < minOddLevel) minOddLevel = level
            }
            runStarts[runCount] = length
            // Rule L2: from the highest level to the lowest odd level, reverse any contiguous
            // sequence of runs at that level or higher.
            val order = IntArray(runCount) { it }
            var level = maxLevel
            while (level >= minOddLevel) {
                var i = 0
                while (i < runCount) {
                    if (levels[runStarts[order[i]]] >= level) {
                        var j = i
                        while (j + 1 < runCount && levels[runStarts[order[j + 1]]] >= level) j++
                        order.reverse(i, j + 1)
                        i = j + 1
                    } else {
                        i++
                    }
                }
                level--
            }
            val result = IntArray(runCount * 3)
            for (v in 0 until runCount) {
                val run = order[v]
                result[v * 3] = offset + runStarts[run]
                result[v * 3 + 1] = offset + runStarts[run + 1]
                result[v * 3 + 2] = levels[runStarts[run]].toInt()
            }
            return result
        }

        /**
         * Applies rule L2 to [levels]: returns, for each visual position (left to right), the index
         * into [levels] of the element displayed there. Elements are reversed individually (no
         * surrogate or cluster handling).
         */
        fun reorderVisually(levels: ByteArray): IntArray {
            val runs = visualRunsOf(levels)
            val result = IntArray(levels.size)
            var out = 0
            var r = 0
            while (r < runs.size) {
                if (runs[r + 2] and 1 == 0) {
                    for (i in runs[r] until runs[r + 1]) result[out++] = i
                } else {
                    for (i in runs[r + 1] - 1 downTo runs[r]) result[out++] = i
                }
                r += 3
            }
            return result
        }

        private fun isParagraphSeparator(c: Char): Boolean =
            when (c) {
                '\n',
                '\r',
                '\u001C',
                '\u001D',
                '\u001E',
                '\u0085',
                '\u2029' -> true
                else -> false
            }
    }
}

/**
 * The implementation of the paragraph-level rules of UAX #9 for a single paragraph, operating on
 * code points. It keeps its working buffers between calls to avoid allocations.
 *
 * The structure follows the Java reference implementation of UAX #9 (BidiReference.java).
 */
private class BidiResolver {
    // Per code point of the current paragraph.
    private var size = 0
    private var codePoints = IntArray(INITIAL_CAPACITY)
    private var offsets = IntArray(INITIAL_CAPACITY) // text index of each code point
    private var initialTypes = ByteArray(INITIAL_CAPACITY)
    private var types = ByteArray(INITIAL_CAPACITY) // types after the explicit rules (X1-X8)
    private var explicitLevels = ByteArray(INITIAL_CAPACITY)
    private var resultLevels = ByteArray(INITIAL_CAPACITY)
    private var matchingPdi = IntArray(INITIAL_CAPACITY)
    private var matchingInitiator = IntArray(INITIAL_CAPACITY)
    private var runForChar = IntArray(INITIAL_CAPACITY)
    private var sequence = IntArray(INITIAL_CAPACITY) // indexes of the current isolating sequence
    private var sequenceTypes = ByteArray(INITIAL_CAPACITY)

    // Level runs.
    private var runFirst = IntArray(16)
    private var runLast = IntArray(16)

    // Directional status stack (X1-X8).
    private val stackLevel = IntArray(BidiParagraph.MAX_DEPTH + 2)
    private val stackOverride = IntArray(BidiParagraph.MAX_DEPTH + 2)
    private val stackIsolate = BooleanArray(BidiParagraph.MAX_DEPTH + 2)

    // Bracket pairs (N0).
    private val bracketStackCodePoint = IntArray(MAX_BRACKET_STACK)
    private val bracketStackPosition = IntArray(MAX_BRACKET_STACK)
    private var pairOpeners = IntArray(16)
    private var pairClosers = IntArray(16)

    private var paragraphLevel = 0

    /** Whether the last resolved paragraph has only level 0 (and nothing was computed). */
    var isTriviallyLeftToRight = false
        private set

    /**
     * Resolves the levels of the paragraph `text[start, end)` and returns its paragraph level.
     * Unless [isTriviallyLeftToRight], call [writeResults] to get the levels.
     */
    fun resolveParagraph(text: CharSequence, start: Int, end: Int, requestedLevel: Int): Int {
        val classes = BidiData.classes
        // Decode the code points.
        ensureCapacity(end - start)
        var n = 0
        var needsFullAlgorithm = false
        var hasStrongL = false
        var i = start
        while (i < end) {
            var codePoint = text[i].code
            if (codePoint in 0xD800..0xDBFF && i + 1 < end) {
                val low = text[i + 1].code
                if (low in 0xDC00..0xDFFF) {
                    codePoint = ((codePoint - 0xD800) shl 10) + (low - 0xDC00) + 0x10000
                }
            }
            val type = classes[codePoint]
            codePoints[n] = codePoint
            offsets[n] = i
            initialTypes[n] = type.toByte()
            when (type) {
                L -> hasStrongL = true
                R,
                AL,
                AN,
                LRE,
                LRO,
                RLE,
                RLO,
                PDF,
                LRI,
                RLI,
                FSI,
                PDI -> needsFullAlgorithm = true
            }
            i += charCount(codePoint)
            n++
        }
        size = n

        // Fast path: without right-to-left characters and explicit formatting characters, a
        // paragraph with level 0 resolves to level 0 everywhere.
        if (!needsFullAlgorithm) {
            val level =
                when (requestedLevel) {
                    BidiParagraph.LEVEL_LTR -> 0
                    BidiParagraph.LEVEL_RTL -> 1
                    BidiParagraph.LEVEL_DEFAULT_LTR -> 0
                    else -> if (hasStrongL) 0 else 1
                }
            if (level == 0) {
                isTriviallyLeftToRight = true
                paragraphLevel = 0
                return 0
            }
        }
        isTriviallyLeftToRight = false

        determineMatchingIsolates()
        paragraphLevel =
            when (requestedLevel) {
                BidiParagraph.LEVEL_LTR -> 0
                BidiParagraph.LEVEL_RTL -> 1
                else ->
                    when (firstStrongType(0, n)) {
                        L -> 0
                        R -> 1
                        else -> if (requestedLevel == BidiParagraph.LEVEL_DEFAULT_RTL) 1 else 0
                    }
            }
        determineExplicitEmbeddingLevels()
        resolveIsolatingRunSequences()
        assignLevelsToCharactersRemovedByX9()
        return paragraphLevel
    }

    /**
     * Writes the levels and the original classes of the last resolved paragraph, expanded to code
     * units, to [levels] and [classes] starting at index [offset].
     */
    fun writeResults(levels: ByteArray, classes: ByteArray, offset: Int) {
        val base = offset - offsets[0]
        for (k in 0 until size) {
            val index = offsets[k] + base
            levels[index] = resultLevels[k]
            classes[index] = initialTypes[k]
            if (codePoints[k] >= MIN_SUPPLEMENTARY_CODE_POINT) {
                levels[index + 1] = resultLevels[k]
                classes[index + 1] = initialTypes[k]
            }
        }
    }

    /** Releases the working buffers if a very long paragraph made them grow. */
    fun trimBuffers() {
        if (codePoints.size > MAX_RETAINED_CAPACITY) allocate(INITIAL_CAPACITY)
        if (pairOpeners.size > MAX_RETAINED_CAPACITY) {
            pairOpeners = IntArray(16)
            pairClosers = IntArray(16)
        }
        if (runFirst.size > MAX_RETAINED_CAPACITY) {
            runFirst = IntArray(16)
            runLast = IntArray(16)
        }
    }

    private fun ensureCapacity(capacity: Int) {
        if (codePoints.size < capacity) allocate(maxOf(capacity, codePoints.size * 2))
    }

    private fun allocate(newCapacity: Int) {
        codePoints = IntArray(newCapacity)
        offsets = IntArray(newCapacity)
        initialTypes = ByteArray(newCapacity)
        types = ByteArray(newCapacity)
        explicitLevels = ByteArray(newCapacity)
        resultLevels = ByteArray(newCapacity)
        matchingPdi = IntArray(newCapacity)
        matchingInitiator = IntArray(newCapacity)
        runForChar = IntArray(newCapacity)
        sequence = IntArray(newCapacity)
        sequenceTypes = ByteArray(newCapacity)
    }

    /** BD9: finds the matching PDI of every isolate initiator (and vice versa), or -1. */
    private fun determineMatchingIsolates() {
        // `runForChar` is free at this point and serves as the stack of open isolate initiators.
        val stack = runForChar
        var depth = 0
        for (k in 0 until size) {
            matchingPdi[k] = -1
            matchingInitiator[k] = -1
            when (initialTypes[k].toInt()) {
                LRI,
                RLI,
                FSI -> stack[depth++] = k
                PDI ->
                    if (depth > 0) {
                        val initiator = stack[--depth]
                        matchingPdi[initiator] = k
                        matchingInitiator[k] = initiator
                    }
            }
        }
    }

    /**
     * Rules P2-P3: returns L or R for the first strong character in `[from, to)`, skipping
     * isolates, or ON if there is none.
     */
    private fun firstStrongType(from: Int, to: Int): Int {
        var k = from
        while (k < to) {
            when (initialTypes[k].toInt()) {
                L -> return L
                R,
                AL -> return R
                LRI,
                RLI,
                FSI -> {
                    k = matchingPdi[k]
                    if (k < 0) return ON
                }
            }
            k++
        }
        return ON
    }

    /** Rules X1-X8. */
    private fun determineExplicitEmbeddingLevels() {
        val n = size
        initialTypes.copyInto(types, 0, 0, n)
        var top = 0
        stackLevel[0] = paragraphLevel
        stackOverride[0] = ON
        stackIsolate[0] = false
        var overflowIsolateCount = 0
        var overflowEmbeddingCount = 0
        var validIsolateCount = 0
        for (k in 0 until n) {
            val type = types[k].toInt()
            when (type) {
                RLE,
                LRE,
                RLO,
                LRO -> {
                    // X2-X5.
                    explicitLevels[k] = stackLevel[top].toByte()
                    val isRtl = type == RLE || type == RLO
                    val level = stackLevel[top]
                    val newLevel = if (isRtl) (level + 1) or 1 else (level + 2) and 1.inv()
                    if (
                        newLevel <= BidiParagraph.MAX_DEPTH &&
                            overflowIsolateCount == 0 &&
                            overflowEmbeddingCount == 0
                    ) {
                        top++
                        stackLevel[top] = newLevel
                        stackOverride[top] =
                            when (type) {
                                RLO -> R
                                LRO -> L
                                else -> ON
                            }
                        stackIsolate[top] = false
                    } else if (overflowIsolateCount == 0) {
                        overflowEmbeddingCount++
                    }
                }
                RLI,
                LRI,
                FSI -> {
                    // X5a-X5c.
                    explicitLevels[k] = stackLevel[top].toByte()
                    if (stackOverride[top] != ON) types[k] = stackOverride[top].toByte()
                    val isRtl =
                        when (type) {
                            RLI -> true
                            LRI -> false
                            else -> {
                                val limit = if (matchingPdi[k] >= 0) matchingPdi[k] else n
                                firstStrongType(k + 1, limit) == R
                            }
                        }
                    val level = stackLevel[top]
                    val newLevel = if (isRtl) (level + 1) or 1 else (level + 2) and 1.inv()
                    if (
                        newLevel <= BidiParagraph.MAX_DEPTH &&
                            overflowIsolateCount == 0 &&
                            overflowEmbeddingCount == 0
                    ) {
                        validIsolateCount++
                        top++
                        stackLevel[top] = newLevel
                        stackOverride[top] = ON
                        stackIsolate[top] = true
                    } else {
                        overflowIsolateCount++
                    }
                }
                PDI -> {
                    // X6a.
                    if (overflowIsolateCount > 0) {
                        overflowIsolateCount--
                    } else if (validIsolateCount > 0) {
                        overflowEmbeddingCount = 0
                        while (!stackIsolate[top]) top--
                        top--
                        validIsolateCount--
                    }
                    explicitLevels[k] = stackLevel[top].toByte()
                    if (stackOverride[top] != ON) types[k] = stackOverride[top].toByte()
                }
                PDF -> {
                    // X7.
                    if (overflowIsolateCount > 0) {
                        // Nothing: within an overflow isolate.
                    } else if (overflowEmbeddingCount > 0) {
                        overflowEmbeddingCount--
                    } else if (!stackIsolate[top] && top >= 1) {
                        top--
                    }
                    explicitLevels[k] = stackLevel[top].toByte()
                }
                B -> {
                    // X8: a paragraph separator can only end the paragraph.
                    explicitLevels[k] = paragraphLevel.toByte()
                }
                BN -> {
                    // Removed by X9; keeps its type.
                    explicitLevels[k] = stackLevel[top].toByte()
                }
                else -> {
                    // X6.
                    explicitLevels[k] = stackLevel[top].toByte()
                    if (stackOverride[top] != ON) types[k] = stackOverride[top].toByte()
                }
            }
        }
    }

    /** X10 and the resolution of every isolating run sequence (W1-W7, N0-N2, I1-I2). */
    private fun resolveIsolatingRunSequences() {
        val n = size
        // Level runs (BD7), ignoring characters removed by X9.
        var runCount = 0
        var runLevel = -1
        for (k in 0 until n) {
            if (isRemovedByX9(initialTypes[k].toInt())) continue
            val level = explicitLevels[k].toInt()
            if (runCount == 0 || level != runLevel) {
                if (runCount == runFirst.size) {
                    runFirst = runFirst.copyOf(runCount * 2)
                    runLast = runLast.copyOf(runCount * 2)
                }
                runFirst[runCount] = k
                runCount++
                runLevel = level
            }
            runLast[runCount - 1] = k
            runForChar[k] = runCount - 1
        }

        // Isolating run sequences (BD13).
        for (run in 0 until runCount) {
            val first = runFirst[run]
            if (initialTypes[first].toInt() == PDI && matchingInitiator[first] >= 0) continue
            var length = 0
            var current = run
            while (true) {
                for (k in runFirst[current]..runLast[current]) {
                    if (!isRemovedByX9(initialTypes[k].toInt())) sequence[length++] = k
                }
                val last = runLast[current]
                val lastType = initialTypes[last].toInt()
                if (
                    (lastType == LRI || lastType == RLI || lastType == FSI) &&
                        matchingPdi[last] >= 0
                ) {
                    current = runForChar[matchingPdi[last]]
                } else {
                    break
                }
            }
            resolveSequence(length)
        }
    }

    /** Resolves the isolating run sequence `sequence[0, length)`. */
    private fun resolveSequence(length: Int) {
        val n = size
        val indexes = sequence
        val seqTypes = sequenceTypes
        for (i in 0 until length) seqTypes[i] = types[indexes[i]]

        // sos and eos (X10).
        val level = explicitLevels[indexes[0]].toInt()
        var previous = indexes[0] - 1
        while (previous >= 0 && isRemovedByX9(initialTypes[previous].toInt())) previous--
        val previousLevel = if (previous >= 0) explicitLevels[previous].toInt() else paragraphLevel
        val sos = typeForLevel(maxOf(previousLevel, level))
        val lastType = seqTypes[length - 1].toInt()
        val nextLevel =
            if (lastType == LRI || lastType == RLI || lastType == FSI) {
                paragraphLevel
            } else {
                var next = indexes[length - 1] + 1
                while (next < n && isRemovedByX9(initialTypes[next].toInt())) next++
                if (next < n) explicitLevels[next].toInt() else paragraphLevel
            }
        val eos = typeForLevel(maxOf(nextLevel, level))

        resolveWeakTypes(length, sos, eos)
        resolvePairedBrackets(length, sos, level)
        resolveNeutralTypes(length, sos, eos, level)

        // I1-I2.
        for (i in 0 until length) {
            val type = seqTypes[i].toInt()
            val resolved =
                if (level and 1 == 0) {
                    when (type) {
                        R -> level + 1
                        AN,
                        EN -> level + 2
                        else -> level
                    }
                } else {
                    when (type) {
                        L,
                        AN,
                        EN -> level + 1
                        else -> level
                    }
                }
            resultLevels[indexes[i]] = resolved.toByte()
        }
    }

    /** Rules W1-W7 on `sequenceTypes[0, length)`. */
    private fun resolveWeakTypes(length: Int, sos: Int, eos: Int) {
        val t = sequenceTypes

        // W1: NSM takes the type of the previous character (ON after isolate initiators and PDI).
        for (i in 0 until length) {
            if (t[i].toInt() == NSM) {
                t[i] =
                    if (i == 0) {
                        sos.toByte()
                    } else {
                        when (t[i - 1].toInt()) {
                            LRI,
                            RLI,
                            FSI,
                            PDI -> ON.toByte()
                            else -> t[i - 1]
                        }
                    }
            }
        }

        // W2: EN preceded (through weak types) by AL becomes AN. W3: AL becomes R.
        var lastStrong = sos
        for (i in 0 until length) {
            when (t[i].toInt()) {
                L,
                R -> lastStrong = t[i].toInt()
                AL -> {
                    lastStrong = AL
                    t[i] = R.toByte()
                }
                EN -> if (lastStrong == AL) t[i] = AN.toByte()
            }
        }

        // W4: a single ES between ENs becomes EN; a single CS between numbers of the same type
        // becomes that type.
        for (i in 1 until length - 1) {
            val type = t[i].toInt()
            if (type == ES || type == CS) {
                val before = t[i - 1].toInt()
                val after = t[i + 1].toInt()
                if (before == EN && after == EN) {
                    t[i] = EN.toByte()
                } else if (type == CS && before == AN && after == AN) {
                    t[i] = AN.toByte()
                }
            }
        }

        // W5: a sequence of ETs adjacent to an EN becomes EN.
        var i = 0
        while (i < length) {
            if (t[i].toInt() == ET) {
                val runStart = i
                var runLimit = i + 1
                while (runLimit < length && t[runLimit].toInt() == ET) runLimit++
                val before = if (runStart == 0) sos else t[runStart - 1].toInt()
                val after = if (runLimit == length) eos else t[runLimit].toInt()
                if (before == EN || after == EN) t.fill(EN.toByte(), runStart, runLimit)
                i = runLimit
            } else {
                i++
            }
        }

        // W6: remaining separators and terminators become ON.
        for (k in 0 until length) {
            when (t[k].toInt()) {
                ES,
                ET,
                CS -> t[k] = ON.toByte()
            }
        }

        // W7: EN preceded (through weak types) by L, or by sos L, becomes L.
        lastStrong = sos
        for (k in 0 until length) {
            when (t[k].toInt()) {
                L,
                R -> lastStrong = t[k].toInt()
                EN -> if (lastStrong == L) t[k] = L.toByte()
            }
        }
    }

    /** Rule N0 on `sequenceTypes[0, length)`. */
    private fun resolvePairedBrackets(length: Int, sos: Int, level: Int) {
        val t = sequenceTypes
        val indexes = sequence
        // BD16: identify the bracket pairs.
        var pairCount = 0
        var stackSize = 0
        var i = 0
        while (i < length) {
            if (t[i].toInt() == ON) {
                val info = bidiBracketInfo(codePoints[indexes[i]])
                if (info != 0) {
                    if (info and 3 == BIDI_BRACKET_OPEN) {
                        if (stackSize == MAX_BRACKET_STACK) {
                            // Stack overflow: stop processing BD16 and return an empty list.
                            pairCount = 0
                            break
                        }
                        bracketStackCodePoint[stackSize] = canonicalBracket(info ushr 2)
                        bracketStackPosition[stackSize] = i
                        stackSize++
                    } else {
                        val closing = canonicalBracket(codePoints[indexes[i]])
                        var s = stackSize - 1
                        while (s >= 0) {
                            if (bracketStackCodePoint[s] == closing) {
                                pairCount = addBracketPair(pairCount, bracketStackPosition[s], i)
                                stackSize = s
                                break
                            }
                            s--
                        }
                    }
                }
            }
            i++
        }
        if (pairCount == 0) return

        val embeddingDirection = typeForLevel(level)
        for (p in 0 until pairCount) {
            val opener = pairOpeners[p]
            val closer = pairClosers[p]
            // N0 b-d: find the strong types within the pair.
            var pairDirection = ON
            for (k in opener + 1 until closer) {
                val direction = strongTypeForN0(t[k].toInt())
                if (direction == ON) continue
                pairDirection = direction
                if (direction == embeddingDirection) break
            }
            if (pairDirection == ON) continue // N0 d: no strong type, leave the brackets alone.
            if (pairDirection != embeddingDirection) {
                // N0 c: only the opposite direction inside; check the context before the pair.
                var before = sos
                for (k in opener - 1 downTo 0) {
                    val direction = strongTypeForN0(t[k].toInt())
                    if (direction != ON) {
                        before = direction
                        break
                    }
                }
                if (before != pairDirection) pairDirection = embeddingDirection
            }
            val resolved = pairDirection.toByte()
            t[opener] = resolved
            t[closer] = resolved
            // Original NSMs following a bracket take its new type.
            for (k in opener + 1 until closer) {
                if (types[indexes[k]].toInt() != NSM) break
                t[k] = resolved
            }
            for (k in closer + 1 until length) {
                if (types[indexes[k]].toInt() != NSM) break
                t[k] = resolved
            }
        }
    }

    /** Inserts a pair, keeping the list sorted by the position of the opening bracket. */
    private fun addBracketPair(count: Int, opener: Int, closer: Int): Int {
        if (count == pairOpeners.size) {
            pairOpeners = pairOpeners.copyOf(count * 2)
            pairClosers = pairClosers.copyOf(count * 2)
        }
        var i = count
        while (i > 0 && pairOpeners[i - 1] > opener) {
            pairOpeners[i] = pairOpeners[i - 1]
            pairClosers[i] = pairClosers[i - 1]
            i--
        }
        pairOpeners[i] = opener
        pairClosers[i] = closer
        return count + 1
    }

    /** Rules N1-N2 on `sequenceTypes[0, length)`. */
    private fun resolveNeutralTypes(length: Int, sos: Int, eos: Int, level: Int) {
        val t = sequenceTypes
        var i = 0
        while (i < length) {
            if (isNeutralOrIsolate(t[i].toInt())) {
                val runStart = i
                var runLimit = i + 1
                while (runLimit < length && isNeutralOrIsolate(t[runLimit].toInt())) runLimit++
                val leading = if (runStart == 0) sos else strongTypeForN1(t[runStart - 1].toInt())
                val trailing = if (runLimit == length) eos else strongTypeForN1(t[runLimit].toInt())
                val resolved = if (leading == trailing) leading else typeForLevel(level)
                t.fill(resolved.toByte(), runStart, runLimit)
                i = runLimit
            } else {
                i++
            }
        }
    }

    /** Characters removed by X9 get the level of the preceding character. */
    private fun assignLevelsToCharactersRemovedByX9() {
        for (k in 0 until size) {
            if (isRemovedByX9(initialTypes[k].toInt())) {
                resultLevels[k] = if (k == 0) paragraphLevel.toByte() else resultLevels[k - 1]
            }
        }
    }

    companion object {
        private const val INITIAL_CAPACITY = 64
        /** Buffers larger than this (in code points) are released after use. */
        private const val MAX_RETAINED_CAPACITY = 16384
        private const val MAX_BRACKET_STACK = 63

        /** The shared instance; see the threading note of [BidiParagraph]. */
        val instance = BidiResolver()

        private fun typeForLevel(level: Int): Int = if (level and 1 == 0) L else R

        private fun isRemovedByX9(type: Int): Boolean =
            when (type) {
                LRE,
                RLE,
                LRO,
                RLO,
                PDF,
                BN -> true
                else -> false
            }

        private fun isNeutralOrIsolate(type: Int): Boolean =
            when (type) {
                B,
                S,
                WS,
                ON,
                LRI,
                RLI,
                FSI,
                PDI -> true
                else -> false
            }

        /** N0: EN and AN count as R. */
        private fun strongTypeForN0(type: Int): Int =
            when (type) {
                L -> L
                R,
                AL,
                EN,
                AN -> R
                else -> ON
            }

        /** N1: the type at the boundary of a neutral run (only L, R, EN, AN occur here). */
        private fun strongTypeForN1(type: Int): Int = if (type == L) L else R

        /** U+2329/U+232A are canonically equivalent to U+3008/U+3009 (BD16). */
        private fun canonicalBracket(codePoint: Int): Int =
            when (codePoint) {
                0x2329 -> 0x3008
                0x232A -> 0x3009
                else -> codePoint
            }
    }
}
