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

// Bits of the emoji table, in the order of EMOJI_PROPERTIES in generate_unicode_tables.py.
private const val EMOJI_PRESENTATION = 1 shl 1
private const val EXTENDED_PICTOGRAPHIC = 1 shl 5
private const val UNASSIGNED_EXTENDED_PICTOGRAPHIC = 1 shl 6

private object EmojiData {
    val table = UnicodePropertyTable(EMOJI_DATA)
}

/**
 * Returns true if [codePoint] has the Unicode White_Space property (PropList.txt), which includes
 * the no-break spaces. See [isLineBreakWhitespace] for the ICU `u_isWhitespace` definition.
 */
internal fun isWhitespace(codePoint: Int): Boolean =
    when (codePoint) {
        in 0x09..0x0D,
        0x20,
        0x85,
        0xA0,
        0x1680,
        in 0x2000..0x200A,
        0x2028,
        0x2029,
        0x202F,
        0x205F,
        0x3000 -> true
        else -> false
    }

/** Returns true if [codePoint] has the Extended_Pictographic property (UTS #51). */
internal fun isExtendedPictographic(codePoint: Int): Boolean =
    EmojiData.table[codePoint] and EXTENDED_PICTOGRAPHIC != 0

/**
 * Returns true if [codePoint] has the Extended_Pictographic property but no character assigned yet
 * (General_Category Cn): one of the code points reserved for future emoji.
 */
internal fun isUnassignedExtendedPictographic(codePoint: Int): Boolean =
    EmojiData.table[codePoint] and UNASSIGNED_EXTENDED_PICTOGRAPHIC != 0

/** Returns true if [codePoint] has the Emoji_Presentation property (UTS #51). */
internal fun isEmojiPresentation(codePoint: Int): Boolean =
    EmojiData.table[codePoint] and EMOJI_PRESENTATION != 0
