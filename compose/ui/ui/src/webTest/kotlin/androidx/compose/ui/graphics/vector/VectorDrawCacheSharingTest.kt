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

    @Test
    fun imagesOfOneVectorDrawFromTheCacheOfThePage() {
        val vector = square()
        var cache: GraphicsResourceCache? = null
        var key: VectorDrawCacheKey? = null
        val scene = ImageComposeScene(
            20,
            10,
            content = {
                cache = LocalGraphicsResourceCache.current
                key = VectorDrawCacheKey(
                    genId = vector.genId,
                    densityBits = LocalDensity.current.density.toBits(),
                    width = 10,
                    height = 10,
                    config = ImageBitmapConfig.Alpha8,
                )
                Row {
                    repeat(2) {
                        Image(rememberVectorPainter(vector), null, Modifier.size(10.dp))
                    }
                }
            },
        )
        scene.use { it.render() }
        assertSame(platformGraphicsResourceCache, cache)
        assertTrue(cache!!.contains(key!!))
    }

    @Test
    fun aSharedVectorIsDrawnInTheTintOfEachImage() {
        val vector = square()
        val scene = ImageComposeScene(
            20,
            10,
            content = {
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
            assertEquals(Color.Red, frame.pixel(5, 5))
            assertEquals(Color.Blue, frame.pixel(15, 5))
        }
    }
}
