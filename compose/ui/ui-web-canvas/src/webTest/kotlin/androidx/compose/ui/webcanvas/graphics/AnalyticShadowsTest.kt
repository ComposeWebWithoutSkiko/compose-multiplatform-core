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

@file:OptIn(InternalComposeUiApi::class)

package androidx.compose.ui.webcanvas.graphics

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.webcanvas.path.WebPath
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** The shadows of rounded rectangles, drawn without blurring, look like the blurred ones. */
class AnalyticShadowsTest {
    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    private fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float) =
        ShadowShape.RoundRect(
            left, top, right, bottom, radius,
            path = WebPath()
                .apply { addRoundRect(RoundRect(left, top, right, bottom, CornerRadius(radius))) }
                .snapshot(),
            key = null,
            spriteRequests = null,
        )

    /** The same shape, blurred. */
    private fun ShadowShape.blurred() = ShadowShape.Path(path, key = null, spriteRequests = null)

    /** The shadow of [shape] in opaque black, blurred by [sigma], after [transform]. */
    private fun shadow(shape: ShadowShape, sigma: Float, transform: Canvas.() -> Unit = {}): PixelMap {
        val image = ImageBitmap(Size, Size)
        val canvas = Canvas(image) as WebCanvas
        canvas.transform()
        canvas.drawShadow(shape, WebCanvas.OpaqueBlack, sigma, offsetX = 0f, offsetY = 0f)
        canvas.finish()
        return image.toPixelMap()
    }

    /** The largest difference of alpha between [a] and [b] over the pixels that [where] selects. */
    private fun maxDifference(a: PixelMap, b: PixelMap, where: (Int, Int) -> Boolean = { _, _ -> true }): Float {
        var difference = 0f
        for (y in 0 until Size) {
            for (x in 0 until Size) {
                if (where(x, y)) difference = max(difference, abs(a[x, y].alpha - b[x, y].alpha))
            }
        }
        return difference
    }

    // Between pixels, where the regions of the shadow meet within pixels.
    private val betweenPixels: Canvas.() -> Unit = { translate(0.37f, 0.61f) }

    @Test
    fun roundedCornersLookLikeTheBlurredShadow() {
        val shape = roundRect(20f, 20f, 80f, 70f, radius = 16f)
        val difference = maxDifference(
            shadow(shape, sigma = 3f, betweenPixels),
            shadow(shape.blurred(), sigma = 3f, betweenPixels),
        )
        assertTrue(difference <= RoundedTolerance, "alpha differs by $difference")
    }

    @Test
    fun theEdgesOfARectangleLookLikeTheBlurredShadow() {
        val shape = roundRect(20f, 20f, 80f, 70f, radius = 0f)
        val analytic = shadow(shape, sigma = 3f, betweenPixels)
        val blurred = shadow(shape.blurred(), sigma = 3f, betweenPixels)
        // Away from the corners, which are rounded by the blur (see ShadowPainter).
        val edges = maxDifference(analytic, blurred) { x, y -> x in 32..68 || y in 32..58 }
        assertTrue(edges <= EdgeTolerance, "alpha differs by $edges along the edges")
        val corners = maxDifference(analytic, blurred)
        assertTrue(corners <= RectangleCornerTolerance, "alpha differs by $corners at the corners")
    }

    @Test
    fun aRotatedShadowIsBlurred() {
        val shape = roundRect(20f, 20f, 80f, 70f, radius = 16f)
        val rotate: Canvas.() -> Unit = {
            translate(50f, 45f)
            rotate(30f)
            translate(-50f, -45f)
        }
        assertTrue(maxDifference(shadow(shape, 3f, rotate), shadow(shape.blurred(), 3f, rotate)) == 0f)
    }

    @Test
    fun aShadowScaledUnevenlyIsBlurred() {
        val shape = roundRect(10f, 20f, 40f, 70f, radius = 8f)
        val scale: Canvas.() -> Unit = { scale(2f, 1f) }
        assertTrue(maxDifference(shadow(shape, 3f, scale), shadow(shape.blurred(), 3f, scale)) == 0f)
    }

    @Test
    fun aShapeNarrowerThanTheBlurIsBlurred() {
        val shape = roundRect(40f, 40f, 50f, 50f, radius = 2f)
        assertTrue(maxDifference(shadow(shape, 4f), shadow(shape.blurred(), 4f)) == 0f)
    }

    private companion object {
        const val Size = 100

        /** Along an edge: the profile in 9 stops, against the blur of the browser. */
        const val EdgeTolerance = 0.03f

        /**
         * The corners follow the distance to their arcs, which differs from a Gaussian blur of the
         * arcs by a few percent.
         */
        const val RoundedTolerance = 0.06f

        /** The corners of a rectangle are rounded as much as the blur spreads. */
        const val RectangleCornerTolerance = 0.2f
    }
}
