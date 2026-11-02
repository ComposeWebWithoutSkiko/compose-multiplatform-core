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

package androidx.compose.ui.webcanvas.graphics

import kotlin.js.JsString
import kotlin.js.toJsString
import kotlin.test.Test
import kotlin.test.assertEquals

class DrawTargetTest {
    /** A setting of a [DrawTarget] that takes a JS string, with two of its values. */
    private class Setting(
        val property: String,
        val value: String,
        val other: String,
        val set: DrawTarget.(JsString) -> Unit,
    )

    private val settings = listOf(
        Setting("globalCompositeOperation", "multiply", "screen") { setCompositeOperation(it) },
        Setting("lineCap", "round", "square") { setLineCap(it) },
        Setting("lineJoin", "round", "bevel") { setLineJoin(it) },
        Setting("font", "12px serif", "14px serif") { setFont(it) },
        Setting("lang", "ja", "ko") { setLanguage(it) },
        Setting("letterSpacing", "1px", "2px") { setLetterSpacing(it) },
        Setting("direction", "rtl", "ltr") { setDirection(it) },
        // A color as a style: what a sweep gradient falls back to without conic gradients.
        Setting("fillStyle", "#ff0000", "#00ff00") { setFillStyle(it) },
        Setting("strokeStyle", "#ff0000", "#00ff00") { setStrokeStyle(it) },
    )

    @Test
    fun aStringSettingIsWrittenOncePerValue() {
        // Equal JS strings are one value, whether the same instance or made apart: on wasm, `===`
        // between JS strings is never true, so a cache comparing them with it would always write.
        val mismatches = settings.filter { contextHasProperty(it.property) }.mapNotNull { setting ->
            val target = CanvasImageBitmap(1, 1).drawTarget
            val first = setting.value.toJsString()
            val writes = propertyWritesDuring(setting.property) {
                target.(setting.set)(first)
                target.(setting.set)(first)
                target.(setting.set)(setting.value.toJsString())
                target.(setting.set)(setting.other.toJsString())
            }
            if (writes == 2) null else "${setting.property}: $writes writes"
        }
        assertEquals(emptyList(), mismatches)
    }
}
