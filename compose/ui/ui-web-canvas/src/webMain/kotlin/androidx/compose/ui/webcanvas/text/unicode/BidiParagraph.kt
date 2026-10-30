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
 * [text] laid out at [paragraphLevel] (0 for left-to-right, 1 for right-to-left: Compose resolves
 * the direction of a paragraph itself). Resolves the embedding level of every UTF-16 code unit
 * (rules X1-X10, W1-W7, N0-N2, I1-I2, and the part of rule L1 that doesn't depend on where lines
 * end); the rest of rule L1 is [trailingWhitespaceStart], and rule L2 is [reorderVisually].
 *
 * Following rule P1, the text is split after every paragraph separator (Bidi_Class B, e.g. `\n`,
 * `\r`, U+2029), and every paragraph is resolved independently.
 *
 * Both code units of a surrogate pair always get the same level. Characters removed by rule X9
 * (Bidi_Class BN, e.g. ZWJ, and the explicit embedding controls LRE, RLE, LRO, RLO, PDF) get the
 * level of the preceding character (or the paragraph level at the start of a paragraph), so they
 * never split a run, as recommended by section 5.2 of UAX #9.
 *
 * Instances are immutable. The constructor is not thread-safe (it reuses internal buffers), which
 * is fine on the single-threaded web targets.
 */
internal class BidiParagraph(text: CharSequence, paragraphLevel: Int) {
    /**
     * The resolved embedding levels, one per UTF-16 code unit (`levels[i]` is the level of
     * `text[i]`), with the part of rule L1 that doesn't depend on where lines end: segment and
     * paragraph separators, and the whitespace, isolate formatting characters and characters
     * removed by X9 before them, have the paragraph level. Do not modify.
     */
    val levels: ByteArray = ByteArray(text.length)

    init {
        require(paragraphLevel == 0 || paragraphLevel == 1) {
            "Invalid paragraph level $paragraphLevel"
        }
        val resolver = BidiResolver.instance
        var start = 0
        while (start < text.length) {
            // Rule P1: a paragraph ends after its separator. (The LF of a CR LF then makes a
            // paragraph of its own, at the paragraph level like the end of the one before.)
            var end = start
            while (end < text.length) {
                if (isParagraphSeparator(text[end++])) break
            }
            resolver.resolveParagraph(text, start, end, paragraphLevel, levels)
            start = end
        }
        resolver.trimBuffers()
    }

    companion object {
        /** The maximum explicit embedding level (max_depth of UAX #9). */
        const val MAX_DEPTH: Int = 125

        /**
         * Applies rule L2 to the [levels] of the elements of a line: returns its runs (maximal
         * sequences of elements of the same level) in visual order, left to right, as three
         * consecutive values `start, end, level` (indexes into [levels], end exclusive). A run with
         * an odd level is right-to-left: its elements are displayed in reverse order.
         */
        fun visualRunsOf(levels: ByteArray): IntArray {
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
                result[v * 3] = runStarts[run]
                result[v * 3 + 1] = runStarts[run + 1]
                result[v * 3 + 2] = levels[runStarts[run]].toInt()
            }
            return result
        }

        /**
         * Applies rule L2 to [levels]: returns, for each visual position (left to right), the index
         * into [levels] of the element displayed there, or null if that's the logical order (no
         * level is odd). Elements are reversed individually (no surrogate or cluster handling).
         */
        fun reorderVisually(levels: ByteArray): IntArray? {
            if (levels.all { it.toInt() and 1 == 0 }) return null
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
 * The start of the sequence of separators and whitespace (see [isWhitespaceForL1]) that ends the
 * line `text[lineStart, lineEnd)`, which rule L1 gives the paragraph level: [BidiParagraph.levels]
 * has it for the separators and the whitespace before them, but not for the whitespace that ends a
 * line, since it depends on where lines end.
 */
internal fun trailingWhitespaceStart(text: CharSequence, lineStart: Int, lineEnd: Int): Int {
    var i = lineEnd
    while (i > lineStart) {
        val codePoint = codePointBefore(text, i)
        val type = bidiClass(codePoint)
        if (type != S && type != B && !isWhitespaceForL1(type)) break
        i -= charCount(codePoint)
    }
    return i
}

/**
 * Whether rule L1 treats a character of Bidi_Class [type] as whitespace, which takes the paragraph
 * level before a separator or at the end of a line: whitespace, isolate formatting characters, and
 * the characters removed by X9 (as section 5.2 of UAX #9 recommends).
 */
private fun isWhitespaceForL1(type: Int): Boolean =
    when (type) {
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
        PDF -> true
        else -> false
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

    /**
     * Resolves the levels of the paragraph `text[start, end)` laid out at [paragraphLevel] and
     * writes them to `levels[start, end)`, which holds zeros before.
     */
    fun resolveParagraph(
        text: CharSequence,
        start: Int,
        end: Int,
        paragraphLevel: Int,
        levels: ByteArray,
    ) {
        val classes = BidiData.classes
        // Decode the code points.
        ensureCapacity(end - start)
        var n = 0
        var needsFullAlgorithm = false
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
        if (!needsFullAlgorithm && paragraphLevel == 0) return

        this.paragraphLevel = paragraphLevel
        determineMatchingIsolates()
        determineExplicitEmbeddingLevels()
        resolveIsolatingRunSequences()
        assignLevelsToCharactersRemovedByX9()
        resetSeparatorsToParagraphLevel()
        // Expanded to code units.
        for (k in 0 until size) {
            levels[offsets[k]] = resultLevels[k]
            if (codePoints[k] >= MIN_SUPPLEMENTARY_CODE_POINT) levels[offsets[k] + 1] = resultLevels[k]
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

    /**
     * Rule L1 but at the end of lines (see [trailingWhitespaceStart]): segment and
     * paragraph separators, and the whitespace before them (see [isWhitespaceForL1]), take the
     * paragraph level.
     */
    private fun resetSeparatorsToParagraphLevel() {
        val level = paragraphLevel.toByte()
        var resetting = false
        for (k in size - 1 downTo 0) {
            val type = initialTypes[k].toInt()
            if (type == S || type == B) {
                resultLevels[k] = level
                resetting = true
            } else if (resetting && isWhitespaceForL1(type)) {
                resultLevels[k] = level
            } else {
                resetting = false
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
