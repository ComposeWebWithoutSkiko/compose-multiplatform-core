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

import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.AI
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.AK
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.AL
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.AP
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.AS
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.B2
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.BA
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.BB
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.BK
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.CB
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.CJ
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.CL
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.CM
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.CP
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.CR
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.EB
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.EM
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.EX
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.GL
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.H2
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.H3
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.HH
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.HL
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.HY
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.ID
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.IN
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.IS
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.JL
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.JT
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.JV
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.LF
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.NL
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.NS
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.NU
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.OP
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.PO
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.PR
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.QU
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.RI
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.SA
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.SG
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.SP
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.SY
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.VF
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.VI
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.WJ
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.XX
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.ZW
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass.ZWJ

/**
 * Values of the Line_Break property, see
 * [UAX #14, Table 1](https://www.unicode.org/reports/tr14/#Table1).
 *
 * The numbering matches the generated table in `UnicodeTables.kt`.
 */
internal object LineBreakClass {
    /** Mandatory break (e.g. U+000B, U+000C, U+2028, U+2029). */
    const val BK = 0
    /** Carriage return. */
    const val CR = 1
    /** Line feed. */
    const val LF = 2
    /** Next line (U+0085). */
    const val NL = 3
    /** Space (U+0020). */
    const val SP = 4
    /** Zero width space (U+200B). */
    const val ZW = 5
    /** Zero width joiner (U+200D). */
    const val ZWJ = 6
    /** Combining mark. */
    const val CM = 7
    /** Word joiner (e.g. U+2060, U+FEFF). */
    const val WJ = 8
    /** Non-breaking ("glue", e.g. U+00A0). */
    const val GL = 9
    /** Ambiguous (alphabetic or ideographic); resolves to AL. */
    const val AI = 10
    /** Aksara. */
    const val AK = 11
    /** Alphabetic. */
    const val AL = 12
    /** Aksara prebase. */
    const val AP = 13
    /** Aksara start. */
    const val AS = 14
    /** Break opportunity before and after (em dash). */
    const val B2 = 15
    /** Break after. */
    const val BA = 16
    /** Break before. */
    const val BB = 17
    /** Contingent break opportunity. */
    const val CB = 18
    /** Conditional Japanese starter; resolves to NS. */
    const val CJ = 19
    /** Close punctuation. */
    const val CL = 20
    /** Close parenthesis. */
    const val CP = 21
    /** Emoji base. */
    const val EB = 22
    /** Emoji modifier. */
    const val EM = 23
    /** Exclamation/interrogation. */
    const val EX = 24
    /** Hangul LV syllable. */
    const val H2 = 25
    /** Hangul LVT syllable. */
    const val H3 = 26
    /** Unambiguous hyphen. */
    const val HH = 27
    /** Hebrew letter. */
    const val HL = 28
    /** Hyphen-minus. */
    const val HY = 29
    /** Ideographic. */
    const val ID = 30
    /** Inseparable (leaders). */
    const val IN = 31
    /** Infix numeric separator. */
    const val IS = 32
    /** Hangul L jamo. */
    const val JL = 33
    /** Hangul T jamo. */
    const val JT = 34
    /** Hangul V jamo. */
    const val JV = 35
    /** Nonstarter. */
    const val NS = 36
    /** Numeric. */
    const val NU = 37
    /** Open punctuation. */
    const val OP = 38
    /** Postfix numeric. */
    const val PO = 39
    /** Prefix numeric. */
    const val PR = 40
    /** Quotation. */
    const val QU = 41
    /** Regional indicator. */
    const val RI = 42
    /** Complex context dependent (South East Asian); resolves to AL or CM without a dictionary. */
    const val SA = 43
    /** Surrogate; resolves to AL. */
    const val SG = 44
    /** Symbols allowing break after (`/`). */
    const val SY = 45
    /** Virama final. */
    const val VF = 46
    /** Virama. */
    const val VI = 47
    /** Unknown; resolves to AL. */
    const val XX = 48
}

/**
 * Internal pseudo classes of the generated line break table (see generate_unicode_tables.py): they
 * fold the General_Category distinctions needed by rules LB1, LB15a/b and LB19 into the Line_Break
 * table.
 */
private const val SA_MARK = 49 // SA with General_Category Mn or Mc (resolves to CM per LB1)
private const val QU_PI = 50 // QU with General_Category Pi
private const val QU_PF = 51 // QU with General_Category Pf
private const val INTERNAL_CLASS_COUNT = 52

private const val DOTTED_CIRCLE = 0x25CC

/** Decoded line break tables. */
private object LineBreakData {
    val table = UnicodePropertyTable(LINE_BREAK_DATA)
    val eastAsian = UnicodePropertyTable(EAST_ASIAN_DATA)

    /** Internal table value -> raw Line_Break class. */
    val rawClass =
        ByteArray(INTERNAL_CLASS_COUNT) {
            when (it) {
                SA_MARK -> SA
                QU_PI,
                QU_PF -> QU
                else -> it
            }.toByte()
        }

    /**
     * Internal table value -> class resolved per rule LB1 (without dictionary or language-specific
     * tailoring): AI, SG, XX and SA become AL (SA with General_Category Mn or Mc becomes CM) and CJ
     * becomes NS.
     */
    val resolvedClass =
        ByteArray(INTERNAL_CLASS_COUNT) {
            when (it) {
                AI,
                SG,
                XX,
                SA -> AL
                SA_MARK -> CM
                CJ -> NS
                QU_PI,
                QU_PF -> QU
                else -> it
            }.toByte()
        }
}

/** Returns the Line_Break property of [codePoint], one of the [LineBreakClass] constants. */
internal fun lineBreakClass(codePoint: Int): Int =
    LineBreakData.rawClass[LineBreakData.table[codePoint]].toInt()

/**
 * Returns true if [codePoint] belongs to the `$EastAsian` set of UAX #14, i.e. its East_Asian_Width
 * is Fullwidth, Wide or Halfwidth.
 */
internal fun isEastAsianWidth(codePoint: Int): Boolean = LineBreakData.eastAsian[codePoint] != 0

/**
 * Returns true for whitespace that hangs past the end of a line, which the line's visible end and
 * width leave out: SkParagraph's whitespace breaks. These are ICU's `u_isWhitespace` characters
 * (General_Category Zs, Zl or Zp except the no-break spaces U+00A0, U+2007 and U+202F, plus the
 * controls U+0009..U+000D and U+001C..U+001F), except U+001C..U+001F, which SkParagraph's check
 * of single ASCII characters doesn't count.
 */
internal fun isLineBreakWhitespace(codePoint: Int): Boolean =
    when (codePoint) {
        0x20,
        in 0x09..0x0D,
        0x1680,
        in 0x2000..0x2006,
        in 0x2008..0x200A,
        0x2028,
        0x2029,
        0x205F,
        0x3000 -> true
        else -> false
    }

/** A break at [position], encoded as [lineBreaks] returns them. */
internal fun encodeLineBreak(position: Int, isMandatory: Boolean): Int =
    (position shl 1) or (if (isMandatory) 1 else 0)

/** The position of a break returned by [lineBreaks]. */
internal fun lineBreakPosition(encodedBreak: Int): Int = encodedBreak ushr 1

/**
 * Whether a break returned by [lineBreaks] is mandatory (after a line terminator, see
 * [isLineTerminator], or at the end of the text) rather than an opportunity.
 */
internal fun isMandatoryLineBreak(encodedBreak: Int): Boolean = encodedBreak and 1 != 0

/**
 * Whether [c] is a line terminator: a character after which a line must end (classes BK, CR, LF
 * and NL, rules LB4 and LB5; a CR followed by an LF ends the line after the LF), and which the
 * line doesn't show.
 */
internal fun isLineTerminator(c: Char): Boolean =
    when (lineBreakClass(c.code)) {
        BK,
        CR,
        LF,
        NL -> true
        else -> false
    }

/**
 * Computes the line break opportunities of `text[start, end)` according to the default rules of
 * [UAX #14](https://www.unicode.org/reports/tr14/) (LB1-LB31, without tailoring; SA characters are
 * treated per LB1, i.e. without a dictionary).
 *
 * Returns the breaks in increasing order, encoded with [encodeLineBreak] (which keeps the order
 * of the positions; see [lineBreakPosition] and [isMandatoryLineBreak]); a break at `position`
 * means that a new line may (or, if mandatory, must) start at `text[position]`. Positions are
 * absolute indices into [text] in `(start, end]`: there is never a break at [start] (LB2) and the
 * last element is always a mandatory break at [end] (LB3), also for an empty range.
 *
 * Breaks never split a surrogate pair or a combining character sequence (LB9).
 */
internal fun lineBreaks(text: CharSequence, start: Int = 0, end: Int = text.length): IntArray {
    require(start in 0..end && end <= text.length) {
        "Invalid range [$start, $end) for text of length ${text.length}"
    }
    return LineBreaker.compute(text, start, end)
}

/** The implementation of [lineBreaks]. Reuses its buffers between calls (single-threaded web). */
private object LineBreaker {
    private const val NO_BREAK = 0
    private const val ALLOWED = 1
    private const val MANDATORY = 2

    /** Buffers larger than this (in code units) are released after use. */
    private const val MAX_RETAINED_CAPACITY = 16384

    // State of rule LB25 for the text ending at the current position.
    private const val NUMERIC_NONE = 0
    private const val NUMERIC_NU = 1 // matches NU (SY | IS)*
    private const val NUMERIC_NU_CLOSE = 2 // matches NU (SY | IS)* (CL | CP)

    /*
     * The text is first split into "units": a base character followed by the combining marks
     * and ZWJs attached to it by LB9 (or a lone CM/ZWJ treated as AL by LB10). Rules LB11 and
     * later only look at the classes of units; no break is possible inside a unit.
     */
    private var unitStart = IntArray(64)
    private var unitClass = ByteArray(64)

    /** The base code point of each unit (U+0041 for units created by LB10). */
    private var unitCodePoint = IntArray(64)

    /** The breaks found so far; at most one per unit plus the end of the text. */
    private var breaks = IntArray(64)

    fun compute(text: CharSequence, start: Int, end: Int): IntArray {
        val n = buildUnits(text, start, end)
        if (breaks.size < n + 1) breaks = IntArray(maxOf(n + 1, breaks.size * 2))
        val result = breaks
        var count = 0
        if (n > 0) {
            val classes = unitClass
            var lastNonSpace = if (classes[0].toInt() == SP) -1 else 0
            var numeric = nextNumericState(NUMERIC_NONE, classes[0].toInt())
            var regionalIndicators = if (classes[0].toInt() == RI) 1 else 0
            for (k in 1 until n) {
                val decision = decide(text, k, n, lastNonSpace, numeric, regionalIndicators)
                if (decision != NO_BREAK) {
                    result[count++] = encodeLineBreak(unitStart[k], isMandatory = decision == MANDATORY)
                }
                val c = classes[k].toInt()
                if (c != SP) lastNonSpace = k
                numeric = nextNumericState(numeric, c)
                regionalIndicators = if (c == RI) regionalIndicators + 1 else 0
            }
        }
        result[count++] = encodeLineBreak(end, isMandatory = true)
        val output = result.copyOf(count)
        if (unitStart.size > MAX_RETAINED_CAPACITY) {
            // Do not keep the buffers of a very long text.
            unitStart = IntArray(64)
            unitClass = ByteArray(64)
            unitCodePoint = IntArray(64)
            breaks = IntArray(64)
        }
        return output
    }

    /** Splits `text[start, end)` into units (LB1, LB9, LB10); returns the number of units. */
    private fun buildUnits(text: CharSequence, start: Int, end: Int): Int {
        val table = LineBreakData.table
        val resolved = LineBreakData.resolvedClass
        if (unitStart.size < end - start) {
            val capacity = maxOf(end - start, unitStart.size * 2)
            unitStart = IntArray(capacity)
            unitClass = ByteArray(capacity)
            unitCodePoint = IntArray(capacity)
        }
        var n = 0
        var i = start
        while (i < end) {
            var codePoint = text[i].code
            var length = 1
            if (codePoint in 0xD800..0xDBFF && i + 1 < end) {
                val low = text[i + 1].code
                if (low in 0xDC00..0xDFFF) {
                    codePoint = ((codePoint - 0xD800) shl 10) + (low - 0xDC00) + 0x10000
                    length = 2
                }
            }
            var c = resolved[table[codePoint]].toInt()
            if (c == CM || c == ZWJ) {
                if (n > 0) {
                    when (unitClass[n - 1].toInt()) {
                        BK,
                        CR,
                        LF,
                        NL,
                        SP,
                        ZW -> {}
                        else -> {
                            // LB9: X (CM | ZWJ)* is treated as X.
                            i += length
                            continue
                        }
                    }
                }
                // LB10: a remaining CM or ZWJ is treated as U+0041 (AL).
                c = AL
                codePoint = 0x41
            }
            unitStart[n] = i
            unitClass[n] = c.toByte()
            unitCodePoint[n] = codePoint
            n++
            i += length
        }
        return n
    }

    private fun nextNumericState(state: Int, c: Int): Int =
        when (c) {
            NU -> NUMERIC_NU
            SY,
            IS -> if (state == NUMERIC_NU) NUMERIC_NU else NUMERIC_NONE
            CL,
            CP -> if (state == NUMERIC_NU) NUMERIC_NU_CLOSE else NUMERIC_NONE
            else -> NUMERIC_NONE
        }

    /**
     * Decides whether there is a break between unit `k - 1` and unit `k`.
     *
     * @param lastNonSpace index of the last unit before `k` that is not SP, or -1 (sot).
     * @param numeric the LB25 state of the units before `k`.
     * @param regionalIndicators the number of consecutive RI units ending with unit `k - 1`.
     */
    private fun decide(
        text: CharSequence,
        k: Int,
        n: Int,
        lastNonSpace: Int,
        numeric: Int,
        regionalIndicators: Int,
    ): Int {
        val classes = unitClass
        val a = classes[k - 1].toInt()
        val b = classes[k].toInt()

        // LB4, LB5: always break after hard line breaks, but not inside CR LF.
        if (a == BK) return MANDATORY
        if (a == CR) return if (b == LF) NO_BREAK else MANDATORY
        if (a == LF || a == NL) return MANDATORY
        // LB6: do not break before hard line breaks.
        if (b == BK || b == CR || b == LF || b == NL) return NO_BREAK
        // LB7: do not break before spaces or zero width space.
        if (b == SP || b == ZW) return NO_BREAK
        // The class before any spaces preceding `b`; -1 for sot.
        val beforeSpaces = if (lastNonSpace >= 0) classes[lastNonSpace].toInt() else -1
        // LB8: ZW SP* ÷
        if (beforeSpaces == ZW) return ALLOWED
        // LB8a: ZWJ ×
        if (text[unitStart[k] - 1] == '\u200D') return NO_BREAK
        // LB11: × WJ, WJ ×
        if (a == WJ || b == WJ) return NO_BREAK
        // LB12: GL ×
        if (a == GL) return NO_BREAK
        // LB12a: [^SP HY HH] × GL
        if (b == GL && a != SP && a != HY && a != HH) return NO_BREAK
        // LB13: × CL, × CP, × EX, × SY
        if (b == CL || b == CP || b == EX || b == SY) return NO_BREAK
        // LB14: OP SP* ×
        if (beforeSpaces == OP) return NO_BREAK
        // LB15a: (sot | BK | CR | LF | NL | OP | QU | GL | SP | ZW) [\p{Pi}&QU] SP* ×
        if (beforeSpaces == QU && isInitialQuote(lastNonSpace)) {
            if (lastNonSpace == 0) return NO_BREAK
            when (classes[lastNonSpace - 1].toInt()) {
                BK,
                CR,
                LF,
                NL,
                OP,
                QU,
                GL,
                SP,
                ZW -> return NO_BREAK
            }
        }
        // LB15b: × [\p{Pf}&QU] (SP | GL | WJ | CL | QU | CP | EX | IS | SY | BK | CR | LF | NL |
        // ZW | eot)
        if (b == QU && isFinalQuote(k)) {
            if (k + 1 == n) return NO_BREAK
            when (classes[k + 1].toInt()) {
                SP,
                GL,
                WJ,
                CL,
                QU,
                CP,
                EX,
                IS,
                SY,
                BK,
                CR,
                LF,
                NL,
                ZW -> return NO_BREAK
            }
        }
        // LB15c: SP ÷ IS NU
        if (a == SP && b == IS && k + 1 < n && classes[k + 1].toInt() == NU) return ALLOWED
        // LB15d: × IS
        if (b == IS) return NO_BREAK
        // LB16: (CL | CP) SP* × NS
        if (b == NS && (beforeSpaces == CL || beforeSpaces == CP)) return NO_BREAK
        // LB17: B2 SP* × B2
        if (b == B2 && beforeSpaces == B2) return NO_BREAK
        // LB18: SP ÷
        if (a == SP) return ALLOWED
        // LB19: × [QU - \p{Pi}], [QU - \p{Pf}] ×
        if (b == QU && !isInitialQuote(k)) return NO_BREAK
        if (a == QU && !isFinalQuote(k - 1)) return NO_BREAK
        // LB19a: [^$EastAsian] × QU, × QU ([^$EastAsian] | eot), QU × [^$EastAsian],
        // (sot | [^$EastAsian]) QU ×
        if (b == QU) {
            if (!isEastAsian(k - 1)) return NO_BREAK
            if (k + 1 == n || !isEastAsian(k + 1)) return NO_BREAK
        }
        if (a == QU) {
            if (!isEastAsian(k)) return NO_BREAK
            if (k < 2 || !isEastAsian(k - 2)) return NO_BREAK
        }
        // LB20: ÷ CB, CB ÷
        if (a == CB || b == CB) return ALLOWED
        // LB20a: (sot | BK | CR | LF | NL | SP | ZW | CB | GL) (HY | HH) × (AL | HL)
        if ((a == HY || a == HH) && (b == AL || b == HL)) {
            if (k < 2) return NO_BREAK
            when (classes[k - 2].toInt()) {
                BK,
                CR,
                LF,
                NL,
                SP,
                ZW,
                CB,
                GL -> return NO_BREAK
            }
        }
        // LB21: × BA, × HH, × HY, × NS, BB ×
        if (b == BA || b == HH || b == HY || b == NS || a == BB) return NO_BREAK
        // LB21a: HL (HY | HH) × [^HL]
        if ((a == HY || a == HH) && b != HL && k >= 2 && classes[k - 2].toInt() == HL) {
            return NO_BREAK
        }
        // LB21b: SY × HL
        if (a == SY && b == HL) return NO_BREAK
        // LB22: × IN
        if (b == IN) return NO_BREAK
        // LB23: (AL | HL) × NU, NU × (AL | HL)
        if ((a == AL || a == HL) && b == NU) return NO_BREAK
        if (a == NU && (b == AL || b == HL)) return NO_BREAK
        // LB23a: PR × (ID | EB | EM), (ID | EB | EM) × PO
        if (a == PR && (b == ID || b == EB || b == EM)) return NO_BREAK
        if ((a == ID || a == EB || a == EM) && b == PO) return NO_BREAK
        // LB24: (PR | PO) × (AL | HL), (AL | HL) × (PR | PO)
        if ((a == PR || a == PO) && (b == AL || b == HL)) return NO_BREAK
        if ((a == AL || a == HL) && (b == PR || b == PO)) return NO_BREAK
        // LB25: NU (SY | IS)* (CL | CP)? × (PO | PR), (PO | PR) × OP IS? NU,
        // (PO | PR | HY | IS) × NU, NU (SY | IS)* × NU
        if ((b == PO || b == PR) && numeric != NUMERIC_NONE) return NO_BREAK
        if ((a == PO || a == PR) && b == OP && k + 1 < n) {
            val next = classes[k + 1].toInt()
            if (next == NU) return NO_BREAK
            if (next == IS && k + 2 < n && classes[k + 2].toInt() == NU) return NO_BREAK
        }
        if ((a == PO || a == PR || a == HY || a == IS) && b == NU) return NO_BREAK
        if (b == NU && numeric == NUMERIC_NU) return NO_BREAK
        // LB26: JL × (JL | JV | H2 | H3), (JV | H2) × (JV | JT), (JT | H3) × JT
        if (a == JL && (b == JL || b == JV || b == H2 || b == H3)) return NO_BREAK
        if ((a == JV || a == H2) && (b == JV || b == JT)) return NO_BREAK
        if ((a == JT || a == H3) && b == JT) return NO_BREAK
        // LB27: (JL | JV | JT | H2 | H3) × PO, PR × (JL | JV | JT | H2 | H3)
        if (isKorean(a) && b == PO) return NO_BREAK
        if (a == PR && isKorean(b)) return NO_BREAK
        // LB28: (AL | HL) × (AL | HL)
        if ((a == AL || a == HL) && (b == AL || b == HL)) return NO_BREAK
        // LB28a: AP × (AK | ◌ | AS), (AK | ◌ | AS) × (VF | VI),
        // (AK | ◌ | AS) VI × (AK | ◌), (AK | ◌ | AS) × (AK | ◌ | AS) VF
        val aIsAksara = isAksara(k - 1)
        val bIsAksara = isAksara(k)
        if (a == AP && bIsAksara) return NO_BREAK
        if (aIsAksara && (b == VF || b == VI)) return NO_BREAK
        if (
            a == VI && (b == AK || unitCodePoint[k] == DOTTED_CIRCLE) && k >= 2 && isAksara(k - 2)
        ) {
            return NO_BREAK
        }
        if (aIsAksara && bIsAksara && k + 1 < n && classes[k + 1].toInt() == VF) return NO_BREAK
        // LB29: IS × (AL | HL)
        if (a == IS && (b == AL || b == HL)) return NO_BREAK
        // LB30: (AL | HL | NU) × [OP - $EastAsian], [CP - $EastAsian] × (AL | HL | NU)
        if ((a == AL || a == HL || a == NU) && b == OP && !isEastAsian(k)) return NO_BREAK
        if (a == CP && (b == AL || b == HL || b == NU) && !isEastAsian(k - 1)) return NO_BREAK
        // LB30a: break between regional indicators only after an even number of them.
        if (a == RI && b == RI && regionalIndicators and 1 == 1) return NO_BREAK
        // LB30b: EB × EM, [\p{Extended_Pictographic}&\p{Cn}] × EM
        if (b == EM) {
            if (a == EB) return NO_BREAK
            val previous = unitCodePoint[k - 1]
            if (isUnassignedExtendedPictographic(previous)) return NO_BREAK
        }
        // LB31: break everywhere else.
        return ALLOWED
    }

    private fun isInitialQuote(unit: Int): Boolean =
        LineBreakData.table[unitCodePoint[unit]] == QU_PI

    private fun isFinalQuote(unit: Int): Boolean = LineBreakData.table[unitCodePoint[unit]] == QU_PF

    private fun isEastAsian(unit: Int): Boolean = isEastAsianWidth(unitCodePoint[unit])

    private fun isAksara(unit: Int): Boolean {
        val c = unitClass[unit].toInt()
        return c == AK || c == AS || unitCodePoint[unit] == DOTTED_CIRCLE
    }

    private fun isKorean(c: Int): Boolean = c == JL || c == JV || c == JT || c == H2 || c == H3
}
