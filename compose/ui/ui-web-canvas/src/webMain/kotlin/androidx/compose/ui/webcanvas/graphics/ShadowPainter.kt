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

import androidx.compose.ui.webcanvas.path.toPath2D
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Paints the shadows of shapes into draw targets in opaque colors, the same way into the frame,
 * into the images of shadows (see [ShadowSprites]) and into their slots (see [ShadowSlots]): the
 * alpha of a shadow is the global alpha of its target.
 */
internal object ShadowPainter {
    private val region = Affine()
    private val rect = FloatArray(4)

    /**
     * The scale of the matrix of the rounded rectangle that [fitsGradients] accepted last, and how
     * far its profile spreads on both sides of an edge and its corners reach inside its edges
     * (corners rounded less than the blur spreads are taken as rounded that much): what
     * [paintRoundRect] paints it with.
     */
    private var scale = 0f
    private var spread = 0f
    private var inset = 0f

    /**
     * Whether [paint] has the browser blur the shadow of [shape], placed by [matrix] and blurred by
     * [sigma] pixels: when it's blurred at all, unless it's a rounded rectangle painted without
     * blurring (see [paintRoundRect]).
     */
    fun blurs(shape: ShadowShape, matrix: Affine, sigma: Float): Boolean =
        sigma > 0f && (shape !is ShadowShape.RoundRect || !fitsGradients(shape, matrix, sigma))

    /**
     * Paints the shadow of [shape], placed by [matrix] and then moved by ([dx], [dy]) in the root
     * device space of [target], blurred by [sigma] pixels in the opaque color [rgb], without the
     * shape itself: without blurring for a rounded rectangle if it can (see [paintRoundRect]),
     * blurred by the browser otherwise.
     */
    fun paint(
        target: DrawTarget,
        shape: ShadowShape,
        matrix: Affine,
        dx: Float,
        dy: Float,
        sigma: Float,
        rgb: Int,
    ) {
        if (shape is ShadowShape.RoundRect && fitsGradients(shape, matrix, sigma)) {
            paintRoundRect(target, shape, matrix, dx, dy, rgb)
            return
        }
        target.setFillColor(WebCanvas.OpaqueBlack)
        beginShadowOnly(
            target.ctx, rgb, sigma, offsetX = 0.0, offsetY = 0.0,
            matrix.a, matrix.b, matrix.c, matrix.d,
            (matrix.e + dx - target.originX).toDouble(), (matrix.f + dy - target.originY).toDouble(),
        )
        target.ctx.fill(shape.path.toPath2D(), shape.path.fillType.toJs())
        endShadowOnly(target.ctx)
        target.invalidateTransform()
    }

    /**
     * Whether [paintRoundRect] can paint the shadow of [shape], blurred by [sigma] pixels: not when
     * [matrix] does more than scaling uniformly and translating, when the blur spreads across the
     * shape, or without blur. A shape scaled to nothing has nothing to paint. Sets [scale],
     * [spread] and [inset].
     */
    private fun fitsGradients(shape: ShadowShape.RoundRect, matrix: Affine, sigma: Float): Boolean {
        if (!matrix.isAxisAligned || !matrix.isSimilarity) return false
        scale = matrix.averageScale
        if (scale == 0f) return true
        // The size of the shape on the device: an axis-aligned matrix scales it by a and d.
        val width = abs(matrix.a) * (shape.right - shape.left)
        val height = abs(matrix.d) * (shape.bottom - shape.top)
        spread = ShadowGradients.Spread * sigma
        inset = max(min(shape.radius * scale, min(width, height) / 2), spread)
        return spread > 0f && width >= 2 * inset && height >= 2 * inset
    }

    /**
     * Paints the shadow of a rounded rectangle like [paint] does, without blurring, like Skia
     * draws the shadows of rounded rectangles: the inside filled, the edges and the corners filled
     * with the profile of a blurred edge (see [ShadowGradients]), the corners around their arcs.
     * The regions are cut at whole pixels, so that every pixel is filled once: the antialiased
     * edges of two fills meeting within a pixel would cover it partly. Only for the shadow that
     * [fitsGradients] accepted last.
     */
    private fun paintRoundRect(
        target: DrawTarget,
        shape: ShadowShape.RoundRect,
        matrix: Affine,
        dx: Float,
        dy: Float,
        rgb: Int,
    ) {
        if (scale == 0f) return
        // The shape in the root device space.
        matrix.mapRect(shape.left, shape.top, shape.right, shape.bottom, rect)
        val left = rect[0] + dx
        val top = rect[1] + dy
        val right = rect[2] + dx
        val bottom = rect[3] + dy
        // The regions: outside, the edge bands and the corners, then the inside.
        val x0 = floor(left - spread)
        val x1 = round(left + inset)
        val x2 = max(x1, round(right - inset))
        val x3 = ceil(right + spread)
        val y0 = floor(top - spread)
        val y1 = round(top + inset)
        val y2 = max(y1, round(bottom - inset))
        val y3 = ceil(bottom + spread)
        val gradients = ShadowGradients.of(rgb)
        target.setFillColor(rgb)
        fillRegion(target, 1f, 0f, 0f, 1f, 0f, 0f, x1, y1, x2, y2)
        // The edges: the profile from `spread` inside to `spread` outside along the x axis of the
        // edge gradient, mapped across each edge.
        target.setFillStyle(gradients.edge)
        val across = 2 * spread
        fillRegion(target, 0f, -across, 1f, 0f, 0f, top + spread, x1, y0, x2, y1)
        fillRegion(target, 0f, across, 1f, 0f, 0f, bottom - spread, x1, y2, x2, y3)
        fillRegion(target, -across, 0f, 0f, 1f, left + spread, 0f, x0, y1, x1, y2)
        fillRegion(target, across, 0f, 0f, 1f, right - spread, 0f, x2, y1, x3, y2)
        // The corners: the profile around the centers of their arcs, scaled to the outer radius.
        val outer = inset + spread
        target.setFillStyle(gradients.corner((inset - spread) / outer))
        fillRegion(target, outer, 0f, 0f, outer, left + inset, top + inset, x0, y0, x1, y1)
        fillRegion(target, outer, 0f, 0f, outer, right - inset, top + inset, x2, y0, x3, y1)
        fillRegion(target, outer, 0f, 0f, outer, left + inset, bottom - inset, x0, y2, x1, y3)
        fillRegion(target, outer, 0f, 0f, outer, right - inset, bottom - inset, x2, y2, x3, y3)
    }

    /**
     * Fills the rectangle ([left], [top], [right], [bottom]) of the root device space of [target]
     * with its current fill style, placed by the transformation (a, b, c, d, e, f) into that space.
     */
    private fun fillRegion(
        target: DrawTarget,
        a: Float,
        b: Float,
        c: Float,
        d: Float,
        e: Float,
        f: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        if (right <= left || bottom <= top) return
        val space = region.set(a, b, c, d, e, f)
        target.setTransform(space)
        // The rectangle in the space of the fill style.
        space.invert()
        space.mapRect(left, top, right, bottom, rect)
        target.ctx.fillRect(
            rect[0].toDouble(), rect[1].toDouble(),
            (rect[2] - rect[0]).toDouble(), (rect[3] - rect[1]).toDouble(),
        )
    }
}
