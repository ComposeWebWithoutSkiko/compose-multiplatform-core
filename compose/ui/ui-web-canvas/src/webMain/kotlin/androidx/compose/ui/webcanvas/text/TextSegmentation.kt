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

import androidx.compose.ui.InternalComposeUiApi

/**
 * Unicode text segmentation for the libraries built on top of the web implementation (e.g. the
 * text editing of foundation), which uses ICU through Skia on the other targets.
 */
@InternalComposeUiApi
object TextSegmentation {
    /**
     * Returns the grapheme cluster boundary before [index], or -1 if there is none (like ICU's
     * `BreakIterator.preceding`).
     */
    fun precedingGraphemeBreak(text: String, index: Int): Int =
        if (index <= 0) -1 else TextUnicode.previousGraphemeBoundary(text, index.coerceAtMost(text.length))

    /**
     * Returns the grapheme cluster boundary after [index], or -1 if there is none (like ICU's
     * `BreakIterator.following`).
     */
    fun followingGraphemeBreak(text: String, index: Int): Int =
        if (index >= text.length) -1 else TextUnicode.nextGraphemeBoundary(text, index.coerceAtLeast(0))

    /** Whether [codePoint] has the Emoji_Presentation or Extended_Pictographic property. */
    fun isEmojiPresentationOrExtendedPictographic(codePoint: Int): Boolean =
        TextUnicode.isEmojiPresentationOrExtendedPictographic(codePoint)
}
