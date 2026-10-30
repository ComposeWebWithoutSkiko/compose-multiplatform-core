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

package androidx.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageComposeSceneWebTest {
    private fun ImageBitmap.pixel(x: Int, y: Int): Color {
        val argb = IntArray(1)
        readPixels(argb, x, y, width = 1, height = 1)
        return Color(argb[0])
    }

    @Test
    fun theFramesRenderedBeforeKeepTheirPixels() {
        ImageComposeScene(10, 10, content = { Box(Modifier.fillMaxSize().background(Color.Red)) }).use { scene ->
            val first = scene.render()
            scene.setContent { Box(Modifier.fillMaxSize().background(Color.Blue)) }
            val second = scene.render()
            assertEquals(Color.Red, first.pixel(5, 5))
            assertEquals(Color.Blue, second.pixel(5, 5))
        }
    }

    @Test
    fun aFrameIsRenderedRightIntoItsImage() {
        ImageComposeScene(10, 10, content = { Box(Modifier.fillMaxSize().background(Color.Red)) }).use { scene ->
            val spy = spyOnDrawImage()
            try {
                scene.render()
            } finally {
                stopSpying(spy)
            }
            assertEquals(0, callCount(spy), "images drawn (a copy of the frame)")
        }
    }
}

// language=js
private fun spyOnDrawImage(): JsAny = js(
    """{
        const spy = { count: 0, prototypes: [CanvasRenderingContext2D.prototype], originals: [] };
        if (typeof OffscreenCanvasRenderingContext2D !== 'undefined') {
            spy.prototypes.push(OffscreenCanvasRenderingContext2D.prototype);
        }
        for (const prototype of spy.prototypes) {
            const original = prototype.drawImage;
            spy.originals.push(original);
            prototype.drawImage = function () {
                spy.count++;
                return original.apply(this, arguments);
            };
        }
        return spy;
    }"""
)

// language=js
private fun stopSpying(spy: JsAny): Unit =
    js("spy.prototypes.forEach((prototype, i) => { prototype.drawImage = spy.originals[i]; })")

// language=js
private fun callCount(spy: JsAny): Int = js("spy.count")
