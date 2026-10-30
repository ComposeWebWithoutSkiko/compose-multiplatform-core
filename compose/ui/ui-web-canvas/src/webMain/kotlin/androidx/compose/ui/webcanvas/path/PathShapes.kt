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

package androidx.compose.ui.webcanvas.path

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

// Rectangles, ovals and round rects of a WebPath: added as Skia adds them (start points,
// directions, quarter conics, normalized radii), so that the paths match the skiko backend, and
// rectangles recognized in paths, so that the renderer fills them with the Canvas 2D primitive.

/** Port of Skia's `SkPath::addRect(rect, dir, startIndex)`. */
internal fun WebPath.addRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    clockwise: Boolean,
    startIndex: Int,
) {
    ensureCapacity(5, 4, 0)
    var index = startIndex and 3
    val advance = if (clockwise) 1 else 3
    moveTo(rectPointX(index, left, right), rectPointY(index, top, bottom))
    for (i in 0 until 3) {
        index = (index + advance) and 3
        lineTo(rectPointX(index, left, right), rectPointY(index, top, bottom))
    }
    close()
}

/** Port of Skia's `SkPath::addOval(oval, dir, startIndex)`: 4 quarter conics. */
internal fun WebPath.addOval(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    clockwise: Boolean,
    startIndex: Int,
) {
    ensureCapacity(6, 9, 4)
    val centerX = midpoint(left, right)
    val centerY = midpoint(top, bottom)
    val advance = if (clockwise) 1 else 3
    var ovalIndex = startIndex and 3
    // The corner iterator points are tracking "behind" the oval points.
    var rectIndex = (startIndex + if (clockwise) 0 else 1) and 3
    moveTo(
        ovalPointX(ovalIndex, left, right, centerX),
        ovalPointY(ovalIndex, top, bottom, centerY),
    )
    for (i in 0 until 4) {
        rectIndex = (rectIndex + advance) and 3
        ovalIndex = (ovalIndex + advance) and 3
        conicTo(
            rectPointX(rectIndex, left, right),
            rectPointY(rectIndex, top, bottom),
            ovalPointX(ovalIndex, left, right, centerX),
            ovalPointY(ovalIndex, top, bottom, centerY),
            QuarterConicWeight,
        )
    }
    close()
}

/**
 * Port of Skia's `SkPath::addRRect(rrect, dir, startIndex)`. [radii] holds the x/y radii of the
 * top-left, top-right, bottom-right and bottom-left corners; they are normalized like
 * `SkRRect::setRectRadii` (negative radii clamped, overlapping radii scaled down).
 */
internal fun WebPath.addRoundRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radii: FloatArray,
    clockwise: Boolean,
    startIndex: Int,
) {
    val rrect = NormalizedRoundRect(left, top, right, bottom, radii)
    val l = rrect.left
    val t = rrect.top
    val r = rrect.right
    val b = rrect.bottom
    when (rrect.type) {
        NormalizedRoundRect.TypeEmpty,
        NormalizedRoundRect.TypeRect -> {
            // Degenerate (rect) => radii points are collapsing.
            addRect(l, t, r, b, clockwise, (startIndex + 1) / 2)
        }
        NormalizedRoundRect.TypeOval -> {
            // Degenerate (oval) => line points are collapsing.
            addOval(l, t, r, b, clockwise, startIndex / 2)
        }
        else -> {
            ensureCapacity(10, 13, 4)
            val rr = rrect.radii
            // Points of SkPath_RRectPointIterator.
            val xs = floatArrayOf(l + rr[0], r - rr[2], r, r, r - rr[4], l + rr[6], l, l)
            val ys = floatArrayOf(t, t, t + rr[3], b - rr[5], b, b, b - rr[7], t + rr[1])
            // We start with a conic on odd indices when moving CW vs. even indices when moving
            // CCW.
            val startsWithConic = ((startIndex and 1) == 1) == clockwise
            val rrectAdvance = if (clockwise) 1 else 7
            val rectAdvance = if (clockwise) 1 else 3
            var rrectIndex = startIndex and 7
            // Corner iterator indices follow the collapsed radii model, adjusted such that the
            // start point is "behind" the radii start point.
            var rectIndex = (startIndex / 2 + if (clockwise) 0 else 1) and 3

            fun nextRRect() {
                rrectIndex = (rrectIndex + rrectAdvance) and 7
            }
            fun conic() {
                rectIndex = (rectIndex + rectAdvance) and 3
                nextRRect()
                conicTo(
                    rectPointX(rectIndex, l, r),
                    rectPointY(rectIndex, t, b),
                    xs[rrectIndex],
                    ys[rrectIndex],
                    QuarterConicWeight,
                )
            }
            fun line() {
                nextRRect()
                lineTo(xs[rrectIndex], ys[rrectIndex])
            }

            moveTo(xs[rrectIndex], ys[rrectIndex])
            if (startsWithConic) {
                for (i in 0 until 3) {
                    conic()
                    line()
                }
                conic()
                // Final lineTo handled by close().
            } else {
                for (i in 0 until 4) {
                    line()
                    conic()
                }
            }
            close()
        }
    }
}

/**
 * Returns the rectangle if this path is a single closed, axis-aligned rectangle with a non-zero
 * area (as produced by `addRect`, in any direction and from any corner), null otherwise. The start
 * point and the direction of the contour are not reported, so callers that depend on them (e.g.
 * dashing) should use the path.
 */
internal fun PathData.asRect(): Rect? {
    val count = verbCount
    if (count != 5 && count != 6) return null
    if (verbs[0] != PathVerb.Move || verbs[count - 1] != PathVerb.Close) return null
    for (i in 1 until count - 1) {
        if (verbs[i] != PathVerb.Line) return null
    }
    val pts = points
    if (count == 6) {
        // Explicit closing line back to the start point.
        if (pts[8] != pts[0] || pts[9] != pts[1]) return null
    }
    val x0 = pts[0]
    val y0 = pts[1]
    val x1 = pts[2]
    val y1 = pts[3]
    val x2 = pts[4]
    val y2 = pts[5]
    val x3 = pts[6]
    val y3 = pts[7]
    val isRect =
        (x0 == x1 && y1 == y2 && x2 == x3 && y3 == y0) ||
            (y0 == y1 && x1 == x2 && y2 == y3 && x3 == x0)
    if (!isRect) return null
    val left = minOf(x0, x2)
    val right = maxOf(x0, x2)
    val top = minOf(y0, y2)
    val bottom = maxOf(y0, y2)
    if (!(left < right && top < bottom)) return null
    return Rect(left, top, right, bottom)
}

/**
 * Writes the rounded rectangle this path is into [out] and returns `true` if the path is a single
 * closed contour going once around a rounded rectangle, as `addRoundRect` and `addOval` make it, in
 * any direction and from any point: 4 quarter conics, one around each corner of the bounds, its
 * control point, from an edge to the next, with at most a line along the edge between two of them.
 * [out] receives the left, top, right and bottom, then the x and y radii of the top-left,
 * top-right, bottom-right and bottom-left corners (zero for a square corner). Rectangles without
 * conics (see [asRect]) aren't recognized.
 */
internal fun PathData.asRoundRect(out: FloatArray): Boolean {
    val count = verbCount
    if (count < 6 || verbs[0] != PathVerb.Move || verbs[count - 1] != PathVerb.Close) return false
    if (!isFinite) return false
    val l = left
    val t = top
    val r = right
    val b = bottom
    if (!(l < r && t < b)) return false
    val pts = points
    var x = pts[0]
    var y = pts[1]
    var point = 1
    var weight = 0
    // The corners met, a bit each, in the order of [cornerOf].
    var corners = 0
    // The lines (of some length) before the first corner, and since the last one. With at most one
    // between two corners, the contour goes once around: a line runs along an edge, which only two
    // neighboring corners share.
    var leadingLines = -1
    var lines = 0
    for (i in 1 until count - 1) {
        when (verbs[i]) {
            PathVerb.Line -> {
                val nextX = pts[2 * point]
                val nextY = pts[2 * point + 1]
                point++
                if (!isAlongEdge(x, y, nextX, nextY, l, t, r, b)) return false
                if (nextX != x || nextY != y) lines++
                if (lines > 1) return false
                x = nextX
                y = nextY
            }
            PathVerb.Conic -> {
                // Kotlin/JS keeps the constant at double precision, the weights stored at float's.
                if (abs(conicWeights[weight++] - QuarterConicWeight) > 1e-6f) return false
                val cornerX = pts[2 * point]
                val cornerY = pts[2 * point + 1]
                val endX = pts[2 * point + 2]
                val endY = pts[2 * point + 3]
                point += 2
                val corner = cornerOf(cornerX, cornerY, l, t, r, b)
                if (corner < 0 || (corners and (1 shl corner)) != 0) return false
                corners = corners or (1 shl corner)
                if (leadingLines < 0) leadingLines = lines
                lines = 0
                // From the edge through the corner across to the one down, or the other way.
                val radiusX: Float
                val radiusY: Float
                if (y == cornerY && endX == cornerX) {
                    radiusX = abs(x - cornerX)
                    radiusY = abs(endY - cornerY)
                } else if (x == cornerX && endY == cornerY) {
                    radiusX = abs(endX - cornerX)
                    radiusY = abs(y - cornerY)
                } else {
                    return false
                }
                out[4 + 2 * corner] = radiusX
                out[5 + 2 * corner] = radiusY
                x = endX
                y = endY
            }
            else -> return false
        }
    }
    // Closed by a line along an edge back to the start.
    if (corners != 0b1111 || !isAlongEdge(x, y, pts[0], pts[1], l, t, r, b)) return false
    if (x != pts[0] || y != pts[1]) lines++
    if (leadingLines + lines > 1) return false
    // The corners of each edge fit along it.
    val width = r - l
    val height = b - t
    if (out[4] + out[6] > width || out[10] + out[8] > width) return false
    if (out[5] + out[11] > height || out[7] + out[9] > height) return false
    out[0] = l
    out[1] = t
    out[2] = r
    out[3] = b
    return true
}

/**
 * The corner of the bounds at ([x], [y]): top-left 0, top-right 1, bottom-right 2, bottom-left 3,
 * or -1.
 */
private fun cornerOf(x: Float, y: Float, left: Float, top: Float, right: Float, bottom: Float): Int =
    when {
        x == left && y == top -> 0
        x == right && y == top -> 1
        x == right && y == bottom -> 2
        x == left && y == bottom -> 3
        else -> -1
    }

/** Whether the line from ([x0], [y0]) to ([x1], [y1]) runs along an edge of the bounds. */
private fun isAlongEdge(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): Boolean =
    (x0 == x1 && (x0 == left || x0 == right)) || (y0 == y1 && (y0 == top || y0 == bottom))

/** X coordinate of `SkPath_RectPointIterator` point [index]. */
private fun rectPointX(index: Int, left: Float, right: Float): Float =
    if (index == 0 || index == 3) left else right

/** Y coordinate of `SkPath_RectPointIterator` point [index]. */
private fun rectPointY(index: Int, top: Float, bottom: Float): Float =
    if (index < 2) top else bottom

/** X coordinate of `SkPath_OvalPointIterator` point [index]. */
private fun ovalPointX(index: Int, left: Float, right: Float, centerX: Float): Float =
    when (index) {
        1 -> right
        3 -> left
        else -> centerX
    }

/** Y coordinate of `SkPath_OvalPointIterator` point [index]. */
private fun ovalPointY(index: Int, top: Float, bottom: Float, centerY: Float): Float =
    when (index) {
        0 -> top
        2 -> bottom
        else -> centerY
    }

/**
 * A round rect normalized like Skia's `SkRRect::setRectRadii`: sorted bounds, clamped and scaled
 * radii and the resulting type.
 */
private class NormalizedRoundRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    inputRadii: FloatArray,
) {
    var left = 0f
        private set

    var top = 0f
        private set

    var right = 0f
        private set

    var bottom = 0f
        private set

    /** x/y radii of the top-left, top-right, bottom-right and bottom-left corners. */
    val radii = FloatArray(8)

    var type = TypeEmpty
        private set

    init {
        normalize(left, top, right, bottom, inputRadii)
    }

    private fun normalize(l: Float, t: Float, r: Float, b: Float, input: FloatArray) {
        // initializeRect: check finiteness before sorting because sorting can hide NaNs.
        if (!l.isFinite() || !t.isFinite() || !r.isFinite() || !b.isFinite()) {
            return // Empty rrect at (0, 0, 0, 0).
        }
        left = minOf(l, r)
        right = maxOf(l, r)
        top = minOf(t, b)
        bottom = maxOf(t, b)
        if (!(left < right && top < bottom)) {
            type = TypeEmpty
            return
        }
        for (i in 0 until 8) {
            if (!input[i].isFinite()) {
                type = TypeRect // Devolve into a simple rect.
                return
            }
            radii[i] = input[i]
        }
        if (clampToZero(radii)) {
            type = TypeRect
            return
        }
        scaleRadii()
    }

    private fun scaleRadii() {
        // Proportionally scale down all radii to fit (W3C css3-background, "Overlapping Curves").
        var scale = 1.0
        val width = right.toDouble() - left.toDouble()
        val height = bottom.toDouble() - top.toDouble()
        scale = computeMinScale(radii[0], radii[2], width, scale)
        scale = computeMinScale(radii[3], radii[5], height, scale)
        scale = computeMinScale(radii[4], radii[6], width, scale)
        scale = computeMinScale(radii[7], radii[1], height, scale)

        flushToZero(0, 2)
        flushToZero(3, 5)
        flushToZero(4, 6)
        flushToZero(7, 1)

        if (scale < 1.0) {
            adjustRadii(width, scale, 0, 2)
            adjustRadii(height, scale, 3, 5)
            adjustRadii(width, scale, 4, 6)
            adjustRadii(height, scale, 7, 1)
        }
        // adjustRadii may set x or y to zero; set the companion to zero as well.
        clampToZero(radii)
        computeType()
    }

    private fun computeMinScale(rad1: Float, rad2: Float, limit: Double, curMin: Double): Double {
        val sum = rad1.toDouble() + rad2.toDouble()
        return if (sum > limit) minOf(curMin, limit / sum) else curMin
    }

    private fun flushToZero(a: Int, b: Int) {
        // Single precision sum, like Skia (see f32).
        val sum = f32(radii[a] + radii[b])
        if (sum == radii[a]) {
            radii[b] = 0f
        } else if (sum == radii[b]) {
            radii[a] = 0f
        }
    }

    /** Port of `SkScaleToSides::AdjustRadii`. */
    private fun adjustRadii(limit: Double, scale: Double, a: Int, b: Int) {
        radii[a] = (radii[a].toDouble() * scale).toFloat()
        radii[b] = (radii[b].toDouble() * scale).toFloat()
        // The sums are computed in single precision, like Skia.
        if (f32(radii[a] + radii[b]).toDouble() > limit) {
            val minIndex: Int
            val maxIndex: Int
            if (radii[a] > radii[b]) {
                minIndex = b
                maxIndex = a
            } else {
                minIndex = a
                maxIndex = b
            }
            val newMin = radii[minIndex]
            var newMax = f32((limit - newMin).toFloat())
            // Reduce newMax an ulp at a time until it fits.
            while (f32(newMax + newMin).toDouble() > limit) {
                newMax = nextFloatTowardsZero(newMax)
            }
            radii[maxIndex] = newMax
        }
    }

    private fun computeType() {
        var allRadiiEqual = true
        var allCornersSquare = radii[0] == 0f || radii[1] == 0f
        for (i in 1 until 4) {
            if (radii[2 * i] != 0f && radii[2 * i + 1] != 0f) {
                // If either radius is zero the corner is square so both have to be non-zero to
                // have a rounded corner.
                allCornersSquare = false
            }
            if (radii[2 * i] != radii[2 * i - 2] || radii[2 * i + 1] != radii[2 * i - 1]) {
                allRadiiEqual = false
            }
        }
        type =
            when {
                allCornersSquare -> TypeRect
                allRadiiEqual &&
                    radii[0] >= f32(f32(right - left) * 0.5f) &&
                    radii[1] >= f32(f32(bottom - top) * 0.5f) -> TypeOval
                else -> TypeComplex
            }
    }

    companion object {
        const val TypeEmpty = 0
        const val TypeRect = 1
        const val TypeOval = 2
        const val TypeComplex = 3

        /** Clamps non-positive radii to zero; returns true if all corners are square. */
        private fun clampToZero(radii: FloatArray): Boolean {
            var allCornersSquare = true
            for (i in 0 until 4) {
                if (radii[2 * i] <= 0f || radii[2 * i + 1] <= 0f) {
                    radii[2 * i] = 0f
                    radii[2 * i + 1] = 0f
                } else {
                    allCornersSquare = false
                }
            }
            return allCornersSquare
        }
    }
}
