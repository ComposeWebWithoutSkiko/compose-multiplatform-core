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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StampedPathEffectStyle
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebPathEffectTest {

    private val horizontalLine =
        WebPath()
            .apply {
                moveTo(0f, 0f)
                lineTo(100f, 0f)
            }
            .snapshot()

    @Test
    fun factoriesCreateWebEffects() {
        assertTrue(createDashPathEffect(floatArrayOf(1f, 2f), 0f) is WebPathEffect.Dash)
        assertTrue(createCornerPathEffect(2f) is WebPathEffect.Corner)
        val chain = createChainPathEffect(createCornerPathEffect(2f), createCornerPathEffect(3f))
        assertTrue(chain is WebPathEffect.Chain)
        val stamped =
            createStampedPathEffect(rectPath(0f, 0f, 1f, 1f), 5f, 0f, StampedPathEffectStyle.Rotate)
        assertTrue(stamped is WebPathEffect.Stamped)
    }

    @Test
    fun dashExposesItsParameters() {
        val intervals = floatArrayOf(10f, 5f)
        val dash = createDashPathEffect(intervals, 3f) as WebPathEffect.Dash
        intervals[0] = 100f // The effect keeps its own copy.
        assertContentEquals(floatArrayOf(10f, 5f), dash.intervals)
        assertTrue(dash.isValid)
        assertEquals(3f, dash.adjustedPhase)

        assertEquals(
            10f,
            (createDashPathEffect(floatArrayOf(10f, 5f), -5f) as WebPathEffect.Dash).adjustedPhase,
        )
        assertEquals(
            5f,
            (createDashPathEffect(floatArrayOf(10f, 5f), 35f) as WebPathEffect.Dash).adjustedPhase,
        )
    }

    @Test
    fun dashOnALine() {
        val dash = WebPathEffect.Dash(floatArrayOf(10f, 5f), 0f)
        val result = dash.apply(horizontalLine, isStroke = true)
        // Dashes at [0, 10], [15, 25], ..., [90, 100].
        assertEquals(List(7) { listOf(M, L) }.flatten(), verbsOf(result))
        val points = pointsOf(result)
        assertOffsetClose(Offset(0f, 0f), points[0])
        assertOffsetClose(Offset(10f, 0f), points[1])
        assertOffsetClose(Offset(15f, 0f), points[2])
        assertOffsetClose(Offset(25f, 0f), points[3])
        assertOffsetClose(Offset(90f, 0f), points[12])
        assertOffsetClose(Offset(100f, 0f), points[13])
        assertTrue(dash.resultIsStroke(true))
    }

    @Test
    fun dashWithPhase() {
        val dash = WebPathEffect.Dash(floatArrayOf(10f, 10f), 25f)
        // A phase of 25 wraps to 5: the line starts 5 units into the first dash.
        val result = dash.apply(horizontalLine, isStroke = true)
        val points = pointsOf(result)
        assertOffsetClose(Offset(0f, 0f), points[0])
        assertOffsetClose(Offset(5f, 0f), points[1])
        assertOffsetClose(Offset(15f, 0f), points[2])
        assertOffsetClose(Offset(25f, 0f), points[3])
    }

    @Test
    fun dashIsIgnoredForFills() {
        val dash = WebPathEffect.Dash(floatArrayOf(10f, 5f), 0f)
        assertSame(horizontalLine, dash.apply(horizontalLine, isStroke = false))
        assertFalse(dash.resultIsStroke(false))
    }

    @Test
    fun dashAppliesToHairlines() {
        val dash = WebPathEffect.Dash(floatArrayOf(10f, 5f), 0f)
        assertEquals(14, dash.apply(horizontalLine, isStroke = true).verbCount)
    }

    @Test
    fun invalidDashesAreNoOps() {
        for (intervals in
            listOf(
                floatArrayOf(10f),
                floatArrayOf(10f, 5f, 3f),
                floatArrayOf(10f, -5f),
                floatArrayOf(0f, 0f),
            )) {
            val dash = WebPathEffect.Dash(intervals, 0f)
            assertFalse(dash.isValid)
            assertTrue(dash.isNoOp)
            assertSame(
                horizontalLine,
                dash.apply(horizontalLine, isStroke = true),
            )
        }
        assertFalse(WebPathEffect.Dash(floatArrayOf(1f, 1f), Float.NaN).isValid)
    }

    @Test
    fun aScaledDashKeepsItsProportionsAndStaysInvalidIfItWas() {
        val scaled = WebPathEffect.Dash(floatArrayOf(10f, 5f), 3f).scaled(0.5f)
        assertEquals(listOf(5f, 2.5f), scaled.intervals.toList())
        assertEquals(1.5f, scaled.adjustedPhase)
        assertFalse(WebPathEffect.Dash(floatArrayOf(1f, 1f), Float.NaN).scaled(0.5f).isValid)
    }

    @Test
    fun dashOnAClosedContourJoinsTheLastAndFirstDashes() {
        val rect = rectPath(0f, 0f, 10f, 10f, Path.Direction.Clockwise).snapshot()
        val dash = WebPathEffect.Dash(floatArrayOf(10f, 10f), 5f)
        val result = dash.apply(rect, isStroke = true)
        // First dash [15, 25], then [35, 40] continued by [0, 5] in the same subpath.
        assertEquals(listOf(M, L, L, M, L, L), verbsOf(result))
        assertPoints(result, 10f, 5f, 10f, 10f, 5f, 10f, 0f, 5f, 0f, 0f, 5f, 0f)
    }

    @Test
    fun dashOnCurves() {
        val circle = circlePath(50f, 50f, 50f).snapshot()
        val dash = WebPathEffect.Dash(floatArrayOf(20f, 20f), 0f)
        val result = dash.apply(circle, isStroke = true)
        assertTrue(verbsOf(result).all { it == M || it == K })
        // Every dash lies on the circle.
        for (p in onCurvePoints(result)) {
            val dx = p.x - 50f
            val dy = p.y - 50f
            assertClose(50f, kotlin.math.sqrt(dx * dx + dy * dy), 1e-2f)
        }
        // The circumference is ~314: the first dash is skipped and added at the end, after 7
        // dashes starting at 40, 80, ..., 280.
        assertEquals(8, contourCount(result))
    }

    @Test
    fun cornerOnAClosedRect() {
        val rect = rectPath(0f, 0f, 100f, 100f, Path.Direction.Clockwise).snapshot()
        val corner = WebPathEffect.Corner(10f)
        val result = corner.apply(rect, isStroke = true)
        assertEquals(listOf(M, L, Q, L, Q, L, Q, L, Q, Z), verbsOf(result))
        assertPoints(
            result,
            10f,
            0f,
            90f,
            0f,
            100f,
            0f,
            100f,
            10f,
            100f,
            90f,
            100f,
            100f,
            90f,
            100f,
            10f,
            100f,
            0f,
            100f,
            0f,
            90f,
            0f,
            10f,
            0f,
            0f,
            10f,
            0f,
        )
        // Corner effects apply to fills too.
        assertEquals(10, corner.apply(rect, isStroke = false).verbCount)
    }

    @Test
    fun cornerOnAnOpenPolyline() {
        val polyline =
            WebPath()
                .apply {
                    moveTo(0f, 0f)
                    lineTo(100f, 0f)
                    lineTo(100f, 100f)
                }
                .snapshot()
        val result = WebPathEffect.Corner(10f).apply(polyline, isStroke = true)
        assertEquals(listOf(M, Q, L, Q, L, L), verbsOf(result))
        assertPoints(
            result,
            0f,
            0f,
            0f,
            0f,
            10f,
            0f,
            90f,
            0f,
            100f,
            0f,
            100f,
            10f,
            100f,
            90f,
            100f,
            100f,
        )
    }

    @Test
    fun cornerWithShortSegments() {
        // Segments shorter than twice the radius are rounded at their middle.
        val triangle = polygonPath(0f, 0f, 10f, 0f, 10f, 10f).snapshot()
        val result = WebPathEffect.Corner(20f).apply(triangle, isStroke = true)
        assertEquals(listOf(M, Q, Q, Q, Z), verbsOf(result))
        assertOffsetClose(Offset(5f, 0f), pointsOf(result)[0])
    }

    @Test
    fun cornerKeepsCurves() {
        val circle = circlePath(10f, 10f, 10f).snapshot()
        val result = WebPathEffect.Corner(5f).apply(circle, isStroke = true)
        assertEquals(listOf(M, K, K, K, K, Z), verbsOf(result))
    }

    @Test
    fun cornerKeepsQuadraticsAndCubics() {
        val curves =
            WebPath()
                .apply {
                    moveTo(0f, 0f)
                    quadraticTo(10f, 0f, 10f, 10f)
                    cubicTo(10f, 20f, 20f, 20f, 30f, 20f)
                }
                .snapshot()
        val result = WebPathEffect.Corner(5f).apply(curves, isStroke = true)
        // An open contour ends with a line to its last corner, as in Skia.
        assertEquals(listOf(M, Q, C, L), verbsOf(result))
        assertPoints(result, 0f, 0f, 10f, 0f, 10f, 10f, 10f, 20f, 20f, 20f, 30f, 20f, 30f, 20f)
    }

    @Test
    fun invalidCornerIsNoOp() {
        val corner = WebPathEffect.Corner(0f)
        assertTrue(corner.isNoOp)
        assertSame(horizontalLine, corner.apply(horizontalLine, isStroke = true))
        assertTrue(WebPathEffect.Corner(Float.NaN).isNoOp)
    }

    @Test
    fun stampedTranslate() {
        val shape = rectPath(0f, 0f, 2f, 2f)
        val stamped = WebPathEffect.Stamped(shape, 25f, 0f, StampedPathEffectStyle.Translate)
        val result = stamped.apply(horizontalLine, isStroke = true)
        // Stamps at 0, 25, 50 and 75.
        assertEquals(4, contourCount(result))
        val moves = pointsOf(result).filterIndexed { index, _ -> index % 4 == 0 }
        assertEquals(4, moves.size)
        for ((i, x) in listOf(0f, 25f, 50f, 75f).withIndex()) {
            assertOffsetClose(Offset(x, 0f), moves[i])
        }
        // The result is filled.
        assertFalse(stamped.resultIsStroke(true))
    }

    @Test
    fun stampedPhase() {
        val shape = rectPath(0f, 0f, 2f, 2f)
        val stamped = WebPathEffect.Stamped(shape, 25f, 10f, StampedPathEffectStyle.Translate)
        val result = stamped.apply(horizontalLine, isStroke = true)
        val moves = pointsOf(result).filterIndexed { index, _ -> index % 4 == 0 }
        assertEquals(4, moves.size)
        for ((i, x) in listOf(15f, 40f, 65f, 90f).withIndex()) {
            assertOffsetClose(Offset(x, 0f), moves[i])
        }
    }

    @Test
    fun stampedRotate() {
        val vertical =
            WebPath()
                .apply {
                    moveTo(0f, 0f)
                    lineTo(0f, 100f)
                }
                .snapshot()
        val shape = rectPath(0f, 0f, 2f, 2f)
        val stamped = WebPathEffect.Stamped(shape, 50f, 0f, StampedPathEffectStyle.Rotate)
        val result = stamped.apply(vertical, isStroke = true)
        assertEquals(2, contourCount(result))
        val points = pointsOf(result)
        // The shape is rotated by 90 degrees: (x, y) -> (-y, x).
        assertOffsetClose(Offset(0f, 0f), points[0])
        assertOffsetClose(Offset(-2f, 0f), points[1])
        assertOffsetClose(Offset(-2f, 2f), points[2])
        assertOffsetClose(Offset(0f, 2f), points[3])
        assertOffsetClose(Offset(0f, 50f), points[4])
    }

    @Test
    fun stampedMorph() {
        val shape =
            WebPath().apply {
                moveTo(0f, 0f)
                lineTo(10f, 0f)
                lineTo(10f, 5f)
            }
        val stamped = WebPathEffect.Stamped(shape, 50f, 0f, StampedPathEffectStyle.Morph)
        val result = stamped.apply(horizontalLine, isStroke = true)
        // Lines become quads.
        assertEquals(listOf(M, Q, Q, M, Q, Q), verbsOf(result))
        // On a straight path, morphing is a translation.
        assertPoints(
            result,
            0f,
            0f,
            5f,
            0f,
            10f,
            0f,
            10f,
            2.5f,
            10f,
            5f,
            50f,
            0f,
            55f,
            0f,
            60f,
            0f,
            60f,
            2.5f,
            60f,
            5f,
        )

        // On a circle, the stamps follow the curvature: y offsets are applied along the normal,
        // which points outwards for this circle.
        val circle = circlePath(0f, 0f, 100f).snapshot()
        val bent = stamped.apply(circle, isStroke = true)
        for (p in pointsOf(bent)) {
            val r = kotlin.math.sqrt(p.x * p.x + p.y * p.y)
            assertTrue(r in 99.9f..105.1f, "$p")
        }
    }

    @Test
    fun stampedMorphKeepsCurves() {
        val shape =
            WebPath().apply {
                moveTo(0f, 0f)
                quadraticTo(5f, 2f, 10f, 0f)
                conicTo(12f, 2f, 14f, 0f, 0.5f)
                cubicTo(15f, 2f, 17f, 4f, 20f, 0f)
            }
        val stamped = WebPathEffect.Stamped(shape, 50f, 0f, StampedPathEffectStyle.Morph)
        val result = stamped.apply(horizontalLine, isStroke = true)
        assertEquals(listOf(M, Q, K, C, M, Q, K, C), verbsOf(result))
        // On a straight path, morphing is a translation.
        assertPoints(
            result,
            0f,
            0f,
            5f,
            2f,
            10f,
            0f,
            12f,
            2f,
            14f,
            0f,
            15f,
            2f,
            17f,
            4f,
            20f,
            0f,
            50f,
            0f,
            55f,
            2f,
            60f,
            0f,
            62f,
            2f,
            64f,
            0f,
            65f,
            2f,
            67f,
            4f,
            70f,
            0f,
        )
        assertEquals(listOf(0.5f, 0.5f), weightsOf(result))
    }

    /** Move points and segment end points (control points excluded). */
    private fun onCurvePoints(data: PathData): List<Offset> {
        val points = pointsOf(data)
        val result = mutableListOf<Offset>()
        var p = 0
        for (verb in verbsOf(data)) {
            val count = PathVerb.pointCount(verb)
            if (count > 0) result.add(points[p + count - 1])
            p += count
        }
        return result
    }

    @Test
    fun invalidStampedIsNoOp() {
        val shape = rectPath(0f, 0f, 2f, 2f)
        for (stamped in
            listOf(
                WebPathEffect.Stamped(shape, 0f, 0f, StampedPathEffectStyle.Translate),
                WebPathEffect.Stamped(shape, Float.NaN, 0f, StampedPathEffectStyle.Translate),
                WebPathEffect.Stamped(WebPath(), 10f, 0f, StampedPathEffectStyle.Translate),
            )) {
            assertTrue(stamped.isNoOp)
            assertSame(
                horizontalLine,
                stamped.apply(horizontalLine, isStroke = true),
            )
            assertTrue(stamped.resultIsStroke(true))
        }
    }

    @Test
    fun stampedShapeIsCapturedAtCreation() {
        val shape = rectPath(0f, 0f, 2f, 2f)
        val stamped = WebPathEffect.Stamped(shape, 25f, 0f, StampedPathEffectStyle.Translate)
        shape.addRect(Rect(0f, 0f, 50f, 50f))
        assertEquals(
            4 * 5,
            stamped.apply(horizontalLine, isStroke = true).verbCount,
        )
    }

    @Test
    fun chainAppliesInnerThenOuter() {
        val rect = rectPath(0f, 0f, 100f, 100f, Path.Direction.Clockwise).snapshot()
        val corner = WebPathEffect.Corner(10f)
        val dash = WebPathEffect.Dash(floatArrayOf(30f, 10f), 0f)
        val chain = WebPathEffect.Chain(outer = dash, inner = corner)
        val expected = dash.apply(corner.apply(rect, true), true)
        val result = chain.apply(rect, isStroke = true)
        assertEquals(verbsOf(expected), verbsOf(result))
        assertEquals(pointsOf(expected), pointsOf(result))
        assertTrue(chain.resultIsStroke(true))
    }

    @Test
    fun chainKeepsSkiaQuirkWhenOuterDoesNotApply() {
        // For fills the outer dash does not apply, so (like Skia) the source is drawn as is.
        val rect = rectPath(0f, 0f, 100f, 100f).snapshot()
        val chain =
            WebPathEffect.Chain(
                WebPathEffect.Dash(floatArrayOf(30f, 10f), 0f),
                WebPathEffect.Corner(10f),
            )
        assertSame(rect, chain.apply(rect, isStroke = false))
    }

    @Test
    fun chainIgnoresNoOpEffects() {
        val rect = rectPath(0f, 0f, 100f, 100f).snapshot()
        val corner = WebPathEffect.Corner(10f)
        val chain = WebPathEffect.Chain(WebPathEffect.Dash(floatArrayOf(1f), 0f), corner)
        assertEquals(
            pointsOf(corner.apply(rect, false)),
            pointsOf(chain.apply(rect, false)),
        )
        assertTrue(WebPathEffect.Chain(WebPathEffect.Corner(0f), WebPathEffect.Corner(-1f)).isNoOp)
        // Effects of other backends are ignored.
        val foreign = object : PathEffect {}
        assertSame(rect, WebPathEffect.Chain(foreign, foreign).apply(rect, true))
    }

    @Test
    fun effectsKeepTheirLastResult() {
        val corner = WebPathEffect.Corner(10f)
        val rect = rectPath(0f, 0f, 100f, 100f).snapshot()
        val result = corner.apply(rect, isStroke = true)
        assertSame(result, corner.apply(rect, isStroke = true))
        // The same geometry in another path, as shapes are rebuilt for their effects.
        assertSame(result, corner.apply(rectPath(0f, 0f, 100f, 100f).snapshot(), isStroke = true))
        // Another geometry or style gets the effect applied anew.
        val smaller = rectPath(0f, 0f, 50f, 50f).snapshot()
        val smallerResult = corner.apply(smaller, isStroke = true)
        assertNotSame(result, smallerResult)
        assertEquals(Rect(0f, 0f, 50f, 50f), smallerResult.bounds)
        assertNotSame(smallerResult, corner.apply(smaller, isStroke = false))
    }

    @Test
    fun chainWithStampedProducesFill() {
        val shape = rectPath(0f, 0f, 2f, 2f)
        val stamped = WebPathEffect.Stamped(shape, 12f, 0f, StampedPathEffectStyle.Translate)
        val dash = WebPathEffect.Dash(floatArrayOf(30f, 10f), 0f)
        assertFalse(WebPathEffect.Chain(outer = stamped, inner = dash).resultIsStroke(true))
        assertFalse(WebPathEffect.Chain(outer = dash, inner = stamped).resultIsStroke(true))
        // Stamps every 12 units (0, 12, 24) on the dashes [0, 30], [40, 70] and [80, 100].
        val result =
            WebPathEffect.Chain(outer = stamped, inner = dash)
                .apply(horizontalLine, isStroke = true)
        assertEquals(3 + 3 + 2, contourCount(result))
    }
}
