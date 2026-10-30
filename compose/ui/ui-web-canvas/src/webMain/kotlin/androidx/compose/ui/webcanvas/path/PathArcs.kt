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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

// Arcs of a WebPath, built as Skia builds them (conics per quadrant, Skia's start points and
// rounding), so that the paths match the skiko backend.

/** Port of Skia's `SkPath::arcTo(oval, startAngle, sweepAngle, forceMoveTo)`. */
internal fun WebPath.arcTo(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    startAngleDegrees: Float,
    sweepAngleDegrees: Float,
    forceMoveTo: Boolean,
) {
    // Skia computes arcs in single precision and relies on its rounding for exact comparisons,
    // hence the f32() calls (only effective on Kotlin/JS).
    val width = f32(right - left)
    val height = f32(bottom - top)
    if (width < 0f || height < 0f) return

    val startAngle = f32(startAngleDegrees % 360f)
    val sweepAngle = sweepAngleDegrees
    val moveTo = forceMoveTo || isEmpty

    // Lone point cases.
    if (sweepAngle == 0f && (startAngle == 0f || startAngle == 360f)) {
        // Chrome uses this path to move into and out of ovals.
        addArcPoint(right, midpoint(top, bottom), moveTo)
        return
    } else if (width == 0f && height == 0f) {
        addArcPoint(right, top, moveTo)
        return
    }

    // angles_to_unit_vectors
    val startRad = degreesToRadians(startAngle)
    var stopRad = degreesToRadians(f32(startAngle + sweepAngle))
    val startX = cosSnapToZero(startRad)
    val startY = sinSnapToZero(startRad)
    var stopX = cosSnapToZero(stopRad)
    var stopY = sinSnapToZero(stopRad)
    // If the sweep angle is nearly (but less than) 360, precision loss may produce coincident
    // vectors; tweak the stop vector so that a nearly complete circle is still drawn.
    if (startX == stopX && startY == stopY) {
        val sw = abs(sweepAngle)
        if (sw < 360f && sw > 359f) {
            val deltaRad = if (sweepAngle < 0f) -(1f / 512f) else (1f / 512f)
            do {
                stopRad = f32(stopRad - deltaRad)
                stopY = sinSnapToZero(stopRad)
                stopX = cosSnapToZero(stopRad)
            } while (startX == stopX && startY == stopY)
        }
    }
    val clockwise = sweepAngle > 0f

    val centerX = midpoint(left, right)
    val centerY = midpoint(top, bottom)
    val radiusX = width * 0.5f
    val radiusY = height * 0.5f

    if (startX == stopX && startY == stopY) {
        // The sweep angle is too small for angles_to_unit_vectors: draw a line.
        val endAngle = degreesToRadians(f32(startAngle + sweepAngle))
        addArcPoint(
            centerX + radiusX * cos(endAngle),
            centerY + radiusY * sin(endAngle),
            moveTo,
        )
        return
    }

    val conics = FloatArray(MaxConicsForArc * 7)
    val count =
        buildUnitArc(
            startX,
            startY,
            stopX,
            stopY,
            clockwise,
            radiusX,
            radiusY,
            centerX,
            centerY,
            conics,
        )
    if (count == 0) {
        addArcPoint(centerX + radiusX * stopX, centerY + radiusY * stopY, moveTo)
        return
    }
    addArcPoint(conics[0], conics[1], moveTo)
    for (i in 0 until count) {
        val o = i * 7
        conicTo(conics[o + 2], conics[o + 3], conics[o + 4], conics[o + 5], conics[o + 6])
    }
}

/**
 * Port of Skia's `SkPath::addArc(oval, startAngle, sweepAngle)`: a contour of its own, an oval if
 * the arc turns all around from one of the points ovals start at.
 */
internal fun WebPath.addArc(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    startAngleDegrees: Float,
    sweepAngleDegrees: Float,
) {
    if (!(left < right && top < bottom) || sweepAngleDegrees == 0f) return

    if (sweepAngleDegrees >= 360f || sweepAngleDegrees <= -360f) {
        // We can treat the arc as an oval if it begins at one of our legal starting positions.
        val startOver90 = startAngleDegrees / 90f
        val startOver90I = floor(startOver90 + 0.5f)
        val error = startOver90 - startOver90I
        if (nearlyEqual(error, 0f)) {
            // Index 1 is at startAngle == 0.
            var startIndex = (startOver90I + 1f) % 4f
            if (startIndex < 0f) startIndex += 4f
            addOval(left, top, right, bottom, sweepAngleDegrees > 0f, startIndex.toInt())
            return
        }
    }
    arcTo(left, top, right, bottom, startAngleDegrees, sweepAngleDegrees, forceMoveTo = true)
}

/**
 * Adds a moveTo to ([x], [y]) if [moveTo] is set, otherwise a lineTo unless the current point is
 * sufficiently close already (avoids spurious lines between contiguous arcs).
 */
private fun WebPath.addArcPoint(x: Float, y: Float, moveTo: Boolean) {
    if (moveTo) {
        moveTo(x, y)
    } else if (isEmpty || !nearlyEqual(lastPointX(), x) || !nearlyEqual(lastPointY(), y)) {
        lineTo(x, y)
    }
}

/** Maximum number of conics produced for an arc (Skia's `SkConic::kMaxConicsForArc`). */
private const val MaxConicsForArc = 5

/** `SK_ScalarPI / 180` in single precision. */
private const val DegreesToRadians = 0.017453292f

private fun degreesToRadians(degrees: Float): Float = f32(degrees * f32(DegreesToRadians))

private fun sinSnapToZero(radians: Float): Float {
    val v = f32(sin(radians))
    return if (abs(v) <= ScalarNearlyZero) 0f else v
}

private fun cosSnapToZero(radians: Float): Float {
    val v = f32(cos(radians))
    return if (abs(v) <= ScalarNearlyZero) 0f else v
}

/**
 * Port of Skia's `SkConic::BuildUnitArc` followed by the mapping into the oval: writes up to
 * [MaxConicsForArc] conics as (x0, y0, x1, y1, x2, y2, w) records into [dst] and returns their
 * count. Returns 0 when the start and stop vectors are effectively coincident.
 */
private fun buildUnitArc(
    startX: Float,
    startY: Float,
    stopX: Float,
    stopY: Float,
    clockwise: Boolean,
    radiusX: Float,
    radiusY: Float,
    centerX: Float,
    centerY: Float,
    dst: FloatArray,
): Int {
    // Rotate by (startX, startY) so that the start vector is (1, 0).
    val x = f32(startX * stopX + startY * stopY)
    var y = f32(startX * stopY - startY * stopX)
    val absY = abs(y)

    // Check for (effectively) coincident vectors: this can happen if our angle is nearly 0 or
    // nearly 180 (y == 0); we use the dot product to distinguish between 0 and 180 (x > 0).
    if (absY <= ScalarNearlyZero && x > 0f && ((y >= 0f && clockwise) || (y <= 0f && !clockwise))) {
        return 0
    }
    if (!clockwise) y = -y

    // We decide to use 1 conic per quadrant of a circle. What quadrant does (x, y) lie in?
    var quadrant = 0
    if (y == 0f) {
        quadrant = 2 // 180
    } else if (x == 0f) {
        quadrant = if (y > 0f) 1 else 3 // 90 / 270
    } else {
        if (y < 0f) quadrant += 2
        if ((x < 0f) != (y < 0f)) quadrant += 1
    }

    var conicCount = 0
    for (i in 0 until quadrant) {
        val o = conicCount * 7
        dst[o] = QuadrantX[2 * i]
        dst[o + 1] = QuadrantY[2 * i]
        dst[o + 2] = QuadrantX[2 * i + 1]
        dst[o + 3] = QuadrantY[2 * i + 1]
        dst[o + 4] = QuadrantX[2 * i + 2]
        dst[o + 5] = QuadrantY[2 * i + 2]
        dst[o + 6] = QuarterConicWeight
        conicCount++
    }

    // Now compute any remaining (sub-90-degree) arc for the last conic.
    val lastQx = QuadrantX[quadrant * 2]
    val lastQy = QuadrantY[quadrant * 2]
    val dot = f32(lastQx * x + lastQy * y)
    if (dot < 1f) {
        var offX = f32(lastQx + x)
        var offY = f32(lastQy + y)
        // Compute the bisector vector, and then rescale to be the off-curve point. The length is
        // 1 / cos(theta / 2), and cos(theta / 2) is also the conic weight.
        val cosThetaOver2 = f32(sqrt(f32((1f + dot) / 2f)))
        val targetLength = f32(1f / cosThetaOver2)
        val length = sqrt(offX.toDouble() * offX + offY.toDouble() * offY)
        val scale = targetLength / length
        val scaledX = f32((offX * scale).toFloat())
        val scaledY = f32((offY * scale).toFloat())
        if (scaledX.isFinite() && scaledY.isFinite() && (scaledX != 0f || scaledY != 0f)) {
            offX = scaledX
            offY = scaledY
        } else {
            offX = 0f
            offY = 0f
        }
        if (lastQx != offX || lastQy != offY) {
            val o = conicCount * 7
            dst[o] = lastQx
            dst[o + 1] = lastQy
            dst[o + 2] = offX
            dst[o + 3] = offY
            dst[o + 4] = x
            dst[o + 5] = y
            dst[o + 6] = cosThetaOver2
            conicCount++
        }
    }

    // Undo the rotation (and the y flip for counter-clockwise arcs), then map into the oval.
    for (i in 0 until conicCount) {
        val o = i * 7
        for (k in 0 until 3) {
            val px = dst[o + 2 * k]
            val py = if (clockwise) dst[o + 2 * k + 1] else -dst[o + 2 * k + 1]
            val rx = startX * px - startY * py
            val ry = startY * px + startX * py
            dst[o + 2 * k] = rx * radiusX + centerX
            dst[o + 2 * k + 1] = ry * radiusY + centerY
        }
    }
    return conicCount
}

private val QuadrantX = floatArrayOf(1f, 1f, 0f, -1f, -1f, -1f, 0f, 1f, 1f)
private val QuadrantY = floatArrayOf(0f, 1f, 1f, 1f, 0f, -1f, -1f, -1f, 0f)
