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

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import kotlin.math.abs
import kotlin.math.max

// Cheap shape detection, so that the renderer can use the native Canvas2D primitives (fillRect,
// ellipse, roundRect) instead of a Path2D. The detected shapes are closed single contours; the
// start point and the direction of the contour are not reported, so callers that depend on them
// (e.g. dashing) should use the path.

/**
 * Returns the rectangle if this path is a single closed, axis-aligned rectangle with a non-zero
 * area (as produced by `addRect`, in any direction and from any corner), null otherwise.
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
 * Returns the bounds of the ellipse if this path is a single closed ellipse made of 4 quarter
 * conics (as produced by `addOval` or a full-circle `addArc`, in any direction and from any of the
 * 4 start points), null otherwise.
 */
internal fun PathData.asOval(): Rect? {
    if (verbCount != 6 || verbs[0] != PathVerb.Move || verbs[5] != PathVerb.Close) return null
    for (i in 1..4) {
        if (verbs[i] != PathVerb.Conic) return null
    }
    for (i in 0 until 4) {
        if (!isQuarterWeight(conicWeights[i])) return null
    }
    if (!isFinite) return null
    val l = left
    val t = top
    val r = right
    val b = bottom
    if (!(l < r && t < b)) return null
    val eps = shapeEpsilon(l, t, r, b)
    val cx = midpoint(l, r)
    val cy = midpoint(t, b)
    val pts = points
    // The last end point closes the ellipse.
    if (!near(pts[16], pts[0], eps) || !near(pts[17], pts[1], eps)) return null
    var visited = 0
    for (i in 0 until 4) {
        val sx = pts[4 * i]
        val sy = pts[4 * i + 1]
        val ctrlX = pts[4 * i + 2]
        val ctrlY = pts[4 * i + 3]
        val ex = pts[4 * i + 4]
        val ey = pts[4 * i + 5]
        val start = ovalExtremeIndex(sx, sy, l, t, r, b, cx, cy, eps)
        val end = ovalExtremeIndex(ex, ey, l, t, r, b, cx, cy, eps)
        if (start < 0 || end < 0 || (start + end) % 2 == 0) return null // Not adjacent.
        visited = visited or (1 shl start)
        // The control point is the corner between the two extremes.
        val cornerX = if (start % 2 == 1) sx else ex
        val cornerY = if (start % 2 == 0) sy else ey
        if (!near(ctrlX, cornerX, eps) || !near(ctrlY, cornerY, eps)) return null
    }
    if (visited != 0xF) return null
    return Rect(l, t, r, b)
}

/**
 * Returns the round rect if this path is a single closed rounded rectangle made of 4 quarter conics
 * joined by axis-aligned lines (as produced by `addRoundRect` with at least one rounded corner, in
 * any direction and from any start point), null otherwise. Rectangles and ellipses are reported by
 * [asRect] and [asOval] instead.
 */
internal fun PathData.asRoundRect(): RoundRect? {
    val count = verbCount
    if (count < 6 || count > 10) return null
    if (verbs[0] != PathVerb.Move || verbs[count - 1] != PathVerb.Close) return null
    var conics = 0
    var lines = 0
    for (i in 1 until count - 1) {
        when (verbs[i]) {
            PathVerb.Conic -> conics++
            PathVerb.Line -> lines++
            else -> return null
        }
    }
    // Without lines this is an oval (see asOval).
    if (conics != 4 || lines == 0) return null
    if (!isFinite) return null
    val l = left
    val t = top
    val r = right
    val b = bottom
    if (!(l < r && t < b)) return null
    val eps = shapeEpsilon(l, t, r, b)
    // Radii per corner: top-left, top-right, bottom-right, bottom-left (x, y).
    val radii = FloatArray(8)
    var corners = 0
    val pts = points
    var curX = pts[0]
    var curY = pts[1]
    var p = 1
    var w = 0
    for (i in 1 until count - 1) {
        if (verbs[i] == PathVerb.Line) {
            val x = pts[2 * p]
            val y = pts[2 * p + 1]
            if (!onSameSide(curX, curY, x, y, l, t, r, b, eps)) return null
            curX = x
            curY = y
            p += 1
        } else {
            if (!isQuarterWeight(conicWeights[w++])) return null
            val ctrlX = pts[2 * p]
            val ctrlY = pts[2 * p + 1]
            val ex = pts[2 * p + 2]
            val ey = pts[2 * p + 3]
            val cornerLeft = near(ctrlX, l, eps)
            val cornerTop = near(ctrlY, t, eps)
            if (!cornerLeft && !near(ctrlX, r, eps)) return null
            if (!cornerTop && !near(ctrlY, b, eps)) return null
            // One end point lies on the vertical side of the corner, the other on the horizontal.
            val valid =
                (near(curX, ctrlX, eps) && near(ey, ctrlY, eps)) ||
                    (near(curY, ctrlY, eps) && near(ex, ctrlX, eps))
            if (!valid) return null
            val corner =
                when {
                    cornerLeft && cornerTop -> 0
                    !cornerLeft && cornerTop -> 1
                    !cornerLeft -> 2
                    else -> 3
                }
            if (corners and (1 shl corner) != 0) return null
            corners = corners or (1 shl corner)
            radii[2 * corner] = max(abs(curX - ctrlX), abs(ex - ctrlX))
            radii[2 * corner + 1] = max(abs(curY - ctrlY), abs(ey - ctrlY))
            curX = ex
            curY = ey
            p += 2
        }
    }
    // The implicit closing line.
    if (!onSameSide(curX, curY, pts[0], pts[1], l, t, r, b, eps)) return null
    if (corners != 0xF) return null
    // The corners must not overlap.
    val width = r - l
    val height = b - t
    if (radii[0] + radii[2] > width + eps || radii[6] + radii[4] > width + eps) return null
    if (radii[1] + radii[7] > height + eps || radii[3] + radii[5] > height + eps) return null
    return RoundRect(
        l,
        t,
        r,
        b,
        topLeftCornerRadius = CornerRadius(radii[0], radii[1]),
        topRightCornerRadius = CornerRadius(radii[2], radii[3]),
        bottomRightCornerRadius = CornerRadius(radii[4], radii[5]),
        bottomLeftCornerRadius = CornerRadius(radii[6], radii[7]),
    )
}

private fun isQuarterWeight(weight: Float): Boolean = abs(weight - QuarterConicWeight) <= 1e-5f

private fun shapeEpsilon(l: Float, t: Float, r: Float, b: Float): Float =
    max(max(abs(l), abs(r)), max(abs(t), abs(b))).coerceAtLeast(1f) * 1e-5f

private fun near(a: Float, b: Float, eps: Float): Boolean = abs(a - b) <= eps

/**
 * Index of the extreme point of the ellipse at ([x], [y]): 0 top, 1 right, 2 bottom, 3 left (the
 * order of Skia's `SkPath_OvalPointIterator`), or -1.
 */
private fun ovalExtremeIndex(
    x: Float,
    y: Float,
    l: Float,
    t: Float,
    r: Float,
    b: Float,
    cx: Float,
    cy: Float,
    eps: Float,
): Int =
    when {
        near(x, cx, eps) && near(y, t, eps) -> 0
        near(x, r, eps) && near(y, cy, eps) -> 1
        near(x, cx, eps) && near(y, b, eps) -> 2
        near(x, l, eps) && near(y, cy, eps) -> 3
        else -> -1
    }

/** True if both points lie on the same side of the rectangle. */
private fun onSameSide(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    l: Float,
    t: Float,
    r: Float,
    b: Float,
    eps: Float,
): Boolean =
    (near(x0, l, eps) && near(x1, l, eps)) ||
        (near(x0, r, eps) && near(x1, r, eps)) ||
        (near(y0, t, eps) && near(y1, t, eps)) ||
        (near(y0, b, eps) && near(y1, b, eps))
