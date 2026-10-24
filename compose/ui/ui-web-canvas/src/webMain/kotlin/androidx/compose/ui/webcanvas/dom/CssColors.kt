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

package androidx.compose.ui.webcanvas.dom

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString
import kotlin.js.toJsString

/**
 * Converts ARGB colors to CSS color strings (`#rrggbbaa`). Conversions are cached in a small
 * direct-mapped table, because the same handful of colors is drawn over and over.
 */
internal object CssColors {
    private const val CacheSize = 1024 // must be a power of two

    private val keys = IntArray(CacheSize)
    private val values = arrayOfNulls<JsString>(CacheSize)

    private val hexDigits = "0123456789abcdef".toCharArray()

    fun of(argb: Int): JsString {
        val slot = mix(argb) and (CacheSize - 1)
        val cached = values[slot]
        if (cached != null && keys[slot] == argb) return cached
        val css = format(argb).toJsString()
        keys[slot] = argb
        values[slot] = css
        return css
    }

    private fun format(argb: Int): String {
        val chars = CharArray(9)
        chars[0] = '#'
        putByte(chars, 1, argb ushr 16)
        putByte(chars, 3, argb ushr 8)
        putByte(chars, 5, argb)
        putByte(chars, 7, argb ushr 24)
        return chars.concatToString()
    }

    private fun putByte(chars: CharArray, offset: Int, value: Int) {
        chars[offset] = hexDigits[(value ushr 4) and 0xF]
        chars[offset + 1] = hexDigits[value and 0xF]
    }

    private fun mix(value: Int): Int {
        var h = value * -0x61c88647
        h = h xor (h ushr 16)
        return h
    }
}
