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

@file:OptIn(ExperimentalWasmJsInterop::class)

package androidx.compose.ui.webcanvas.text.unicode

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

// Extended grapheme clusters (UAX #29), as the browser's `Intl.Segmenter` finds them: the browser
// draws the text, so its clusters are the ones that show as single characters. Without
// `Intl.Segmenter` they are approximated: a character with the marks, joiners, variation selectors
// and emoji modifiers that follow it.
//
// A text is segmented with a single JS interop call, and the boundaries of the last few texts are
// kept: the layout and text editing look up many positions of the same text.

/**
 * Returns all grapheme cluster boundaries of [text]: an increasing array from 0 to `text.length`
 * (just `[0]` for an empty text). Do not modify it.
 */
internal fun graphemeBoundaries(text: CharSequence): IntArray =
    GraphemeCache.boundariesOrNull(text) ?: simpleBoundaries(text)

/**
 * Returns the first grapheme cluster boundary strictly after [index], or `text.length` if there is
 * none (i.e. when `index >= text.length`). For `index < 0` this is 0. [index] may be any position,
 * including one inside a cluster or a surrogate pair.
 */
internal fun nextGraphemeBoundary(text: CharSequence, index: Int): Int {
    val length = text.length
    if (index >= length) return length
    if (index < 0) return 0
    val boundaries = GraphemeCache.boundariesOrNull(text)
        ?: return if (text[index] == '\r' && index + 1 < length && text[index + 1] == '\n') {
            index + 2
        } else {
            index + 1
        }
    // The first boundary greater than index; the last one, text.length, is.
    var low = 0
    var high = boundaries.size - 1
    while (low < high) {
        val mid = (low + high) ushr 1
        if (boundaries[mid] > index) high = mid else low = mid + 1
    }
    return boundaries[low]
}

/**
 * Returns the last grapheme cluster boundary strictly before [index], or 0 if there is none (i.e.
 * when `index <= 0`). For `index > text.length` this is `text.length`. [index] may be any position,
 * including one inside a cluster or a surrogate pair.
 */
internal fun previousGraphemeBoundary(text: CharSequence, index: Int): Int {
    val length = text.length
    if (index <= 0) return 0
    if (index > length) return length
    val boundaries = GraphemeCache.boundariesOrNull(text)
        ?: return if (index >= 2 && text[index - 2] == '\r' && text[index - 1] == '\n') {
            index - 2
        } else {
            index - 1
        }
    // The last boundary less than index; the first one, 0, is.
    var low = 0
    var high = boundaries.size - 1
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        if (boundaries[mid] < index) low = mid else high = mid - 1
    }
    return boundaries[low]
}

/**
 * Whether every grapheme cluster of [text] is a single character, except CR LF: true for the
 * characters below U+0300 (Latin), which have no Grapheme_Cluster_Break property but CR, LF and
 * Control.
 */
private fun isSimple(text: CharSequence): Boolean {
    for (i in text.indices) {
        if (text[i].code >= 0x300) return false
    }
    return true
}

/** The boundaries of a text for which [isSimple] holds. */
private fun simpleBoundaries(text: CharSequence): IntArray {
    val length = text.length
    val result = IntArray(length + 1)
    var count = 0
    result[count++] = 0
    for (i in 1..length) {
        if (i < length && text[i - 1] == '\r' && text[i] == '\n') continue
        result[count++] = i
    }
    return if (count == result.size) result else result.copyOf(count)
}

/** The boundaries of the last few texts that aren't simple (see [isSimple]). */
private object GraphemeCache {
    private const val Size = 8

    /** Stands for the boundaries of a simple text, which aren't computed. */
    private val Simple = IntArray(0)

    private val texts = arrayOfNulls<String>(Size)
    private val boundaries = arrayOfNulls<IntArray>(Size)
    private var next = 0

    /** The boundaries of [text], or `null` if it is simple (see [isSimple]). */
    fun boundariesOrNull(text: CharSequence): IntArray? {
        val string = text.toString()
        for (i in 0 until Size) {
            val cached = texts[i] ?: continue
            if (cached === string || (cached.length == string.length && cached == string)) {
                val result = boundaries[i]
                return if (result === Simple) null else result
            }
        }
        val result = if (isSimple(string)) Simple else segment(string)
        texts[next] = string
        boundaries[next] = result
        next = (next + 1) % Size
        return if (result === Simple) null else result
    }

    private fun segment(text: String): IntArray {
        val segmenter = IntlSegmenters.grapheme ?: return approximateGraphemeBoundaries(text)
        return decodeClusterStarts(segmentGraphemes(segmenter, text), text.length)
    }
}

/**
 * The grapheme cluster boundaries of [text] (like [graphemeBoundaries]) for browsers without
 * `Intl.Segmenter`: a cluster is CR LF, a pair of regional indicators (a flag), or a character with
 * the extending characters and spacing marks that follow it, a zero width joiner taking the
 * pictograph after it along. Hangul syllables made of jamo and prepended characters are split.
 */
internal fun approximateGraphemeBoundaries(text: String): IntArray =
    decodeClusterStarts(approximateGraphemes(text), text.length)

/** Decodes the cluster starts encoded by [segmentGraphemes]: bits 0-14 and 15-29, two characters. */
private fun decodeClusterStarts(encoded: String, textLength: Int): IntArray {
    val count = encoded.length / 2
    val result = IntArray(count + 1)
    for (i in 0 until count) {
        result[i] = encoded[2 * i].code or (encoded[2 * i + 1].code shl 15)
    }
    result[count] = textLength
    return result
}

/** Returns the starts of the grapheme clusters of [text], two characters each (see the decoder). */
// language=js
private fun segmentGraphemes(segmenter: JsAny, text: String): String =
    js(
        """(function() {
    var codes = [];
    var result = '';
    var iterator = segmenter.segment(text)[Symbol.iterator]();
    for (var step = iterator.next(); !step.done; step = iterator.next()) {
        var index = step.value.index;
        codes.push(index & 0x7FFF, index >>> 15);
        if (codes.length >= 8192) {
            result += String.fromCharCode.apply(null, codes);
            codes.length = 0;
        }
    }
    return result + String.fromCharCode.apply(null, codes);
})()"""
    )

/** Like [segmentGraphemes], for browsers without `Intl.Segmenter`: see [approximateGraphemeBoundaries]. */
// language=js
private fun approximateGraphemes(text: String): String =
    js(
        """(function() {
    var pattern = /\r\n|\p{RI}\p{RI}|[\s\S](?:[\p{Grapheme_Extend}\p{Mc}\p{Emoji_Modifier}]|\u200D\p{Extended_Pictographic}?)*/gu;
    var codes = [];
    var result = '';
    var match;
    while ((match = pattern.exec(text)) !== null) {
        codes.push(match.index & 0x7FFF, match.index >>> 15);
        if (codes.length >= 8192) {
            result += String.fromCharCode.apply(null, codes);
            codes.length = 0;
        }
    }
    return result + String.fromCharCode.apply(null, codes);
})()"""
    )
