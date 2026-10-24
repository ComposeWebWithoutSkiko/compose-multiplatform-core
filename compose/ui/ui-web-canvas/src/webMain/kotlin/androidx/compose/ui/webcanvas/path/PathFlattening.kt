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

import androidx.compose.ui.graphics.PathFillType

/**
 * Polygonal approximation of a path: every contour becomes a closed polygon (open contours are
 * implicitly closed, as for filling). Contour `c` uses the points `[start(c), start(c + 1))`.
 */
internal class FlattenedPath(
    /** Interleaved x/y coordinates of all polygons. */
    val xy: DoubleArray,
    /** Point index where each polygon starts, followed by the total point count. */
    val contourStarts: IntArray,
    /** Number of polygons. */
    val contourCount: Int,
) {
    /** Total number of points. */
    val pointCount: Int
        get() = contourStarts[contourCount]

    fun start(contour: Int): Int = contourStarts[contour]

    fun end(contour: Int): Int = contourStarts[contour + 1]
}

/** Growable storage used to build [FlattenedPath]s. */
internal class PolygonBuilder(initialPoints: Int = 64) {
    private var xy = DoubleArray(2 * initialPoints)
    private var starts = IntArray(8)
    private var contours = 0
    private var points = 0
    private var contourStart = 0

    val pointCount: Int
        get() = points

    /** Number of points in the contour being built. */
    val currentContourSize: Int
        get() = points - contourStart

    fun lastX(): Double = xy[2 * points - 2]

    fun lastY(): Double = xy[2 * points - 1]

    fun add(x: Double, y: Double) {
        if (2 * points + 2 > xy.size) xy = xy.copyOf(xy.size * 2)
        xy[2 * points] = x
        xy[2 * points + 1] = y
        points++
    }

    /** Adds ([x], [y]) unless it equals the last point of the current contour. */
    fun addIfDistinct(x: Double, y: Double) {
        if (currentContourSize > 0 && lastX() == x && lastY() == y) return
        add(x, y)
    }

    /**
     * Ends the current contour, keeping it only if it has at least [minPoints] points (a closing
     * point equal to the first one is dropped).
     */
    fun endContour(minPoints: Int = 2) {
        var size = points - contourStart
        if (
            size >= 2 &&
                xy[2 * contourStart] == xy[2 * points - 2] &&
                xy[2 * contourStart + 1] == xy[2 * points - 1]
        ) {
            points--
            size--
        }
        if (size < minPoints) {
            points = contourStart
            return
        }
        if (contours + 2 > starts.size) starts = starts.copyOf(starts.size * 2)
        starts[contours] = contourStart
        contours++
        contourStart = points
    }

    fun build(): FlattenedPath {
        // Drop a contour that was started but not ended.
        points = contourStart
        if (contours + 1 > starts.size) starts = starts.copyOf(contours + 1)
        starts[contours] = points
        return FlattenedPath(xy, starts, contours)
    }
}

/**
 * Returns the polygonal approximation of this path, cached on the snapshot. The tolerance is
 * relative to the size of the path (at most 0.1 unit).
 */
internal fun PathData.flattened(): FlattenedPath {
    flattenedCache?.let {
        return it
    }
    val extent = maxOf(right - left, bottom - top).toDouble()
    val result = flatten(this, flatteningTolerance(extent))
    flattenedCache = result
    return result
}

/** Flattening tolerance for a path of the given [extent]. */
internal fun flatteningTolerance(extent: Double): Double = (extent * 0.001).coerceIn(1e-4, 0.1)

/** Flattens [data] into polygons with a maximum deviation of [tolerance]. */
internal fun flatten(
    data: PathData,
    tolerance: Double,
    minPointsPerContour: Int = 2,
): FlattenedPath {
    val builder = PolygonBuilder(maxOf(16, data.pointCount * 2))
    val pts = data.points
    var p = 0
    var w = 0
    var inContour = false
    var quads: FloatArray? = null
    for (i in 0 until data.verbCount) {
        when (data.verbs[i]) {
            PathVerb.Move -> {
                if (inContour) builder.endContour(minPointsPerContour)
                builder.add(pts[2 * p].toDouble(), pts[2 * p + 1].toDouble())
                inContour = true
                p += 1
            }
            PathVerb.Line -> {
                builder.addIfDistinct(pts[2 * p].toDouble(), pts[2 * p + 1].toDouble())
                p += 1
            }
            PathVerb.Quad -> {
                flattenQuad(
                    builder,
                    pts[2 * p - 2].toDouble(),
                    pts[2 * p - 1].toDouble(),
                    pts[2 * p].toDouble(),
                    pts[2 * p + 1].toDouble(),
                    pts[2 * p + 2].toDouble(),
                    pts[2 * p + 3].toDouble(),
                    tolerance,
                )
                p += 2
            }
            PathVerb.Conic -> {
                val weight = data.conicWeights[w++]
                val x0 = pts[2 * p - 2]
                val y0 = pts[2 * p - 1]
                val x1 = pts[2 * p]
                val y1 = pts[2 * p + 1]
                val x2 = pts[2 * p + 2]
                val y2 = pts[2 * p + 3]
                val pow2 = computeConicQuadPow2(x0, y0, x1, y1, x2, y2, weight, tolerance.toFloat())
                val buffer = quads ?: FloatArray(2 * MaxConicQuadPoints).also { quads = it }
                val count = chopConicIntoQuadsPow2(x0, y0, x1, y1, x2, y2, weight, pow2, buffer, 0)
                for (q in 0 until count) {
                    val o = 4 * q
                    flattenQuad(
                        builder,
                        buffer[o].toDouble(),
                        buffer[o + 1].toDouble(),
                        buffer[o + 2].toDouble(),
                        buffer[o + 3].toDouble(),
                        buffer[o + 4].toDouble(),
                        buffer[o + 5].toDouble(),
                        tolerance,
                    )
                }
                // Make sure the contour ends exactly on the conic end point.
                builder.addIfDistinct(x2.toDouble(), y2.toDouble())
                p += 2
            }
            PathVerb.Cubic -> {
                flattenCubic(
                    builder,
                    pts[2 * p - 2].toDouble(),
                    pts[2 * p - 1].toDouble(),
                    pts[2 * p].toDouble(),
                    pts[2 * p + 1].toDouble(),
                    pts[2 * p + 2].toDouble(),
                    pts[2 * p + 3].toDouble(),
                    pts[2 * p + 4].toDouble(),
                    pts[2 * p + 5].toDouble(),
                    tolerance,
                )
                p += 3
            }
            PathVerb.Close -> {
                if (inContour) builder.endContour(minPointsPerContour)
                inContour = false
            }
        }
    }
    if (inContour) builder.endContour(minPointsPerContour)
    return builder.build()
}

private fun flattenQuad(
    builder: PolygonBuilder,
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    tolerance: Double,
) {
    val n = quadSegmentCount(x0, y0, x1, y1, x2, y2, tolerance)
    for (i in 1 until n) {
        val t = i.toDouble() / n
        val mt = 1.0 - t
        val a = mt * mt
        val b = 2.0 * mt * t
        val c = t * t
        builder.addIfDistinct(a * x0 + b * x1 + c * x2, a * y0 + b * y1 + c * y2)
    }
    builder.addIfDistinct(x2, y2)
}

private fun flattenCubic(
    builder: PolygonBuilder,
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    x3: Double,
    y3: Double,
    tolerance: Double,
) {
    val n = cubicSegmentCount(x0, y0, x1, y1, x2, y2, x3, y3, tolerance)
    for (i in 1 until n) {
        val t = i.toDouble() / n
        val mt = 1.0 - t
        val a = mt * mt * mt
        val b = 3.0 * mt * mt * t
        val c = 3.0 * mt * t * t
        val d = t * t * t
        builder.addIfDistinct(a * x0 + b * x1 + c * x2 + d * x3, a * y0 + b * y1 + c * y2 + d * y3)
    }
    builder.addIfDistinct(x3, y3)
}

/**
 * Returns true if the point ([x], [y]) is inside this path according to its fill type. Points on
 * the outline are considered inside, like Skia's `SkPath::contains`. Curves are evaluated on a
 * cached polygonal approximation (maximum error 0.1 unit, less for small paths).
 */
internal fun PathData.contains(x: Float, y: Float): Boolean {
    if (isEmpty || !isFinite) return false
    if (x < left || x > right || y < top || y > bottom) return false
    if (x.isNaN() || y.isNaN()) return false
    return flattened().contains(x.toDouble(), y.toDouble(), fillType)
}

/** Point-in-polygon test with the given fill rule; points on an edge count as inside. */
internal fun FlattenedPath.contains(px: Double, py: Double, fillType: PathFillType): Boolean {
    var winding = 0
    for (c in 0 until contourCount) {
        val start = start(c)
        val end = end(c)
        var ax = xy[2 * (end - 1)]
        var ay = xy[2 * (end - 1) + 1]
        for (i in start until end) {
            val bx = xy[2 * i]
            val by = xy[2 * i + 1]
            val cross = (bx - ax) * (py - ay) - (px - ax) * (by - ay)
            if (
                cross == 0.0 &&
                    px >= minOf(ax, bx) &&
                    px <= maxOf(ax, bx) &&
                    py >= minOf(ay, by) &&
                    py <= maxOf(ay, by)
            ) {
                return true // On the edge.
            }
            if (ay <= py) {
                if (by > py && cross > 0.0) winding++
            } else {
                if (by <= py && cross < 0.0) winding--
            }
            ax = bx
            ay = by
        }
    }
    return if (fillType == PathFillType.EvenOdd) (winding and 1) != 0 else winding != 0
}
