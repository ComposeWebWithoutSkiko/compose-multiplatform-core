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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

internal const val M = PathVerb.Move
internal const val L = PathVerb.Line
internal const val Q = PathVerb.Quad
internal const val K = PathVerb.Conic
internal const val C = PathVerb.Cubic
internal const val Z = PathVerb.Close

internal fun verbsOf(data: PathData): List<Byte> = List(data.verbCount) { data.verbs[it] }

internal fun verbsOf(path: WebPath): List<Byte> = verbsOf(path.snapshot())

internal fun pointsOf(data: PathData): List<Offset> =
    List(data.pointCount) { Offset(data.points[2 * it], data.points[2 * it + 1]) }

internal fun pointsOf(path: WebPath): List<Offset> = pointsOf(path.snapshot())

internal fun weightsOf(data: PathData): List<Float> =
    List(data.conicWeightCount) { data.conicWeights[it] }

internal fun rectPath(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    direction: Path.Direction = Path.Direction.CounterClockwise,
): WebPath = WebPath().apply { addRect(Rect(left, top, right, bottom), direction) }

internal fun circlePath(
    cx: Float,
    cy: Float,
    radius: Float,
    direction: Path.Direction = Path.Direction.CounterClockwise,
): WebPath =
    WebPath().apply { addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius), direction) }

internal fun polygonPath(vararg coordinates: Float): WebPath =
    WebPath().apply {
        moveTo(coordinates[0], coordinates[1])
        for (i in 2 until coordinates.size step 2) {
            lineTo(coordinates[i], coordinates[i + 1])
        }
        close()
    }

/** Signed area of the polygonal approximation of [data] (sum over all contours). */
internal fun signedArea(data: PathData): Double {
    val flat = flatten(data, 0.001)
    var area = 0.0
    for (c in 0 until flat.contourCount) {
        val start = flat.start(c)
        val end = flat.end(c)
        for (i in start until end) {
            val j = if (i + 1 == end) start else i + 1
            area += flat.xy[2 * i] * flat.xy[2 * j + 1] - flat.xy[2 * j] * flat.xy[2 * i + 1]
        }
    }
    return area / 2
}

/** Area covered by a path made of non-overlapping contours (e.g. the result of an op). */
internal fun areaOf(path: WebPath): Double = abs(signedArea(path.snapshot()))

internal fun assertClose(
    expected: Float,
    actual: Float,
    tolerance: Float = 1e-4f,
    message: String? = null,
) {
    if (abs(expected - actual) > tolerance) {
        fail(
            "${message?.let { "$it: " } ?: ""}expected $expected but was $actual (tolerance $tolerance)"
        )
    }
}

internal fun assertClose(
    expected: Double,
    actual: Double,
    tolerance: Double,
    message: String? = null,
) {
    if (abs(expected - actual) > tolerance) {
        fail(
            "${message?.let { "$it: " } ?: ""}expected $expected but was $actual (tolerance $tolerance)"
        )
    }
}

internal fun assertOffsetClose(expected: Offset, actual: Offset, tolerance: Float = 1e-4f) {
    if (abs(expected.x - actual.x) > tolerance || abs(expected.y - actual.y) > tolerance) {
        fail("expected $expected but was $actual (tolerance $tolerance)")
    }
}

internal fun assertRectClose(expected: Rect, actual: Rect, tolerance: Float = 1e-3f) {
    assertTrue(
        abs(expected.left - actual.left) <= tolerance &&
            abs(expected.top - actual.top) <= tolerance &&
            abs(expected.right - actual.right) <= tolerance &&
            abs(expected.bottom - actual.bottom) <= tolerance,
        "expected $expected but was $actual",
    )
}

internal fun assertPoints(data: PathData, vararg coordinates: Float) {
    assertEquals(coordinates.size / 2, data.pointCount, "point count of $data")
    for (i in coordinates.indices) {
        assertClose(coordinates[i], data.points[i], 1e-4f, "coordinate $i of $data")
    }
}

/**
 * True if ([x], [y]) is within [distance] of the (flattened) outline of [data], where the
 * inside/outside classification is ambiguous or depends on approximations.
 */
internal fun isNearOutline(data: PathData, x: Float, y: Float, distance: Double): Boolean {
    if (data.isEmpty) return false
    val flat = data.flattened()
    for (c in 0 until flat.contourCount) {
        val start = flat.start(c)
        val end = flat.end(c)
        for (i in start until end) {
            val j = if (i + 1 == end) start else i + 1
            val ax = flat.xy[2 * i]
            val ay = flat.xy[2 * i + 1]
            val dx = flat.xy[2 * j] - ax
            val dy = flat.xy[2 * j + 1] - ay
            val len2 = dx * dx + dy * dy
            val t =
                if (len2 == 0.0) 0.0
                else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx - x
            val py = ay + t * dy - y
            if (px * px + py * py < distance * distance) return true
        }
    }
    return false
}

/** Number of contours (move verbs). */
internal fun contourCount(data: PathData): Int = verbsOf(data).count { it == PathVerb.Move }
