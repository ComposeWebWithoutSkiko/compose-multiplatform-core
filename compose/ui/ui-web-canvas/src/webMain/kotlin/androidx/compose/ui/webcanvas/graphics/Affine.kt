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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 2D affine transformation, in the layout of the Canvas 2D `setTransform(a, b, c, d, e, f)`:
 * ```
 * x' = a * x + c * y + e
 * y' = b * x + d * y + f
 * ```
 */
internal class Affine(
    var a: Float = 1f,
    var b: Float = 0f,
    var c: Float = 0f,
    var d: Float = 1f,
    var e: Float = 0f,
    var f: Float = 0f,
) {
    val isIdentity: Boolean
        get() = a == 1f && b == 0f && c == 0f && d == 1f && e == 0f && f == 0f

    /** Whether the transformation only translates and scales, so it keeps rectangles axis-aligned. */
    val isAxisAligned: Boolean
        get() = b == 0f && c == 0f

    /**
     * Whether the transformation is a similarity: a combination of translation, rotation, uniform
     * scale and reflection, which maps circles to circles.
     */
    val isSimilarity: Boolean
        get() {
            val tolerance = 1e-4f * max(1f, max(abs(a), abs(b)))
            return (abs(a - d) <= tolerance && abs(b + c) <= tolerance) ||
                (abs(a + d) <= tolerance && abs(b - c) <= tolerance)
        }

    val determinant: Float
        get() = a * d - b * c

    /** The geometric mean of the scale factors: the scale a unit length is approximately drawn at. */
    val averageScale: Float
        get() = sqrt(abs(determinant))

    fun set(other: Affine): Affine {
        a = other.a
        b = other.b
        c = other.c
        d = other.d
        e = other.e
        f = other.f
        return this
    }

    fun set(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float): Affine {
        this.a = a
        this.b = b
        this.c = c
        this.d = d
        this.e = e
        this.f = f
        return this
    }

    fun reset(): Affine = set(1f, 0f, 0f, 1f, 0f, 0f)

    fun copy(): Affine = Affine(a, b, c, d, e, f)

    fun contentEquals(other: Affine): Boolean =
        a == other.a && b == other.b && c == other.c && d == other.d && e == other.e &&
            f == other.f

    /** this = this * translate(dx, dy) */
    fun preTranslate(dx: Float, dy: Float) {
        e += a * dx + c * dy
        f += b * dx + d * dy
    }

    /** this = this * scale(sx, sy) */
    fun preScale(sx: Float, sy: Float) {
        a *= sx
        b *= sx
        c *= sy
        d *= sy
    }

    /** this = this * rotate(radians) */
    fun preRotate(radians: Float) {
        val cos = cos(radians)
        val sin = sin(radians)
        val na = a * cos + c * sin
        val nb = b * cos + d * sin
        val nc = -a * sin + c * cos
        val nd = -b * sin + d * cos
        a = na
        b = nb
        c = nc
        d = nd
    }

    /** this = this * skew(sx, sy), with the tangents of the skew angles. */
    fun preSkew(sx: Float, sy: Float) {
        val na = a + c * sy
        val nb = b + d * sy
        val nc = a * sx + c
        val nd = b * sx + d
        a = na
        b = nb
        c = nc
        d = nd
    }

    /** this = this * other */
    fun preConcat(other: Affine) {
        val na = a * other.a + c * other.b
        val nb = b * other.a + d * other.b
        val nc = a * other.c + c * other.d
        val nd = b * other.c + d * other.d
        val ne = a * other.e + c * other.f + e
        val nf = b * other.e + d * other.f + f
        a = na
        b = nb
        c = nc
        d = nd
        e = ne
        f = nf
    }

    /** Inverts this transformation in place. Returns `false` (and keeps it) if it's singular. */
    fun invert(): Boolean {
        val det = determinant
        if (det == 0f || det.isNaN()) return false
        val inv = 1f / det
        val na = d * inv
        val nb = -b * inv
        val nc = -c * inv
        val nd = a * inv
        val ne = (c * f - d * e) * inv
        val nf = (b * e - a * f) * inv
        a = na
        b = nb
        c = nc
        d = nd
        e = ne
        f = nf
        return true
    }

    fun mapX(x: Float, y: Float): Float = a * x + c * y + e

    fun mapY(x: Float, y: Float): Float = b * x + d * y + f

    /**
     * Maps the rectangle and stores the axis-aligned bounds of the result in [out] as
     * (left, top, right, bottom).
     */
    fun mapRect(left: Float, top: Float, right: Float, bottom: Float, out: FloatArray) {
        if (isAxisAligned) {
            val x0 = a * left + e
            val x1 = a * right + e
            val y0 = d * top + f
            val y1 = d * bottom + f
            out[0] = min(x0, x1)
            out[1] = min(y0, y1)
            out[2] = max(x0, x1)
            out[3] = max(y0, y1)
            return
        }
        val x0 = mapX(left, top)
        val y0 = mapY(left, top)
        val x1 = mapX(right, top)
        val y1 = mapY(right, top)
        val x2 = mapX(right, bottom)
        val y2 = mapY(right, bottom)
        val x3 = mapX(left, bottom)
        val y3 = mapY(left, bottom)
        out[0] = min(min(x0, x1), min(x2, x3))
        out[1] = min(min(y0, y1), min(y2, y3))
        out[2] = max(max(x0, x1), max(x2, x3))
        out[3] = max(max(y0, y1), max(y2, y3))
    }

    override fun toString(): String = "Affine($a, $b, $c, $d, $e, $f)"
}

/**
 * Converts a Compose 4x4 [Matrix] to a 2D affine transformation, dropping the components that
 * only affect z and the perspective row (the Canvas 2D API can't express perspective).
 */
internal fun Matrix.toAffine(out: Affine = Affine()): Affine {
    val v = values
    val w = v[15]
    val scale = if (w != 0f && w != 1f) 1f / w else 1f
    return out.set(
        v[0] * scale,
        v[1] * scale,
        v[4] * scale,
        v[5] * scale,
        v[12] * scale,
        v[13] * scale,
    )
}

/** Whether the matrix has a perspective component, which the Canvas 2D API can't express. */
internal fun Matrix.hasPerspective(): Boolean {
    val v = values
    return v[3] != 0f || v[7] != 0f || (v[15] != 1f && v[15] != 0f)
}
