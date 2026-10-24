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

/**
 * The browser's `Intl.Segmenter`s, created on demand (creating one is relatively expensive).
 * Browsers implement them with ICU, the library SkParagraph segments text with on the other
 * targets. Among the browsers that run Kotlin/Wasm, only Firefox before 125 lacks them.
 */
internal object IntlSegmenters {
    private const val MaxWordSegmenters = 8

    /** Whether the browser has `Intl.Segmenter`. */
    val isAvailable: Boolean = isIntlSegmenterSupported()

    private val wordSegmenters = HashMap<String, JsAny>()

    /** The grapheme cluster segmenter of the default locale, or `null` without `Intl.Segmenter`. */
    val grapheme: JsAny? by lazy { if (isAvailable) createSegmenter(null, "grapheme") else null }

    /**
     * The word segmenter of the BCP 47 [locale] (the default one for `null` or an invalid locale),
     * or `null` without `Intl.Segmenter`.
     */
    fun word(locale: String?): JsAny? {
        if (!isAvailable) return null
        val key = locale ?: ""
        wordSegmenters[key]?.let { return it }
        val segmenter = createSegmenter(locale, "word") ?: return null
        if (wordSegmenters.size >= MaxWordSegmenters) wordSegmenters.clear()
        wordSegmenters[key] = segmenter
        return segmenter
    }
}

// language=js
private fun isIntlSegmenterSupported(): Boolean =
    js("(typeof Intl !== 'undefined' && typeof Intl.Segmenter === 'function')")

/** Creates a segmenter, falling back to the default locale for an invalid [locale]. */
// language=js
private fun createSegmenter(locale: String?, granularity: String): JsAny? =
    js(
        """(function() {
    try {
        return new Intl.Segmenter(locale == null ? undefined : locale, { granularity: granularity });
    } catch (e) {
        try {
            return new Intl.Segmenter(undefined, { granularity: granularity });
        } catch (e2) {
            return null;
        }
    }
})()"""
    )
