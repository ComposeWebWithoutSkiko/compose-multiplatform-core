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

import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.webcanvas.path.PathData
import androidx.compose.ui.webcanvas.path.PathVerb
import androidx.compose.ui.webcanvas.path.WebPath
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A projective transformation of the plane, which the Canvas 2D API can't express:
 * `(x, y) -> (X / W, Y / W)` with `X = m00 x + m01 y + m02`, `Y = m10 x + m11 y + m12` and
 * `W = m20 x + m21 y + m22`.
 *
 * Points with `W` close to zero or negative are behind the camera: they aren't visible.
 */
internal class Projective {
    var m00 = 1.0
    var m01 = 0.0
    var m02 = 0.0
    var m10 = 0.0
    var m11 = 1.0
    var m12 = 0.0
    var m20 = 0.0
    var m21 = 0.0
    var m22 = 1.0

    /** Sets this to the 2D part of a Compose 4x4 matrix (which maps row vectors). */
    fun setFrom(matrix: Matrix): Projective {
        val v = matrix.values
        m00 = v[0].toDouble()
        m01 = v[4].toDouble()
        m02 = v[12].toDouble()
        m10 = v[1].toDouble()
        m11 = v[5].toDouble()
        m12 = v[13].toDouble()
        m20 = v[3].toDouble()
        m21 = v[7].toDouble()
        m22 = v[15].toDouble()
        return this
    }

    fun set(other: Projective): Projective {
        m00 = other.m00
        m01 = other.m01
        m02 = other.m02
        m10 = other.m10
        m11 = other.m11
        m12 = other.m12
        m20 = other.m20
        m21 = other.m21
        m22 = other.m22
        return this
    }

    /** Applies [affine] after this transformation. */
    fun postConcat(affine: Affine): Projective {
        val a = affine.a.toDouble()
        val b = affine.b.toDouble()
        val c = affine.c.toDouble()
        val d = affine.d.toDouble()
        val e = affine.e.toDouble()
        val f = affine.f.toDouble()
        val n00 = a * m00 + c * m10 + e * m20
        val n01 = a * m01 + c * m11 + e * m21
        val n02 = a * m02 + c * m12 + e * m22
        val n10 = b * m00 + d * m10 + f * m20
        val n11 = b * m01 + d * m11 + f * m21
        val n12 = b * m02 + d * m12 + f * m22
        m00 = n00
        m01 = n01
        m02 = n02
        m10 = n10
        m11 = n11
        m12 = n12
        return this
    }

    /** Applies `(x, y) -> (sx * x + tx, sy * y + ty)` before this transformation. */
    fun preScaleTranslate(sx: Double, sy: Double, tx: Double, ty: Double): Projective {
        m02 += m00 * tx + m01 * ty
        m12 += m10 * tx + m11 * ty
        m22 += m20 * tx + m21 * ty
        m00 *= sx
        m10 *= sx
        m20 *= sx
        m01 *= sy
        m11 *= sy
        m21 *= sy
        return this
    }

    fun mapW(x: Double, y: Double): Double = m20 * x + m21 * y + m22

    fun mapX(x: Double, y: Double): Double = (m00 * x + m01 * y + m02) / mapW(x, y)

    fun mapY(x: Double, y: Double): Double = (m10 * x + m11 * y + m12) / mapW(x, y)

    /**
     * Computes the bounds of the visible part of the image of the rectangle, i.e. the part in
     * front of the camera, into [out] (left, top, right, bottom). Returns `false` if no part of
     * the rectangle is visible.
     */
    fun mapRectBounds(left: Float, top: Float, right: Float, bottom: Float, out: FloatArray): Boolean {
        val xs = corners
        xs[0] = left.toDouble()
        xs[1] = top.toDouble()
        xs[2] = right.toDouble()
        xs[3] = top.toDouble()
        xs[4] = right.toDouble()
        xs[5] = bottom.toDouble()
        xs[6] = left.toDouble()
        xs[7] = bottom.toDouble()
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        // Clip the polygon to the half-plane in front of the camera (Sutherland-Hodgman with a
        // single plane), and bound the projection of what remains.
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val xi = xs[2 * i]
            val yi = xs[2 * i + 1]
            val xj = xs[2 * j]
            val yj = xs[2 * j + 1]
            val wi = mapW(xi, yi)
            val wj = mapW(xj, yj)
            if (wi >= MinW) {
                val x = mapX(xi, yi)
                val y = mapY(xi, yi)
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
            }
            if ((wi >= MinW) != (wj >= MinW)) {
                val t = (wi - MinW) / (wi - wj)
                val x = xi + (xj - xi) * t
                val y = yi + (yj - yi) * t
                val px = mapX(x, y)
                val py = mapY(x, y)
                minX = min(minX, px)
                minY = min(minY, py)
                maxX = max(maxX, px)
                maxY = max(maxY, py)
            }
        }
        if (minX > maxX || minY > maxY) return false
        out[0] = minX.toFloat()
        out[1] = minY.toFloat()
        out[2] = maxX.toFloat()
        out[3] = maxY.toFloat()
        return true
    }

    /**
     * The largest magnification of the rectangle's edges by this transformation, or `NaN` if
     * none of its edges is entirely visible.
     */
    fun maxEdgeScale(left: Float, top: Float, right: Float, bottom: Float): Float {
        val l = left.toDouble()
        val t = top.toDouble()
        val r = right.toDouble()
        val b = bottom.toDouble()
        var scale = Double.NaN
        fun edge(x0: Double, y0: Double, x1: Double, y1: Double) {
            if (mapW(x0, y0) < MinW || mapW(x1, y1) < MinW) return
            val length = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
            if (length <= 0.0) return
            val dx = mapX(x1, y1) - mapX(x0, y0)
            val dy = mapY(x1, y1) - mapY(x0, y0)
            val s = sqrt(dx * dx + dy * dy) / length
            if (scale.isNaN() || s > scale) scale = s
        }
        edge(l, t, r, t)
        edge(r, t, r, b)
        edge(r, b, l, b)
        edge(l, b, l, t)
        return scale.toFloat()
    }

    /**
     * Returns the image of [path], or `null` if a part of it is behind the camera. Curves are
     * approximated by mapping their control points.
     */
    fun mapPath(path: PathData): PathData? {
        val result = WebPath()
        result.fillType = path.fillType
        val points = path.points
        var p = 0
        var w = 0
        fun x(i: Int): Float? {
            val px = points[i].toDouble()
            val py = points[i + 1].toDouble()
            if (mapW(px, py) < MinW) return null
            return mapX(px, py).toFloat()
        }
        fun y(i: Int): Float = mapY(points[i].toDouble(), points[i + 1].toDouble()).toFloat()
        for (v in 0 until path.verbCount) {
            when (path.verbs[v]) {
                PathVerb.Move -> {
                    result.moveTo(x(p) ?: return null, y(p))
                    p += 2
                }
                PathVerb.Line -> {
                    result.lineTo(x(p) ?: return null, y(p))
                    p += 2
                }
                PathVerb.Quad -> {
                    result.quadraticTo(x(p) ?: return null, y(p), x(p + 2) ?: return null, y(p + 2))
                    p += 4
                }
                PathVerb.Conic -> {
                    result.conicTo(
                        x(p) ?: return null,
                        y(p),
                        x(p + 2) ?: return null,
                        y(p + 2),
                        path.conicWeights[w++],
                    )
                    p += 4
                }
                PathVerb.Cubic -> {
                    result.cubicTo(
                        x(p) ?: return null,
                        y(p),
                        x(p + 2) ?: return null,
                        y(p + 2),
                        x(p + 4) ?: return null,
                        y(p + 4),
                    )
                    p += 6
                }
                PathVerb.Close -> result.close()
            }
        }
        return result.snapshot()
    }

    private val corners = DoubleArray(8)

    private companion object {
        /** Points closer to the camera plane than this are treated as behind it (like Skia). */
        const val MinW = 1.0 / 16384
    }
}
