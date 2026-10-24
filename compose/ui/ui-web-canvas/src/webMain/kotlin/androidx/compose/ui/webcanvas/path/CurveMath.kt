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

import kotlin.math.abs
import kotlin.math.sqrt

// Curve helpers shared by the path classes of this package. Most functions are ports of the
// corresponding Skia routines (SkGeometry.cpp) so that the results match the skiko backend.
//
// Curves are passed as flat coordinates; functions that produce several points write them as
// interleaved x/y pairs into a caller supplied FloatArray.

/** Skia's `SK_ScalarNearlyZero`. */
internal const val ScalarNearlyZero: Float = 1f / (1 shl 12)

/** Weight Skia uses for the conics of a quarter ellipse (`SK_ScalarRoot2Over2`). */
internal const val QuarterConicWeight: Float = 0.70710677f

/** Skia's `SkConic::kMaxConicToQuadPOW2`. */
internal const val MaxConicToQuadPow2: Int = 5

/** Number of points written by [chopConicIntoQuadsPow2] for the maximal subdivision. */
internal const val MaxConicQuadPoints: Int = 2 * (1 shl MaxConicToQuadPow2) + 1

internal fun nearlyEqual(a: Float, b: Float): Boolean = abs(a - b) <= ScalarNearlyZero

private val F32Buffer = FloatArray(1)

/**
 * Rounds [value] to single precision.
 *
 * Kotlin/JS represents `Float` as a JS number and performs `Float` arithmetic in double precision,
 * while some Skia algorithms rely on single precision rounding for exact comparisons (e.g. `sin(PI
 * / 2)` must round to exactly 1). Storing into a `FloatArray` (a `Float32Array` on JS) restores
 * that rounding; on Wasm this is a no-op.
 */
internal fun f32(value: Float): Float {
    F32Buffer[0] = value
    return F32Buffer[0]
}

/** Returns the bit pattern-preserving "next float towards zero" of a non-negative [value]. */
internal fun nextFloatTowardsZero(value: Float): Float =
    if (value == 0f || !value.isFinite()) value else Float.fromBits(value.toRawBits() - 1)

/**
 * Returns the number of times (as a power of 2) a conic must be subdivided so that the quadratic
 * approximation stays within [tolerance] (Skia's `SkConic::computeQuadPOW2`).
 *
 * A non-positive or non-finite tolerance or non-finite points produce a single quadratic, matching
 * the reference implementation of `PathIterator`.
 */
internal fun computeConicQuadPow2(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    weight: Float,
    tolerance: Float,
): Int {
    if (
        tolerance <= 0f ||
            !tolerance.isFinite() ||
            !x0.isFinite() ||
            !y0.isFinite() ||
            !x1.isFinite() ||
            !y1.isFinite() ||
            !x2.isFinite() ||
            !y2.isFinite()
    ) {
        return 0
    }
    val a = weight - 1f
    val k = a / (4f * (2f + a))
    val x = k * (x0 - 2f * x1 + x2)
    val y = k * (y0 - 2f * y1 + y2)
    var error = sqrt(x * x + y * y)
    var pow2 = 0
    while (pow2 < MaxConicToQuadPow2) {
        if (error <= tolerance) break
        error *= 0.25f
        pow2++
    }
    return pow2
}

/**
 * Approximates the conic (x0, y0) (x1, y1) (x2, y2) with [weight] by `2^pow2` quadratic Béziers
 * (Skia's `SkConic::chopIntoQuadsPOW2`).
 *
 * Writes `2 * quadCount + 1` points (start point, then control/end point pairs) into [dst] starting
 * at float index [dstOffset] and returns the number of quadratics.
 */
internal fun chopConicIntoQuadsPow2(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    weight: Float,
    pow2: Int,
    dst: FloatArray,
    dstOffset: Int,
): Int {
    var level = pow2.coerceIn(0, MaxConicToQuadPow2)
    dst[dstOffset] = x0
    dst[dstOffset + 1] = y0
    var handled = false
    if (level == MaxConicToQuadPow2) {
        // An extreme weight would generate many quads: check whether the first chop already
        // degenerates into a pair of lines.
        val halves = FloatArray(10)
        chopConicAtHalf(x0, y0, x1, y1, x2, y2, weight, halves, 0)
        if (
            halves[2] == halves[4] &&
                halves[3] == halves[5] &&
                halves[4] == halves[6] &&
                halves[5] == halves[7]
        ) {
            dst[dstOffset + 2] = halves[2]
            dst[dstOffset + 3] = halves[3]
            dst[dstOffset + 4] = halves[2]
            dst[dstOffset + 5] = halves[3]
            dst[dstOffset + 6] = halves[2]
            dst[dstOffset + 7] = halves[3]
            dst[dstOffset + 8] = halves[8]
            dst[dstOffset + 9] = halves[9]
            level = 1
            handled = true
        }
    }
    if (!handled) {
        subdivideConic(x0, y0, x1, y1, x2, y2, weight, level, dst, dstOffset + 2)
    }
    val quadCount = 1 shl level
    val pointCount = 2 * quadCount + 1
    var allFinite = true
    for (i in 0 until pointCount * 2) {
        if (!dst[dstOffset + i].isFinite()) {
            allFinite = false
            break
        }
    }
    if (!allFinite) {
        // Pin ourselves to the middle of the hull, as Skia does.
        for (i in 1 until pointCount - 1) {
            dst[dstOffset + 2 * i] = x1
            dst[dstOffset + 2 * i + 1] = y1
        }
    }
    return quadCount
}

private fun between(a: Float, b: Float, c: Float): Boolean = (a - b) * (c - b) <= 0f

/** Port of Skia's recursive `subdivide()` used by `SkConic::chopIntoQuadsPOW2`. */
private fun subdivideConic(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    weight: Float,
    level: Int,
    dst: FloatArray,
    offset: Int,
): Int {
    if (level == 0) {
        dst[offset] = x1
        dst[offset + 1] = y1
        dst[offset + 2] = x2
        dst[offset + 3] = y2
        return offset + 4
    }
    val h = FloatArray(10)
    val newWeight = chopConicAtHalf(x0, y0, x1, y1, x2, y2, weight, h, 0)
    val startY = y0
    val endY = y2
    if (between(startY, y1, endY)) {
        // If the input is monotonic and the output is not, the scan converter hangs.
        // Ensure that the chopped conics maintain their y-order.
        val midY = h[5]
        if (!between(startY, midY, endY)) {
            val closerY = if (abs(midY - startY) < abs(midY - endY)) startY else endY
            h[5] = closerY
        }
        if (!between(startY, h[3], h[5])) {
            h[3] = startY
        }
        if (!between(h[5], h[7], endY)) {
            h[7] = endY
        }
    }
    val next = subdivideConic(h[0], h[1], h[2], h[3], h[4], h[5], newWeight, level - 1, dst, offset)
    return subdivideConic(h[4], h[5], h[6], h[7], h[8], h[9], newWeight, level - 1, dst, next)
}

/**
 * Splits a conic at t = 0.5 (Skia's `SkConic::chop`). Writes the 5 points of the two halves (p0,
 * c0, mid, c1, p2) into [dst] at [offset] and returns the weight shared by both halves.
 */
internal fun chopConicAtHalf(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    weight: Float,
    dst: FloatArray,
    offset: Int,
): Float {
    val scale = 1f / (1f + weight)
    val newWeight = sqrt(0.5f + weight * 0.5f)
    val wx1 = weight * x1
    val wy1 = weight * y1
    var mx = (x0 + 2f * wx1 + x2) * scale * 0.5f
    var my = (y0 + 2f * wy1 + y2) * scale * 0.5f
    if (!mx.isFinite() || !my.isFinite()) {
        val w2 = weight.toDouble() * 2.0
        val scaleHalf = 1.0 / (1.0 + weight) * 0.5
        mx = ((x0 + w2 * x1 + x2) * scaleHalf).toFloat()
        my = ((y0 + w2 * y1 + y2) * scaleHalf).toFloat()
    }
    dst[offset] = x0
    dst[offset + 1] = y0
    dst[offset + 2] = (x0 + wx1) * scale
    dst[offset + 3] = (y0 + wy1) * scale
    dst[offset + 4] = mx
    dst[offset + 5] = my
    dst[offset + 6] = (wx1 + x2) * scale
    dst[offset + 7] = (wy1 + y2) * scale
    dst[offset + 8] = x2
    dst[offset + 9] = y2
    return newWeight
}

/** Evaluates the conic at [t] (Skia's `SkConic::evalAt`), writing x/y into [out] at [offset]. */
internal fun evalConic(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    weight: Float,
    t: Float,
    out: FloatArray,
    offset: Int,
) {
    val p1wx = x1 * weight
    val p1wy = y1 * weight
    val numAx = x2 - 2f * p1wx + x0
    val numAy = y2 - 2f * p1wy + y0
    val numBx = 2f * (p1wx - x0)
    val numBy = 2f * (p1wy - y0)
    val denB = 2f * (weight - 1f)
    val denA = -denB
    val denom = (denA * t + denB) * t + 1f
    out[offset] = ((numAx * t + numBx) * t + x0) / denom
    out[offset + 1] = ((numAy * t + numBy) * t + y0) / denom
}

/** Unnormalized conic tangent at [t] (Skia's `SkConic::evalTangentAt`). */
internal fun evalConicTangent(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    weight: Float,
    t: Float,
    out: FloatArray,
    offset: Int,
) {
    if ((t == 0f && x0 == x1 && y0 == y1) || (t == 1f && x1 == x2 && y1 == y2)) {
        out[offset] = x2 - x0
        out[offset + 1] = y2 - y0
        return
    }
    val p20x = x2 - x0
    val p20y = y2 - y0
    val p10x = x1 - x0
    val p10y = y1 - y0
    val cx = weight * p10x
    val cy = weight * p10y
    val ax = weight * p20x - p20x
    val ay = weight * p20y - p20y
    val bx = p20x - cx - cx
    val by = p20y - cy - cy
    out[offset] = (ax * t + bx) * t + cx
    out[offset + 1] = (ay * t + by) * t + cy
}

/** Evaluates a quadratic Bézier at [t] (Skia's `SkEvalQuadAt`). */
internal fun evalQuad(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    t: Float,
    out: FloatArray,
    offset: Int,
) {
    val ax = x2 - 2f * x1 + x0
    val ay = y2 - 2f * y1 + y0
    val bx = 2f * (x1 - x0)
    val by = 2f * (y1 - y0)
    out[offset] = (ax * t + bx) * t + x0
    out[offset + 1] = (ay * t + by) * t + y0
}

/** Unnormalized quadratic tangent at [t] (Skia's `SkEvalQuadTangentAt`). */
internal fun evalQuadTangent(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    t: Float,
    out: FloatArray,
    offset: Int,
) {
    if ((t == 0f && x0 == x1 && y0 == y1) || (t == 1f && x1 == x2 && y1 == y2)) {
        out[offset] = x2 - x0
        out[offset + 1] = y2 - y0
        return
    }
    val bx = x1 - x0
    val by = y1 - y0
    val ax = x2 - x1 - bx
    val ay = y2 - y1 - by
    val tx = ax * t + bx
    val ty = ay * t + by
    out[offset] = tx + tx
    out[offset + 1] = ty + ty
}

/** Evaluates a cubic Bézier at [t] (Skia's `SkEvalCubicAt`). */
internal fun evalCubic(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    x3: Float,
    y3: Float,
    t: Float,
    out: FloatArray,
    offset: Int,
) {
    val ax = x3 + 3f * (x1 - x2) - x0
    val ay = y3 + 3f * (y1 - y2) - y0
    val bx = 3f * (x2 - 2f * x1 + x0)
    val by = 3f * (y2 - 2f * y1 + y0)
    val cx = 3f * (x1 - x0)
    val cy = 3f * (y1 - y0)
    out[offset] = ((ax * t + bx) * t + cx) * t + x0
    out[offset + 1] = ((ay * t + by) * t + cy) * t + y0
}

/** Unnormalized cubic tangent at [t] (tangent part of Skia's `SkEvalCubicAt`). */
internal fun evalCubicTangent(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    x3: Float,
    y3: Float,
    t: Float,
    out: FloatArray,
    offset: Int,
) {
    if ((t == 0f && x0 == x1 && y0 == y1) || (t == 1f && x2 == x3 && y2 == y3)) {
        var tx: Float
        var ty: Float
        if (t == 0f) {
            tx = x2 - x0
            ty = y2 - y0
        } else {
            tx = x3 - x1
            ty = y3 - y1
        }
        if (tx == 0f && ty == 0f) {
            tx = x3 - x0
            ty = y3 - y0
        }
        out[offset] = tx
        out[offset + 1] = ty
        return
    }
    val ax = x3 + 3f * (x1 - x2) - x0
    val ay = y3 + 3f * (y1 - y2) - y0
    val bx = 2f * (x2 - 2f * x1 + x0)
    val by = 2f * (y2 - 2f * y1 + y0)
    val cx = x1 - x0
    val cy = y1 - y0
    out[offset] = (ax * t + bx) * t + cx
    out[offset + 1] = (ay * t + by) * t + cy
}

/**
 * Splits a quadratic at [t] (Skia's `SkChopQuadAt`), writing the 5 resulting points into [dst] at
 * [offset].
 */
internal fun chopQuadAt(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    t: Float,
    dst: FloatArray,
    offset: Int,
) {
    val p01x = (x1 - x0) * t + x0
    val p01y = (y1 - y0) * t + y0
    val p12x = (x2 - x1) * t + x1
    val p12y = (y2 - y1) * t + y1
    dst[offset] = x0
    dst[offset + 1] = y0
    dst[offset + 2] = p01x
    dst[offset + 3] = p01y
    dst[offset + 4] = (p12x - p01x) * t + p01x
    dst[offset + 5] = (p12y - p01y) * t + p01y
    dst[offset + 6] = p12x
    dst[offset + 7] = p12y
    dst[offset + 8] = x2
    dst[offset + 9] = y2
}

/**
 * Splits a cubic at [t] (Skia's `SkChopCubicAt`), writing the 7 resulting points into [dst] at
 * [offset].
 */
internal fun chopCubicAt(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    x3: Float,
    y3: Float,
    t: Float,
    dst: FloatArray,
    offset: Int,
) {
    val abx = (x1 - x0) * t + x0
    val aby = (y1 - y0) * t + y0
    val bcx = (x2 - x1) * t + x1
    val bcy = (y2 - y1) * t + y1
    val cdx = (x3 - x2) * t + x2
    val cdy = (y3 - y2) * t + y2
    val abcx = (bcx - abx) * t + abx
    val abcy = (bcy - aby) * t + aby
    val bcdx = (cdx - bcx) * t + bcx
    val bcdy = (cdy - bcy) * t + bcy
    dst[offset] = x0
    dst[offset + 1] = y0
    dst[offset + 2] = abx
    dst[offset + 3] = aby
    dst[offset + 4] = abcx
    dst[offset + 5] = abcy
    dst[offset + 6] = (bcdx - abcx) * t + abcx
    dst[offset + 7] = (bcdy - abcy) * t + abcy
    dst[offset + 8] = bcdx
    dst[offset + 9] = bcdy
    dst[offset + 10] = cdx
    dst[offset + 11] = cdy
    dst[offset + 12] = x3
    dst[offset + 13] = y3
}

/**
 * Splits a conic at [t] (Skia's `SkConic::chopAt(t, dst[2])`), writing the 5 points of the two
 * halves into [dst] at [offset] and the two weights into [weights] at [weightOffset].
 *
 * @return false if the result is not finite.
 */
internal fun chopConicAt(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    weight: Float,
    t: Float,
    dst: FloatArray,
    offset: Int,
    weights: FloatArray,
    weightOffset: Int,
): Boolean {
    // Map to homogeneous coordinates: (x0, y0, 1), (x1 * w, y1 * w, w), (x2, y2, 1).
    val ax = x0
    val ay = y0
    val az = 1f
    val bx = x1 * weight
    val by = y1 * weight
    val bz = weight
    val cx = x2
    val cy = y2
    val cz = 1f
    val abx = ax + (bx - ax) * t
    val aby = ay + (by - ay) * t
    val abz = az + (bz - az) * t
    val bcx = bx + (cx - bx) * t
    val bcy = by + (cy - by) * t
    val bcz = bz + (cz - bz) * t
    val midX = abx + (bcx - abx) * t
    val midY = aby + (bcy - aby) * t
    val midZ = abz + (bcz - abz) * t
    dst[offset] = x0
    dst[offset + 1] = y0
    dst[offset + 2] = abx / abz
    dst[offset + 3] = aby / abz
    dst[offset + 4] = midX / midZ
    dst[offset + 5] = midY / midZ
    dst[offset + 6] = bcx / bcz
    dst[offset + 7] = bcy / bcz
    dst[offset + 8] = x2
    dst[offset + 9] = y2
    val root = sqrt(midZ)
    weights[weightOffset] = abz / root
    weights[weightOffset + 1] = bcz / root
    for (i in 0 until 10) {
        if (!dst[offset + i].isFinite()) return false
    }
    return weights[weightOffset].isFinite() && weights[weightOffset + 1].isFinite()
}

/**
 * Extracts the part of a conic between [t1] and [t2] (Skia's `SkConic::chopAt(t1, t2, dst)`).
 * Writes 3 points into [dst] at [offset] and returns the weight of the sub-conic.
 */
internal fun chopConicBetween(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    weight: Float,
    t1: Float,
    t2: Float,
    dst: FloatArray,
    offset: Int,
): Float {
    if (t1 == 0f || t2 == 1f) {
        if (t1 == 0f && t2 == 1f) {
            dst[offset] = x0
            dst[offset + 1] = y0
            dst[offset + 2] = x1
            dst[offset + 3] = y1
            dst[offset + 4] = x2
            dst[offset + 5] = y2
            return weight
        }
        val halves = FloatArray(10)
        val weights = FloatArray(2)
        if (
            chopConicAt(
                x0,
                y0,
                x1,
                y1,
                x2,
                y2,
                weight,
                if (t1 != 0f) t1 else t2,
                halves,
                0,
                weights,
                0,
            )
        ) {
            val which = if (t1 != 0f) 1 else 0
            halves.copyInto(dst, offset, which * 4, which * 4 + 6)
            return weights[which]
        }
    }
    // Numerator/denominator coefficients of the rational quadratic.
    val p1wx = x1 * weight
    val p1wy = y1 * weight
    val numAx = x2 - 2f * p1wx + x0
    val numAy = y2 - 2f * p1wy + y0
    val numBx = 2f * (p1wx - x0)
    val numBy = 2f * (p1wy - y0)
    val denB = 2f * (weight - 1f)
    val denA = -denB
    fun numX(t: Float) = (numAx * t + numBx) * t + x0
    fun numY(t: Float) = (numAy * t + numBy) * t + y0
    fun den(t: Float) = (denA * t + denB) * t + 1f
    val aX = numX(t1)
    val aY = numY(t1)
    val aZ = den(t1)
    val mid = (t1 + t2) / 2f
    val dX = numX(mid)
    val dY = numY(mid)
    val dZ = den(mid)
    val cX = numX(t2)
    val cY = numY(t2)
    val cZ = den(t2)
    val bX = 2f * dX - (aX + cX) * 0.5f
    val bY = 2f * dY - (aY + cY) * 0.5f
    val bZ = 2f * dZ - (aZ + cZ) * 0.5f
    dst[offset] = aX / aZ
    dst[offset + 1] = aY / aZ
    dst[offset + 2] = bX / bZ
    dst[offset + 3] = bY / bZ
    dst[offset + 4] = cX / cZ
    dst[offset + 5] = cY / cZ
    return bZ / sqrt(aZ * cZ)
}

/**
 * Returns the number of line segments needed to flatten a quadratic Bézier within [tolerance]
 * (Wang's formula).
 */
internal fun quadSegmentCount(
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    tolerance: Double,
): Int {
    val dx = x0 - 2.0 * x1 + x2
    val dy = y0 - 2.0 * y1 + y2
    val m = sqrt(dx * dx + dy * dy)
    return segmentCountFor(sqrt(m / (4.0 * tolerance)))
}

/**
 * Returns the number of line segments needed to flatten a cubic Bézier within [tolerance] (Wang's
 * formula).
 */
internal fun cubicSegmentCount(
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    x3: Double,
    y3: Double,
    tolerance: Double,
): Int {
    val ax = x0 - 2.0 * x1 + x2
    val ay = y0 - 2.0 * y1 + y2
    val bx = x1 - 2.0 * x2 + x3
    val by = y1 - 2.0 * y2 + y3
    val m = sqrt(maxOf(ax * ax + ay * ay, bx * bx + by * by))
    return segmentCountFor(sqrt(0.75 * m / tolerance))
}

/** Upper bound on the number of line segments a single curve is flattened into. */
internal const val MaxFlattenSegments: Int = 1024

private fun segmentCountFor(n: Double): Int =
    if (!(n > 1.0)) 1
    else if (n >= MaxFlattenSegments) MaxFlattenSegments else kotlin.math.ceil(n).toInt()
