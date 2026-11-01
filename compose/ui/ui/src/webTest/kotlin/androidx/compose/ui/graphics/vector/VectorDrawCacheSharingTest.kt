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

package androidx.compose.ui.graphics.vector

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.GraphicsResourceCache
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsResourceCache
import androidx.compose.ui.platform.platformGraphicsResourceCache
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VectorDrawCacheSharingTest {
    private fun square() =
        ImageVector.Builder(
            defaultWidth = 10.dp,
            defaultHeight = 10.dp,
            viewportWidth = 10f,
            viewportHeight = 10f,
        ).path(fill = SolidColor(Color.Black)) {
            moveTo(0f, 0f)
            lineTo(10f, 0f)
            lineTo(10f, 10f)
            lineTo(0f, 10f)
            close()
        }.build()

    private fun ImageBitmap.pixel(x: Int, y: Int): Color {
        val argb = IntArray(1)
        readPixels(argb, x, y, width = 1, height = 1)
        return Color(argb[0])
    }

    @Composable
    private fun keyOf(vector: ImageVector) =
        VectorDrawCacheKey(
            genId = vector.genId,
            densityBits = LocalDensity.current.density.toBits(),
            width = 10,
            height = 10,
            config = ImageBitmapConfig.Alpha8,
        )

    @Test
    fun theOwnerOfAPageGivesVectorsTheCacheOfThePage() {
        val vector = square()
        var cache: GraphicsResourceCache? = null
        var key: VectorDrawCacheKey? = null
        val scene = ImageComposeScene(
            10,
            10,
            content = {
                cache = LocalGraphicsResourceCache.current
                key = keyOf(vector)
                Image(rememberVectorPainter(vector), null, Modifier.size(10.dp))
            },
        )
        scene.use { it.render() }
        assertSame(platformGraphicsResourceCache, cache)
        assertTrue(cache!!.contains(key!!))
    }

    @Test
    fun aVectorFollowsATintThatChanges() {
        val vector = square()
        var tint by mutableStateOf(Color.Red)
        val scene = ImageComposeScene(
            10,
            10,
            content = {
                Image(
                    rememberVectorPainter(vector),
                    null,
                    Modifier.size(10.dp),
                    colorFilter = ColorFilter.tint(tint),
                )
            },
        )
        scene.use {
            assertEquals(Color.Red, it.render().pixel(5, 5))
            tint = Color.Blue
            assertEquals(Color.Blue, it.render().pixel(5, 5))
        }
    }

    @Test
    fun aSharedVectorIsDrawnInTheTintOfEachImage() {
        val vector = square()
        var key: VectorDrawCacheKey? = null
        val scene = ImageComposeScene(
            20,
            10,
            content = {
                key = keyOf(vector)
                Row {
                    for (tint in listOf(Color.Red, Color.Blue)) {
                        Image(
                            rememberVectorPainter(vector),
                            null,
                            Modifier.size(10.dp),
                            colorFilter = ColorFilter.tint(tint),
                        )
                    }
                }
            },
        )
        scene.use {
            val frame = it.render()
            assertTrue(platformGraphicsResourceCache!!.contains(key!!))
            assertEquals(Color.Red, frame.pixel(5, 5))
            assertEquals(Color.Blue, frame.pixel(15, 5))
        }
    }

    private fun scene(count: Int, vector: ImageVector) =
        ImageComposeScene(
            80,
            10,
            content = {
                Row {
                    repeat(count) {
                        Image(
                            rememberVectorPainter(vector),
                            null,
                            Modifier.size(10.dp),
                            colorFilter = ColorFilter.tint(Color.Red),
                        )
                    }
                }
            },
        )

    private class DrawCalls(val canvases: Int, val fills: Int)

    /** The canvases made and the rectangles filled during [block]. */
    private fun drawCallsDuring(block: () -> Unit): DrawCalls {
        val spy = spyOnDrawCalls()
        try {
            block()
        } finally {
            stopSpying(spy)
        }
        return DrawCalls(canvasesMade(spy), rectanglesFilled(spy))
    }

    @Test
    fun manyCardsOfOneTintTakeOneCanvas() {
        // Canvases made once, the first time they're needed, are out of the count.
        scene(8, square()).use { it.render() }
        val empty = scene(0, square())
        val emptyFirst = drawCallsDuring { empty.render() }
        val emptySecond = drawCallsDuring { empty.render() }
        empty.close()
        val cards = scene(8, square())
        val first = drawCallsDuring { cards.render() }
        val second = drawCallsDuring { cards.render() }
        cards.close()
        assertEquals(emptyFirst.canvases + 1, first.canvases)
        assertEquals(emptySecond.canvases, second.canvases)
        assertEquals(emptySecond.fills, second.fills)
    }
}

// language=js
private fun spyOnDrawCalls(): JsAny = js(
    """{
        const spy = { canvases: 0, fills: 0, restores: [] };
        const createElement = document.createElement;
        document.createElement = function (name, ...rest) {
            if (String(name).toLowerCase() === 'canvas') spy.canvases++;
            return createElement.call(this, name, ...rest);
        };
        spy.restores.push(() => { document.createElement = createElement; });
        if (typeof OffscreenCanvas !== 'undefined') {
            const Original = OffscreenCanvas;
            globalThis.OffscreenCanvas = new Proxy(Original, {
                construct(target, args) {
                    spy.canvases++;
                    return new target(...args);
                },
            });
            spy.restores.push(() => { globalThis.OffscreenCanvas = Original; });
        }
        const prototypes = [CanvasRenderingContext2D.prototype];
        if (typeof OffscreenCanvasRenderingContext2D !== 'undefined') {
            prototypes.push(OffscreenCanvasRenderingContext2D.prototype);
        }
        for (const prototype of prototypes) {
            const fillRect = prototype.fillRect;
            prototype.fillRect = function (...args) {
                spy.fills++;
                return fillRect.apply(this, args);
            };
            spy.restores.push(() => { prototype.fillRect = fillRect; });
        }
        return spy;
    }"""
)

// language=js
private fun stopSpying(spy: JsAny): Unit = js("spy.restores.forEach((restore) => restore())")

// language=js
private fun canvasesMade(spy: JsAny): Int = js("spy.canvases")

// language=js
private fun rectanglesFilled(spy: JsAny): Int = js("spy.fills")
