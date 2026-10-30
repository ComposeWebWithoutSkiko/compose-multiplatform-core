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

/** The smallest supplementary code point (U+10000). */
internal const val MIN_SUPPLEMENTARY_CODE_POINT: Int = 0x10000

/** The largest Unicode code point (U+10FFFF). */
internal const val MAX_CODE_POINT: Int = 0x10FFFF

/** Number of UTF-16 code units used to encode [codePoint] (1 or 2). */
internal fun charCount(codePoint: Int): Int =
    if (codePoint >= MIN_SUPPLEMENTARY_CODE_POINT) 2 else 1

/**
 * Returns the code point that starts at [index] in [text].
 *
 * A well-formed surrogate pair is combined into a supplementary code point; an unpaired surrogate
 * is returned as is (it is its own "code point", matching the behavior of ICU and the JDK).
 */
internal fun codePointAt(text: CharSequence, index: Int): Int {
    val high = text[index]
    if (high.isHighSurrogate() && index + 1 < text.length) {
        val low = text[index + 1]
        if (low.isLowSurrogate()) return toCodePoint(high, low)
    }
    return high.code
}

/**
 * Returns the code point that ends right before [index] in [text], i.e. the code point that
 * contains the code unit at `index - 1`. Unpaired surrogates are returned as is.
 */
internal fun codePointBefore(text: CharSequence, index: Int): Int {
    val low = text[index - 1]
    if (low.isLowSurrogate() && index - 2 >= 0) {
        val high = text[index - 2]
        if (high.isHighSurrogate()) return toCodePoint(high, low)
    }
    return low.code
}

/** Combines a valid surrogate pair into a supplementary code point. */
internal fun toCodePoint(high: Char, low: Char): Int =
    ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + MIN_SUPPLEMENTARY_CODE_POINT
