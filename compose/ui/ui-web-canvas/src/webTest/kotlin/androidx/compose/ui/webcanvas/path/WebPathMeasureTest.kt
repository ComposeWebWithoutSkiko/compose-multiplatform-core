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
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebPathMeasureTest {

    @Test
    fun getPositionAndTangent() {
        val width = 100f
        val height = 100f
        val path = WebPath().apply { lineTo(width, height) }
        val pathMeasure = WebPathMeasure()

        pathMeasure.setPath(path, false)
        val distance = pathMeasure.length
        val position = pathMeasure.getPosition(distance * 0.5f)

        val tangent = pathMeasure.getTangent(distance * 0.5f)

        assertClose(50f, position.x, 0.0001f)
        assertClose(50f, position.y, 0.0001f)
        assertClose(0.707106f, tangent.x, 0.0001f)
        assertClose(0.707106f, tangent.y, 0.0001f)
    }

    @Test
    fun noPath() {
        val measure = WebPathMeasure()
        assertEquals(0f, measure.length)
        assertEquals(Offset.Unspecified, measure.getPosition(0f))
        assertEquals(Offset.Unspecified, measure.getTangent(0f))
        assertFalse(measure.getSegment(0f, 1f, WebPath()))

        measure.setPath(rectPath(0f, 0f, 10f, 10f), false)
        assertEquals(40f, measure.length)
        measure.setPath(null, false)
        assertEquals(0f, measure.length)
    }

    @Test
    fun closedAndOpenContours() {
        val measure = WebPathMeasure()
        measure.setPath(rectPath(0f, 0f, 10f, 20f), false)
        assertEquals(60f, measure.length)

        val open =
            WebPath().apply {
                moveTo(0f, 0f)
                lineTo(10f, 0f)
                lineTo(10f, 10f)
            }
        measure.setPath(open, false)
        assertEquals(20f, measure.length)

        measure.setPath(open, true)
        assertClose(20f + sqrt(200f), measure.length, 1e-4f)
    }

    @Test
    fun measuresFirstContourWithLength() {
        val path =
            WebPath().apply {
                moveTo(0f, 0f)
                lineTo(0f, 0f) // zero length contour is skipped
                moveTo(5f, 5f) // lone moveTo is skipped
                moveTo(10f, 10f)
                lineTo(20f, 10f)
                moveTo(0f, 0f)
                lineTo(0f, 30f)
            }
        val measure = WebPathMeasure()
        measure.setPath(path, false)
        assertEquals(10f, measure.length)
        assertEquals(Offset(15f, 10f), measure.getPosition(5f))

        // The contours that path effects go through.
        val contours = ContourMeasureIter(path.snapshot(), forceClosed = false)
        assertEquals(10f, contours.next()?.length)
        assertEquals(30f, contours.next()?.length)
        assertNull(contours.next())
    }

    @Test
    fun distancesArePinned() {
        val measure = WebPathMeasure()
        measure.setPath(
            WebPath().apply {
                moveTo(10f, 10f)
                lineTo(20f, 10f)
            },
            false,
        )
        assertEquals(Offset(10f, 10f), measure.getPosition(-5f))
        assertEquals(Offset(20f, 10f), measure.getPosition(100f))
        assertEquals(Offset(1f, 0f), measure.getTangent(100f))
        assertEquals(Offset.Unspecified, measure.getPosition(Float.NaN))
    }

    @Test
    fun circleCircumference() {
        val measure = WebPathMeasure()
        measure.setPath(circlePath(100f, 100f, 100f), false)
        // Like Skia, curves are measured with chords (0.5 tolerance): slightly shorter.
        assertClose(2f * PI.toFloat() * 100f, measure.length, 1.5f)
        assertTrue(measure.length <= 2f * PI.toFloat() * 100f)
        // Positions are evaluated on the exact curve.
        for (i in 0..20) {
            val p = measure.getPosition(measure.length * i / 20f)
            val dx = p.x - 100f
            val dy = p.y - 100f
            assertClose(100f, sqrt(dx * dx + dy * dy), 1e-2f)
            val t = measure.getTangent(measure.length * i / 20f)
            // Tangents are unit vectors perpendicular to the radius.
            assertClose(1f, sqrt(t.x * t.x + t.y * t.y), 1e-4f)
            assertClose(0f, (t.x * dx + t.y * dy) / 100f, 2e-2f)
        }
    }

    @Test
    fun arcLength() {
        val measure = WebPathMeasure()
        measure.setPath(WebPath().apply { addArc(Rect(0f, 0f, 400f, 400f), 0f, 90f) }, false)
        assertClose(PI.toFloat() * 100f, measure.length, 1f)
    }

    @Test
    fun cubicLength() {
        val path =
            WebPath().apply {
                moveTo(0f, 0f)
                cubicTo(0f, 100f, 100f, 100f, 100f, 0f)
            }
        val measure = WebPathMeasure()
        measure.setPath(path, false)
        // Reference length by fine subdivision.
        var reference = 0.0
        var px = 0.0
        var py = 0.0
        val steps = 10000
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val mt = 1 - t
            val x = 3 * mt * t * t * 100 + t * t * t * 100
            val y = 3 * mt * mt * t * 100 + 3 * mt * t * t * 100
            reference += sqrt((x - px) * (x - px) + (y - py) * (y - py))
            px = x
            py = y
        }
        assertClose(reference.toFloat(), measure.length, 1f)
        assertTrue(measure.length <= reference.toFloat() + 1e-3f)
        assertOffsetClose(Offset(50f, 75f), measure.getPosition(measure.length / 2f), 0.5f)
        assertOffsetClose(Offset(1f, 0f), measure.getTangent(measure.length / 2f), 1e-3f)
    }

    @Test
    fun quadLength() {
        val path =
            WebPath().apply {
                moveTo(0f, 0f)
                quadraticTo(50f, 100f, 100f, 0f)
            }
        val measure = WebPathMeasure()
        measure.setPath(path, false)
        // Closed form arc length of the parabola.
        val reference = quadArcLength(0.0, 0.0, 50.0, 100.0, 100.0, 0.0)
        assertClose(reference.toFloat(), measure.length, 1f)
        assertOffsetClose(Offset(50f, 50f), measure.getPosition(measure.length / 2f), 0.5f)
    }

    @Test
    fun getSegmentOnLine() {
        // Port of SkikoPathMeasureTest.getSegment_reusedDestinationUpdatesRetainedSkiaPath
        val startX = 300f
        val startY = 450f
        val endX = 800f
        val endY = 900f
        val source =
            WebPath().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
        val measure = WebPathMeasure().apply { setPath(source, false) }
        val destination = WebPath()
        val startFraction = 0.5f
        val startDistance = measure.length * startFraction

        for (stopFraction in floatArrayOf(0.6f, 0.75f)) {
            destination.reset()

            assertTrue(
                measure.getSegment(
                    startDistance = startDistance,
                    stopDistance = measure.length * stopFraction,
                    destination = destination,
                    startWithMoveTo = true,
                )
            )

            assertEquals(listOf(M, L), verbsOf(destination))
            val points = pointsOf(destination)
            assertOffsetClose(
                Offset(lerp(startX, endX, startFraction), lerp(startY, endY, startFraction)),
                points[0],
                1e-3f,
            )
            assertOffsetClose(
                Offset(lerp(startX, endX, stopFraction), lerp(startY, endY, stopFraction)),
                points[1],
                1e-3f,
            )
        }
    }

    @Test
    fun getSegmentAcrossSegments() {
        val measure = WebPathMeasure()
        measure.setPath(rectPath(0f, 0f, 10f, 10f, Path.Direction.Clockwise), false)
        val destination = WebPath()
        assertTrue(measure.getSegment(5f, 25f, destination))
        assertEquals(listOf(M, L, L, L), verbsOf(destination))
        assertPoints(destination.snapshot(), 5f, 0f, 10f, 0f, 10f, 10f, 5f, 10f)

        // The closing edge is measured too.
        val closing = WebPath()
        assertTrue(measure.getSegment(35f, 100f, closing))
        assertPoints(closing.snapshot(), 0f, 5f, 0f, 0f)
    }

    @Test
    fun getSegmentWithoutMoveTo() {
        val measure = WebPathMeasure()
        measure.setPath(
            WebPath().apply {
                moveTo(0f, 0f)
                lineTo(10f, 0f)
            },
            false,
        )
        val destination = WebPath().apply { moveTo(-5f, -5f) }
        assertTrue(measure.getSegment(2f, 4f, destination, startWithMoveTo = false))
        assertEquals(listOf(M, L), verbsOf(destination))
        assertPoints(destination.snapshot(), -5f, -5f, 4f, 0f)
    }

    @Test
    fun getSegmentRejectsEmptyRanges() {
        val measure = WebPathMeasure()
        measure.setPath(rectPath(0f, 0f, 10f, 10f), false)
        val destination = WebPath()
        assertFalse(measure.getSegment(5f, 5f, destination))
        assertFalse(measure.getSegment(6f, 5f, destination))
        assertFalse(measure.getSegment(50f, 60f, destination))
        assertFalse(measure.getSegment(Float.NaN, 5f, destination))
        assertTrue(destination.isEmpty)
        // Distances are pinned.
        assertTrue(measure.getSegment(-10f, 100f, destination))
        assertEquals(listOf(M, L, L, L, L), verbsOf(destination))
    }

    @Test
    fun getSegmentOnCurves() {
        val quad =
            WebPath().apply {
                moveTo(0f, 0f)
                quadraticTo(50f, 100f, 100f, 0f)
            }
        val cubic =
            WebPath().apply {
                moveTo(0f, 0f)
                cubicTo(0f, 100f, 100f, 100f, 100f, 0f)
            }
        val conic = WebPath().apply { addArc(Rect(0f, 0f, 100f, 100f), 0f, 90f) }
        for ((path, verb) in listOf(quad to Q, cubic to C, conic to K)) {
            val measure = WebPathMeasure()
            measure.setPath(path, false)
            val length = measure.length
            val destination = WebPath()
            assertTrue(measure.getSegment(length * 0.25f, length * 0.75f, destination))
            val verbs = verbsOf(destination)
            assertEquals(M, verbs.first())
            assertTrue(verbs.drop(1).all { it == verb }, "$verbs")
            val points = pointsOf(destination)
            assertOffsetClose(measure.getPosition(length * 0.25f), points.first(), 1e-2f)
            assertOffsetClose(measure.getPosition(length * 0.75f), points.last(), 1e-2f)
            // The extracted piece has about half of the length.
            val pieceMeasure = WebPathMeasure()
            pieceMeasure.setPath(destination, false)
            assertClose(length * 0.5f, pieceMeasure.length, 0.5f)
        }
    }

    @Test
    fun measureIsNotAffectedByLaterPathChanges() {
        val path = WebPath().apply { lineTo(10f, 0f) }
        val measure = WebPathMeasure()
        measure.setPath(path, false)
        path.lineTo(10f, 100f)
        assertEquals(10f, measure.length)
        // Rewritten points too: the measure reads the points of the path as they were.
        path.translate(Offset(50f, 50f))
        assertEquals(Offset(5f, 0f), measure.getPosition(5f))
    }

    @Test
    fun contoursAfterOthersReadTheirOwnConics() {
        val path =
            WebPath().apply {
                addOval(Rect(0f, 0f, 20f, 20f))
                addArc(Rect(100f, 100f, 300f, 300f), 0f, 90f)
            }
        val contours = ContourMeasureIter(path.snapshot(), forceClosed = false)
        contours.next()
        val arc = assertNotNull(contours.next())
        assertClose(50f * PI.toFloat(), arc.length, 0.5f)
        val position = FloatArray(2)
        arc.getPosTan(arc.length / 2f, position, null)
        val angle = PI.toFloat() / 4f
        assertOffsetClose(
            Offset(200f + 100f * cos(angle), 200f + 100f * sin(angle)),
            Offset(position[0], position[1]),
            1e-2f,
        )
    }

    @Test
    fun hugeCurvesAreMeasuredDownToTheTolerance() {
        // Curves subdivided many levels deep: hundreds of chords each.
        val scale = 100_000f
        val path =
            WebPath().apply {
                moveTo(0f, 0f)
                cubicTo(0f, scale, scale, scale, scale, 0f)
                quadraticTo(scale * 1.5f, scale, 2f * scale, 0f)
            }
        val measure = WebPathMeasure()
        measure.setPath(path, false)
        val s = scale.toDouble()
        val expected =
            cubicArcLength(0.0, 0.0, 0.0, s, s, s, s, 0.0) +
                quadArcLength(s, 0.0, 1.5 * s, s, 2.0 * s, 0.0)
        assertClose(expected.toFloat(), measure.length, expected.toFloat() * 1e-4f)
    }

    private fun cubicArcLength(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        x3: Double,
        y3: Double,
    ): Double {
        var length = 0.0
        var px = x0
        var py = y0
        val steps = 100_000
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val mt = 1 - t
            val x = mt * mt * mt * x0 + 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t * t * t * x3
            val y = mt * mt * mt * y0 + 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t * t * t * y3
            length += sqrt((x - px) * (x - px) + (y - py) * (y - py))
            px = x
            py = y
        }
        return length
    }

    private fun quadArcLength(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
    ): Double {
        var length = 0.0
        var px = x0
        var py = y0
        val steps = 10000
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val mt = 1 - t
            val x = mt * mt * x0 + 2 * mt * t * x1 + t * t * x2
            val y = mt * mt * y0 + 2 * mt * t * y1 + t * t * y2
            length += sqrt((x - px) * (x - px) + (y - py) * (y - py))
            px = x
            py = y
        }
        return length
    }
}
