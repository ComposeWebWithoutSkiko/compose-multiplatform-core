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
 * Values of the Bidi_Class property, see
 * [UAX #9, Table 4](https://www.unicode.org/reports/tr9/#Bidirectional_Character_Types).
 *
 * The numbering matches the generated table in `UnicodeTables.kt`.
 */
internal object BidiClass {
    /** Left-to-right. */
    const val L = 0
    /** Right-to-left. */
    const val R = 1
    /** Right-to-left Arabic. */
    const val AL = 2
    /** European number. */
    const val EN = 3
    /** European number separator. */
    const val ES = 4
    /** European number terminator. */
    const val ET = 5
    /** Arabic number. */
    const val AN = 6
    /** Common number separator. */
    const val CS = 7
    /** Nonspacing mark. */
    const val NSM = 8
    /** Boundary neutral. */
    const val BN = 9
    /** Paragraph separator. */
    const val B = 10
    /** Segment separator. */
    const val S = 11
    /** Whitespace. */
    const val WS = 12
    /** Other neutrals. */
    const val ON = 13
    /** Left-to-right embedding. */
    const val LRE = 14
    /** Left-to-right override. */
    const val LRO = 15
    /** Right-to-left embedding. */
    const val RLE = 16
    /** Right-to-left override. */
    const val RLO = 17
    /** Pop directional format. */
    const val PDF = 18
    /** Left-to-right isolate. */
    const val LRI = 19
    /** Right-to-left isolate. */
    const val RLI = 20
    /** First strong isolate. */
    const val FSI = 21
    /** Pop directional isolate. */
    const val PDI = 22
}

/** No strong directional character, see [strongDirection]. */
internal const val STRONG_DIRECTION_NONE: Int = 0

/** Strong left-to-right (Bidi_Class L), see [strongDirection]. */
internal const val STRONG_DIRECTION_LTR: Int = 1

/** Strong right-to-left (Bidi_Class R or AL), see [strongDirection]. */
internal const val STRONG_DIRECTION_RTL: Int = 2

/** Bidi_Paired_Bracket_Type None, see [bidiPairedBracketType]. */
internal const val BIDI_BRACKET_NONE: Int = 0

/** Bidi_Paired_Bracket_Type Open, see [bidiPairedBracketType]. */
internal const val BIDI_BRACKET_OPEN: Int = 1

/** Bidi_Paired_Bracket_Type Close, see [bidiPairedBracketType]. */
internal const val BIDI_BRACKET_CLOSE: Int = 2

/** Lazily decoded bidi tables. */
internal object BidiData {
    val classes = UnicodePropertyTable(BIDI_CLASS_DATA)
}

private object BidiBracketData {
    /** Bracket code points mapped to `paired shl 1 or (0 for open, 1 for close)`. */
    val brackets: SparseCodePointMap

    init {
        val reader = EncodedDataReader(BIDI_BRACKETS_DATA)
        val count = reader.next()
        val keys = IntArray(count)
        val values = IntArray(count)
        var codePoint = 0
        for (i in 0 until count) {
            codePoint += reader.next()
            val encoded = reader.next()
            val paired = codePoint + ((encoded ushr 2) xor -((encoded ushr 1) and 1))
            keys[i] = codePoint
            values[i] = (paired shl 1) or (encoded and 1)
        }
        brackets = SparseCodePointMap(keys, values)
    }
}

/** Returns the Bidi_Class of [codePoint], one of the [BidiClass] constants. */
internal fun bidiClass(codePoint: Int): Int = BidiData.classes[codePoint]

/**
 * Returns the strong direction of [codePoint]: [STRONG_DIRECTION_LTR] for Bidi_Class L,
 * [STRONG_DIRECTION_RTL] for R and AL, and [STRONG_DIRECTION_NONE] otherwise.
 *
 * Matches `CodePoint.strongDirectionType()` of the Skiko text backend.
 */
internal fun strongDirection(codePoint: Int): Int =
    when (bidiClass(codePoint)) {
        BidiClass.L -> STRONG_DIRECTION_LTR
        BidiClass.R,
        BidiClass.AL -> STRONG_DIRECTION_RTL
        else -> STRONG_DIRECTION_NONE
    }

/**
 * Returns the direction of the first strong character (see [strongDirection]) of `text[start,
 * end)`, ignoring the characters between an isolate initiator (LRI, RLI, FSI) and its matching PDI
 * (or the end of the text), or [STRONG_DIRECTION_NONE] if there is none.
 *
 * Matches `CharSequence.firstStrongDirectionType()` of the Skiko text backend (rule P2 of UAX #9
 * applied to the whole text, without splitting it into paragraphs).
 */
internal fun firstStrongDirection(text: CharSequence, start: Int = 0, end: Int = text.length): Int {
    val classes = BidiData.classes
    var openIsolates = 0
    var i = start
    while (i < end) {
        val codePoint = codePointAt(text, i)
        i += charCount(codePoint)
        when (classes[codePoint]) {
            BidiClass.LRI,
            BidiClass.RLI,
            BidiClass.FSI -> openIsolates++
            BidiClass.PDI -> if (openIsolates > 0) openIsolates--
            BidiClass.L -> if (openIsolates == 0) return STRONG_DIRECTION_LTR
            BidiClass.R,
            BidiClass.AL -> if (openIsolates == 0) return STRONG_DIRECTION_RTL
        }
    }
    return STRONG_DIRECTION_NONE
}

/**
 * Returns true if [codePoint] has a neutral Bidi_Class: ON (other neutral), WS (whitespace) or BN
 * (boundary neutral).
 *
 * Matches `CodePoint.isNeutralDirection()` of the Skiko text backend, which checks ICU's
 * OTHER_NEUTRAL, WHITE_SPACE_NEUTRAL and BOUNDARY_NEUTRAL (= BN, not the paragraph separator B).
 */
internal fun isNeutralDirection(codePoint: Int): Boolean =
    when (bidiClass(codePoint)) {
        BidiClass.ON,
        BidiClass.WS,
        BidiClass.BN -> true
        else -> false
    }

/**
 * Returns the Bidi_Paired_Bracket_Type of [codePoint]: [BIDI_BRACKET_OPEN], [BIDI_BRACKET_CLOSE] or
 * [BIDI_BRACKET_NONE].
 */
internal fun bidiPairedBracketType(codePoint: Int): Int {
    val map = BidiBracketData.brackets
    val index = map.indexOf(codePoint)
    if (index < 0) return BIDI_BRACKET_NONE
    return if (map.values[index] and 1 == 0) BIDI_BRACKET_OPEN else BIDI_BRACKET_CLOSE
}

/**
 * Returns the Bidi_Paired_Bracket of [codePoint] (the matching closing bracket for an opening
 * bracket and vice versa), or [codePoint] itself if it is not a paired bracket.
 */
internal fun bidiPairedBracket(codePoint: Int): Int {
    val map = BidiBracketData.brackets
    val index = map.indexOf(codePoint)
    return if (index < 0) codePoint else map.values[index] shr 1
}

/**
 * Returns a bracket packed as `(paired bracket shl 2) or type` (type is one of the BIDI_BRACKET_*
 * constants), or 0 if [codePoint] is not a paired bracket. Used by rule N0.
 */
internal fun bidiBracketInfo(codePoint: Int): Int {
    val map = BidiBracketData.brackets
    val index = map.indexOf(codePoint)
    if (index < 0) return 0
    val value = map.values[index]
    val type = if (value and 1 == 0) BIDI_BRACKET_OPEN else BIDI_BRACKET_CLOSE
    return ((value shr 1) shl 2) or type
}
