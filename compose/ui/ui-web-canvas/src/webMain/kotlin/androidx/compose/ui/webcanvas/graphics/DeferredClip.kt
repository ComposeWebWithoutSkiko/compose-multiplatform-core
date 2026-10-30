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

import androidx.compose.ui.webcanvas.path.MaxDisplayConicTolerance
import androidx.compose.ui.webcanvas.path.PathData
import androidx.compose.ui.webcanvas.path.asRoundRect
import androidx.compose.ui.webcanvas.path.toPath2D
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * A clip to a rounded rectangle that [WebCanvas] applies to the context only for the draws that may
 * reach out of it. Browsers draw under a clip that isn't a rectangle at a cost (Safari's GPU process
 * takes twice as long for text), while most of what's drawn under such a clip, the content of a card
 * or a button, lies inside its shape.
 *
 * The clip is set with a transformation that keeps rectangles axis-aligned, so the shape is a rounded
 * rectangle on the device too.
 */
internal class DeferredClip(
    /** The clip, a rounded rectangle (see [asRoundRect]). */
    private val path: PathData,
    matrix: Affine,
    /** The depth of the stack of the canvas where the clip was set. */
    val depth: Int,
    /** The rounded rectangle of [path], as [asRoundRect] gives it. */
    roundRect: FloatArray,
) {
    /** The transformation the clip was set with. */
    private val matrix = matrix.copy()

    /** The rounded rectangle on the device, laid out like [roundRect]. */
    private val shape = FloatArray(12)

    /**
     * How far inside the edge the clip covers the pixels fully: past its antialiasing, and past the
     * quadratic curves drawing its corners (see [toPath2D]).
     */
    private val margin: Float

    init {
        matrix.mapRect(roundRect[0], roundRect[1], roundRect[2], roundRect[3], shape)
        val scaleX = abs(matrix.a)
        val scaleY = abs(matrix.d)
        for (corner in 0 until 4) {
            // A negative scale mirrors the corners.
            var source = if (matrix.a < 0f) corner xor 1 else corner
            if (matrix.d < 0f) source = 3 - source
            shape[4 + 2 * corner] = scaleX * roundRect[4 + 2 * source]
            shape[5 + 2 * corner] = scaleY * roundRect[5 + 2 * source]
        }
        margin = 1f + MaxDisplayConicTolerance * max(scaleX, scaleY)
    }

    /**
     * Whether the device pixels the rectangle ([left], [top], [right], [bottom]) touches lie inside
     * the shape, where the clip leaves them as they are.
     */
    fun contains(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        val l = floor(left) - margin
        val t = floor(top) - margin
        val r = ceil(right) + margin
        val b = ceil(bottom) + margin
        val s = shape
        if (l < s[0] || t < s[1] || r > s[2] || b > s[3]) return false
        // Each corner of the rectangle lies inside the ellipse of the corner of the shape, so the
        // rest of the rectangle, further from the corner, does too.
        return cornerContains(0, s[0] + s[4] - l, s[1] + s[5] - t) &&
            cornerContains(1, r - (s[2] - s[6]), s[1] + s[7] - t) &&
            cornerContains(2, r - (s[2] - s[8]), b - (s[3] - s[9])) &&
            cornerContains(3, s[0] + s[10] - l, b - (s[3] - s[11]))
    }

    /**
     * Whether the point at ([dx], [dy]) from the center of the ellipse of [corner], towards the
     * corner, lies inside the ellipse, or else not in the corner.
     */
    private fun cornerContains(corner: Int, dx: Float, dy: Float): Boolean {
        val radiusX = shape[4 + 2 * corner]
        val radiusY = shape[5 + 2 * corner]
        // A corner without one of its radii is square.
        if (radiusX <= 0f || radiusY <= 0f || dx <= 0f || dy <= 0f) return true
        val x = dx / radiusX
        val y = dy / radiusY
        return x * x + y * y <= 1f
    }

    /** Whether the device rectangle ([left], [top], [right], [bottom]) covers the whole shape. */
    fun isCoveredBy(left: Float, top: Float, right: Float, bottom: Float): Boolean =
        left <= shape[0] && top <= shape[1] && right >= shape[2] && bottom >= shape[3]

    /** Clips the context of [target] to the shape. */
    fun applyTo(target: DrawTarget) {
        target.setTransform(matrix)
        target.ctx.clip(path.toPath2D(), path.fillType.toJs())
    }
}
