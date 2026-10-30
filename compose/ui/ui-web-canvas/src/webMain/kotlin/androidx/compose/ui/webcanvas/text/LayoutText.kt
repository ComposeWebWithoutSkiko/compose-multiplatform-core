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

package androidx.compose.ui.webcanvas.text

/**
 * [text] as a paragraph lays it out: every UTF-16 unit of a placeholder reads as an OBJECT
 * REPLACEMENT CHARACTER (U+FFFC), the object the placeholder shows instead of its text. Android does
 * the same for the paragraph direction and the bidi algorithm, and SkParagraph gets a U+FFFC for a
 * placeholder. Every analysis of the characters of a paragraph reads it (direction, bidi levels,
 * line breaks and terminators, hanging whitespace, justification, cursive joining); measuring and
 * drawing read [text].
 *
 * [text] itself without placeholders, otherwise a view of it.
 */
internal fun layoutTextOf(text: String, placeholders: List<PlaceholderRun>): CharSequence =
    if (placeholders.isEmpty()) text else TextWithObjects(text, placeholders)

/** [text] with the units of the [placeholders] (sorted, not overlapping) read as U+FFFC. */
private class TextWithObjects(
    private val text: String,
    private val placeholders: List<PlaceholderRun>,
) : CharSequence {
    override val length: Int get() = text.length

    override fun get(index: Int): Char = if (isInPlaceholder(index)) ObjectReplacementCharacter else text[index]

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        buildString(endIndex - startIndex) { for (i in startIndex until endIndex) append(get(i)) }

    private fun isInPlaceholder(index: Int): Boolean {
        var low = 0
        var high = placeholders.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val placeholder = placeholders[mid]
            when {
                index < placeholder.start -> high = mid - 1
                index >= placeholder.end -> low = mid + 1
                else -> return true
            }
        }
        return false
    }
}

private val ObjectReplacementCharacter = Char(0xFFFC)
