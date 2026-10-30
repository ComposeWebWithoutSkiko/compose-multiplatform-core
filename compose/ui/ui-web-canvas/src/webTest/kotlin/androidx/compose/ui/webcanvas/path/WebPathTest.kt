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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebPathTest {

    @Test
    fun isEmpty() {
        val path = WebPath()
        assertTrue(path.isEmpty)

        path.addRect(Rect(0f, 0f, 16f, 16f))

        assertFalse(path.isEmpty)
    }

    @Test
    fun moveToOnlyIsNotEmpty() {
        // Like SkPath::isEmpty, a path with verbs is not empty, even if it has no area.
        val path = WebPath().apply { moveTo(1f, 1f) }
        assertFalse(path.isEmpty)
    }

    @Test
    fun isConvex() {
        val path = WebPath()
        assertTrue(path.isConvex)

        path.addRect(Rect(0f, 0f, 8f, 8f))
        assertTrue(path.isConvex)

        path.addRect(Rect(8f, 8f, 16f, 16f))
        assertFalse(path.isConvex)
    }

    @Test
    fun isConvexForPolygons() {
        assertTrue(polygonPath(0f, 0f, 10f, 0f, 5f, 8f).isConvex)
        // L shape
        assertFalse(polygonPath(0f, 0f, 10f, 0f, 10f, 5f, 5f, 5f, 5f, 10f, 0f, 10f).isConvex)
        // Self-intersecting star
        assertFalse(polygonPath(50f, 0f, 79f, 90f, 2f, 35f, 98f, 35f, 21f, 90f).isConvex)
        assertTrue(circlePath(10f, 10f, 5f).isConvex)
        assertTrue(WebPath().apply { addRoundRect(RoundRect(0f, 0f, 30f, 20f, 4f, 4f)) }.isConvex)
        // A single line is convex (degenerate).
        assertTrue(WebPath().apply { lineTo(10f, 10f) }.isConvex)
    }

    @Test
    fun getBounds() {
        val path = WebPath()
        assertEquals(Rect(0f, 0f, 0f, 0f), path.getBounds())

        path.addRect(Rect(0f, 0f, 8f, 8f))
        assertEquals(Rect(0f, 0f, 8f, 8f), path.getBounds())

        path.addRect(Rect(8f, 8f, 16f, 16f))
        assertEquals(Rect(0f, 0f, 16f, 16f), path.getBounds())
    }

    @Test
    fun boundsIncludeControlPoints() {
        val path =
            WebPath().apply {
                moveTo(0f, 0f)
                quadraticTo(50f, 100f, 100f, 0f)
            }
        assertEquals(Rect(0f, 0f, 100f, 100f), path.getBounds())
    }

    @Test
    fun boundsOfNonFinitePathAreEmpty() {
        val path =
            WebPath().apply {
                moveTo(0f, 0f)
                lineTo(Float.NaN, 10f)
            }
        assertEquals(Rect.Zero, path.getBounds())
    }

    @Test
    fun copiedPathIsIndependent() {
        val r1 = WebPath().apply { addRect(Rect(0f, 0f, 10f, 10f), Path.Direction.Clockwise) }
        val r2 =
            WebPath().apply {
                addPath(r1)
                addRect(Rect(5f, 5f, 15f, 15f), Path.Direction.Clockwise)
            }
        assertEquals(Rect(0f, 0f, 10f, 10f), r1.getBounds())
        assertEquals(Rect(0f, 0f, 15f, 15f), r2.getBounds())
    }

    @Test
    fun initialParameters() {
        assertEquals(PathFillType.NonZero, WebPath().fillType)
    }

    @Test
    fun resetPreservesFillType() {
        val path = WebPath()
        path.fillType = PathFillType.EvenOdd
        path.addRect(Rect(0f, 0f, 1f, 1f))
        path.reset()
        assertEquals(PathFillType.EvenOdd, path.fillType)
        assertTrue(path.isEmpty)
    }

    @Test
    fun rewindClearsPath() {
        val path = WebPath().apply { addRect(Rect(0f, 0f, 100f, 200f)) }
        assertFalse(path.isEmpty)

        path.rewind()

        assertTrue(path.isEmpty)
        // A fresh contour starts at the origin.
        path.lineTo(1f, 1f)
        assertPoints(path.snapshot(), 0f, 0f, 1f, 1f)
    }

    @Test
    fun lineToOnEmptyPathInjectsMoveToOrigin() {
        val path = WebPath().apply { lineTo(10f, 12f) }
        assertEquals(listOf(M, L), verbsOf(path))
        assertPoints(path.snapshot(), 0f, 0f, 10f, 12f)
    }

    @Test
    fun drawingAfterCloseStartsAtLastMoveTo() {
        val path =
            WebPath().apply {
                moveTo(5f, 5f)
                lineTo(10f, 5f)
                lineTo(10f, 10f)
                close()
                lineTo(20f, 20f)
            }
        assertEquals(listOf(M, L, L, Z, M, L), verbsOf(path))
        assertPoints(path.snapshot(), 5f, 5f, 10f, 5f, 10f, 10f, 5f, 5f, 20f, 20f)
    }

    @Test
    fun closeSemantics() {
        val path = WebPath()
        path.close()
        assertTrue(path.isEmpty, "close() on an empty path adds nothing")

        path.moveTo(1f, 1f)
        path.close()
        assertEquals(listOf(M, Z), verbsOf(path), "a lone moveTo can be closed")

        path.close()
        assertEquals(listOf(M, Z), verbsOf(path), "repeated closes are ignored")
    }

    @Test
    fun consecutiveMoveTosAreKept() {
        val path =
            WebPath().apply {
                moveTo(1f, 1f)
                moveTo(2f, 2f)
                lineTo(3f, 3f)
            }
        assertEquals(listOf(M, M, L), verbsOf(path))
    }

    @Test
    fun relativeOperations() {
        val path =
            WebPath().apply {
                moveTo(10f, 10f)
                relativeLineTo(5f, 0f)
                relativeQuadraticTo(1f, 1f, 2f, 2f)
                relativeCubicTo(1f, 0f, 2f, 0f, 3f, 0f)
            }
        assertEquals(listOf(M, L, Q, C), verbsOf(path))
        assertPoints(
            path.snapshot(),
            10f,
            10f,
            15f,
            10f,
            16f,
            11f,
            17f,
            12f,
            18f,
            12f,
            19f,
            12f,
            20f,
            12f,
        )
    }

    @Test
    fun relativeMoveToAfterCloseIsRelativeToContourStart() {
        val path =
            WebPath().apply {
                moveTo(10f, 10f)
                lineTo(20f, 10f)
                close()
                relativeMoveTo(5f, 5f)
            }
        assertEquals(Offset(15f, 15f), pointsOf(path).last())

        val open =
            WebPath().apply {
                moveTo(10f, 10f)
                lineTo(20f, 10f)
                relativeMoveTo(5f, 5f)
            }
        assertEquals(Offset(25f, 15f), pointsOf(open).last())
    }

    @Test
    fun deprecatedQuadraticAliases() {
        val path = WebPath()
        @Suppress("DEPRECATION") path.quadraticBezierTo(1f, 2f, 3f, 4f)
        @Suppress("DEPRECATION") path.relativeQuadraticBezierTo(1f, 1f, 2f, 2f)
        assertEquals(listOf(M, Q, Q), verbsOf(path))
        assertPoints(path.snapshot(), 0f, 0f, 1f, 2f, 3f, 4f, 4f, 5f, 5f, 6f)
    }

    @Test
    fun conicToSpecialWeights() {
        val path =
            WebPath().apply {
                moveTo(0f, 0f)
                conicTo(1f, 1f, 2f, 0f, 1f) // quad
                conicTo(3f, 1f, 4f, 0f, 0f) // line to the end point
                conicTo(5f, 1f, 6f, 0f, Float.POSITIVE_INFINITY) // two lines
                conicTo(7f, 1f, 8f, 0f, 0.5f)
            }
        assertEquals(listOf(M, Q, L, L, L, K), verbsOf(path))
        assertEquals(listOf(0.5f), weightsOf(path.snapshot()))
    }

    @Test
    fun addRectDirections() {
        val ccw = rectPath(1f, 2f, 3f, 4f)
        assertEquals(listOf(M, L, L, L, Z), verbsOf(ccw))
        assertPoints(ccw.snapshot(), 1f, 2f, 1f, 4f, 3f, 4f, 3f, 2f)

        val cw = rectPath(1f, 2f, 3f, 4f, Path.Direction.Clockwise)
        assertPoints(cw.snapshot(), 1f, 2f, 3f, 2f, 3f, 4f, 1f, 4f)
    }

    @Test
    fun addOvalUsesQuarterConics() {
        val ccw = WebPath().apply { addOval(Rect(0f, 0f, 20f, 10f)) }
        assertEquals(listOf(M, K, K, K, K, Z), verbsOf(ccw))
        val weights = weightsOf(ccw.snapshot())
        assertEquals(4, weights.size)
        weights.forEach { assertClose(0.70710677f, it, 1e-7f) }
        assertPoints(
            ccw.snapshot(),
            20f,
            5f,
            20f,
            0f,
            10f,
            0f,
            0f,
            0f,
            0f,
            5f,
            0f,
            10f,
            10f,
            10f,
            20f,
            10f,
            20f,
            5f,
        )

        val cw = WebPath().apply { addOval(Rect(0f, 0f, 20f, 10f), Path.Direction.Clockwise) }
        assertPoints(
            cw.snapshot(),
            20f,
            5f,
            20f,
            10f,
            10f,
            10f,
            0f,
            10f,
            0f,
            5f,
            0f,
            0f,
            10f,
            0f,
            20f,
            0f,
            20f,
            5f,
        )
    }

    @Test
    fun addRoundRectProducesSkiaVerbs() {
        val path = WebPath().apply { addRoundRect(RoundRect(12f, 12f, 36f, 36f, 8f, 8f)) }
        assertEquals(listOf(M, L, K, L, K, L, K, L, K, Z), verbsOf(path))
        val points = pointsOf(path)
        // Counter-clockwise, starting on the left side below the top-left corner.
        assertEquals(Offset(12f, 20f), points[0])
        assertEquals(Offset(12f, 28f), points[1])
        assertEquals(Offset(12f, 36f), points[2]) // control point at the corner
        assertEquals(Offset(20f, 36f), points[3])

        val cw =
            WebPath().apply {
                addRoundRect(RoundRect(12f, 12f, 36f, 36f, 8f, 8f), Path.Direction.Clockwise)
            }
        val cwPoints = pointsOf(cw)
        assertEquals(Offset(12f, 28f), cwPoints[0])
        assertEquals(Offset(12f, 20f), cwPoints[1])
        assertEquals(Offset(12f, 12f), cwPoints[2])
        assertEquals(Offset(20f, 12f), cwPoints[3])
    }

    @Test
    fun addRoundRectDegeneratesToOvalAndRect() {
        // The radii (scaled down to 6) make a circle.
        val oval = WebPath().apply { addRoundRect(RoundRect(12f, 12f, 24f, 24f, 8f, 8f)) }
        assertEquals(listOf(M, K, K, K, K, Z), verbsOf(oval))
        assertPoints(
            oval.snapshot(),
            12f,
            18f,
            12f,
            24f,
            18f,
            24f,
            24f,
            24f,
            24f,
            18f,
            24f,
            12f,
            18f,
            12f,
            12f,
            12f,
            12f,
            18f,
        )

        val rect = WebPath().apply { addRoundRect(RoundRect(0f, 0f, 10f, 10f, 0f, 0f)) }
        assertEquals(listOf(M, L, L, L, Z), verbsOf(rect))
    }

    @Test
    fun addRoundRectScalesOverlappingRadii() {
        // Vertical radii (20 + 20) don't fit in the height (10): all radii are scaled by 0.25.
        val path = WebPath().apply { addRoundRect(RoundRect(0f, 0f, 100f, 10f, 20f, 20f)) }
        assertEquals(listOf(M, L, K, L, K, L, K, L, K, Z), verbsOf(path))
        val points = pointsOf(path)
        assertEquals(Offset(0f, 5f), points[0])
        assertEquals(Offset(0f, 10f), points[2])
        assertEquals(Offset(5f, 10f), points[3])
        assertRectClose(Rect(0f, 0f, 100f, 10f), path.getBounds())
    }

    @Test
    fun addRoundRectPerCornerRadii() {
        val roundRect =
            RoundRect(
                Rect(0f, 0f, 100f, 50f),
                topLeft = androidx.compose.ui.geometry.CornerRadius(10f, 5f),
                topRight = androidx.compose.ui.geometry.CornerRadius(0f, 0f),
                bottomRight = androidx.compose.ui.geometry.CornerRadius(20f, 20f),
                bottomLeft = androidx.compose.ui.geometry.CornerRadius(4f, 8f),
            )
        val path = WebPath().apply { addRoundRect(roundRect) }
        assertEquals(listOf(M, L, K, L, K, L, K, L, K, Z), verbsOf(path))
        // The ends of the corners, each corner its own radii (the top-right one square), and the
        // corners of the rectangle as the control points of the conics.
        val ends = setOf(
            Offset(10f, 0f), Offset(100f, 0f), Offset(100f, 30f), Offset(80f, 50f),
            Offset(4f, 50f), Offset(0f, 42f), Offset(0f, 5f),
        )
        val corners = setOf(Offset(0f, 0f), Offset(100f, 0f), Offset(100f, 50f), Offset(0f, 50f))
        assertEquals(ends + corners, pointsOf(path).toSet())
    }

    @Test
    fun addArcFullCircleIsOval() {
        val path = WebPath().apply { addArc(Rect(0f, 0f, 10f, 10f), 0f, 360f) }
        assertEquals(listOf(M, K, K, K, K, Z), verbsOf(path))
        assertEquals(Offset(10f, 5f), pointsOf(path)[0])
        // Clockwise for a positive sweep.
        assertEquals(Offset(10f, 10f), pointsOf(path)[1])

        val fromBottom = WebPath().apply { addArc(Rect(0f, 0f, 10f, 10f), 90f, -360f) }
        assertEquals(Offset(5f, 10f), pointsOf(fromBottom)[0])
        assertEquals(Offset(10f, 10f), pointsOf(fromBottom)[1])
    }

    @Test
    fun addArcQuarter() {
        val path = WebPath().apply { addArc(Rect(0f, 0f, 100f, 100f), 0f, 90f) }
        assertEquals(listOf(M, K), verbsOf(path))
        assertPoints(path.snapshot(), 100f, 50f, 100f, 100f, 50f, 100f)
        assertClose(0.70710677f, weightsOf(path.snapshot()).single(), 1e-7f)

        val negative = WebPath().apply { addArc(Rect(0f, 0f, 100f, 100f), 0f, -90f) }
        assertPoints(negative.snapshot(), 100f, 50f, 100f, 0f, 50f, 0f)
    }

    @Test
    fun addArcRadUsesRadians() {
        val degrees = WebPath().apply { addArc(Rect(0f, 0f, 100f, 100f), 30f, 120f) }
        val radians =
            WebPath().apply {
                addArcRad(Rect(0f, 0f, 100f, 100f), (PI / 6).toFloat(), (2 * PI / 3).toFloat())
            }
        assertEquals(verbsOf(degrees), verbsOf(radians))
        val a = pointsOf(degrees)
        val b = pointsOf(radians)
        for (i in a.indices) assertOffsetClose(a[i], b[i], 1e-3f)
    }

    @Test
    fun addArcSmallSweepUsesSingleConic() {
        val path = WebPath().apply { addArc(Rect(0f, 0f, 100f, 100f), 0f, 45f) }
        assertEquals(listOf(M, K), verbsOf(path))
        assertClose(0.9238795f, weightsOf(path.snapshot())[0], 1e-5f)
        val end = pointsOf(path)[2]
        assertOffsetClose(Offset(50f + 50f * 0.70710677f, 50f + 50f * 0.70710677f), end, 1e-3f)
    }

    @Test
    fun addArcIgnoresEmptyOvalsAndZeroSweeps() {
        val path = WebPath()
        path.addArc(Rect(0f, 0f, 0f, 10f), 0f, 90f)
        path.addArc(Rect(0f, 0f, 10f, 10f), 0f, 0f)
        assertTrue(path.isEmpty)
    }

    @Test
    fun arcToWithoutForceMoveToAddsLine() {
        val path =
            WebPath().apply {
                moveTo(0f, 0f)
                arcTo(Rect(0f, 0f, 100f, 100f), 0f, 90f, false)
            }
        assertEquals(listOf(M, L, K), verbsOf(path))
        assertPoints(path.snapshot(), 0f, 0f, 100f, 50f, 100f, 100f, 50f, 100f)
    }

    @Test
    fun arcToWithForceMoveToStartsNewContour() {
        val path =
            WebPath().apply {
                moveTo(0f, 0f)
                arcTo(Rect(0f, 0f, 100f, 100f), 0f, 90f, true)
            }
        assertEquals(listOf(M, M, K), verbsOf(path))
    }

    @Test
    fun arcToOnEmptyPathStartsWithMoveTo() {
        val path = WebPath().apply { arcTo(Rect(0f, 0f, 100f, 100f), 0f, 90f, false) }
        assertEquals(listOf(M, K), verbsOf(path))
    }

    @Test
    fun contiguousArcsDontAddLines() {
        val path =
            WebPath().apply {
                addArc(Rect(0f, 0f, 16f, 16f), 0f, 90f)
                arcTo(Rect(0f, 0f, 16f, 16f), 90f, 90f, false)
            }
        assertEquals(listOf(M, K, K), verbsOf(path))
    }

    @Test
    fun arcToRadMatchesDegrees() {
        val path =
            WebPath().apply {
                arcToRad(Rect(0f, 0f, 100f, 100f), PI.toFloat(), (PI / 2).toFloat(), false)
                close()
            }
        assertEquals(listOf(M, K, Z), verbsOf(path))
        assertOffsetClose(Offset(0f, 50f), pointsOf(path)[0], 1e-3f)
        assertOffsetClose(Offset(50f, 0f), pointsOf(path)[2], 1e-3f)
    }

    @Test
    fun addPathWithOffset() {
        val src = rectPath(0f, 0f, 10f, 10f)
        val dst =
            WebPath().apply {
                moveTo(-1f, -1f)
                addPath(src, Offset(10f, 20f))
            }
        assertEquals(listOf(M, M, L, L, L, Z), verbsOf(dst))
        assertEquals(Offset(10f, 20f), pointsOf(dst)[1])
        assertEquals(Rect(-1f, -1f, 20f, 30f), dst.getBounds())
    }

    @Test
    fun addPathToItself() {
        val path = rectPath(0f, 0f, 10f, 10f)
        path.addPath(path, Offset(100f, 0f))
        assertEquals(listOf(M, L, L, L, Z, M, L, L, L, Z), verbsOf(path))
        assertEquals(Rect(0f, 0f, 110f, 10f), path.getBounds())
    }

    @Test
    fun addPathKeepsFillTypeAndCloseState() {
        val src =
            WebPath().apply {
                fillType = PathFillType.EvenOdd
                moveTo(1f, 1f)
                lineTo(2f, 2f)
                close()
            }
        val dst = WebPath()
        dst.addPath(src)
        assertEquals(PathFillType.NonZero, dst.fillType)
        dst.lineTo(5f, 5f)
        assertEquals(listOf(M, L, Z, M, L), verbsOf(dst))
        assertEquals(Offset(1f, 1f), pointsOf(dst)[2])
    }

    @Test
    fun translate() {
        val path = rectPath(0f, 0f, 10f, 10f)
        path.translate(Offset(5f, 6f))
        assertEquals(Rect(5f, 6f, 15f, 16f), path.getBounds())
    }

    @Test
    fun transformTranslate() {
        val path = rectPath(0f, 0f, 50f, 50f)
        path.transform(Matrix().apply { translate(50f, 50f) })
        assertEquals(Rect(50f, 50f, 100f, 100f), path.getBounds())
    }

    @Test
    fun transformRotateAndScale() {
        val path =
            WebPath().apply {
                moveTo(10f, 0f)
                lineTo(20f, 0f)
            }
        path.transform(Matrix().apply { rotateZ(90f) })
        assertOffsetClose(Offset(0f, 10f), pointsOf(path)[0], 1e-4f)
        assertOffsetClose(Offset(0f, 20f), pointsOf(path)[1], 1e-4f)

        path.transform(Matrix().apply { scale(2f, 3f) })
        assertOffsetClose(Offset(0f, 30f), pointsOf(path)[0], 1e-4f)
    }

    @Test
    fun transformWithPerspective() {
        val path =
            WebPath().apply {
                moveTo(100f, 0f)
                quadraticTo(150f, 50f, 200f, 0f)
            }
        val matrix = Matrix().apply { values[Matrix.Perspective0] = 0.01f }
        path.transform(matrix)
        // The quadratic becomes a conic with a transformed weight.
        assertEquals(listOf(M, K), verbsOf(path))
        val points = pointsOf(path)
        assertOffsetClose(Offset(50f, 0f), points[0], 1e-4f) // w = 0.01 * 100 + 1 = 2
        assertOffsetClose(Offset(150f / 2.5f, 50f / 2.5f), points[1], 1e-4f)
        assertOffsetClose(Offset(200f / 3f, 0f), points[2], 1e-4f)
        // w' = w * z1 / sqrt(z0 * z2)
        assertClose(2.5f / kotlin.math.sqrt(2f * 3f), weightsOf(path.snapshot())[0], 1e-5f)
    }

    @Test
    fun transformWithPerspectiveSubdividesCubics() {
        val path =
            WebPath().apply {
                moveTo(0f, 0f)
                cubicTo(10f, 10f, 20f, 10f, 30f, 0f)
            }
        path.transform(Matrix().apply { values[Matrix.Perspective1] = 0.001f })
        assertEquals(listOf(M, C, C, C, C), verbsOf(path))
    }

    @Test
    fun snapshotIsCachedUntilMutated() {
        val path = rectPath(0f, 0f, 10f, 10f)
        val snapshot = path.snapshot()
        assertSame(snapshot, path.snapshot())
        path.lineTo(20f, 20f)
        assertNotSame(snapshot, path.snapshot())
        path.fillType = PathFillType.EvenOdd
        assertEquals(PathFillType.NonZero, snapshot.fillType)
        assertEquals(PathFillType.EvenOdd, path.snapshot().fillType)
    }

    @Test
    fun snapshotSurvivesAppends() {
        val path =
            WebPath().apply {
                moveTo(1f, 2f)
                lineTo(3f, 4f)
            }
        val snapshot = path.snapshot()
        repeat(100) { path.lineTo(it.toFloat(), it.toFloat()) }
        assertEquals(listOf(M, L), verbsOf(snapshot))
        assertPoints(snapshot, 1f, 2f, 3f, 4f)
    }

    @Test
    fun snapshotSurvivesRewritesAndResets() {
        val path =
            WebPath().apply {
                moveTo(1f, 2f)
                lineTo(3f, 4f)
            }
        val snapshot = path.snapshot()
        path.translate(Offset(10f, 10f))
        path.transform(Matrix().apply { scale(2f, 2f) })
        assertPoints(snapshot, 1f, 2f, 3f, 4f)
        assertPoints(path.snapshot(), 22f, 24f, 26f, 28f)

        val second = path.snapshot()
        path.rewind()
        path.moveTo(100f, 100f)
        path.lineTo(200f, 200f)
        assertPoints(second, 22f, 24f, 26f, 28f)

        val third = path.snapshot()
        path.reset()
        path.moveTo(7f, 7f)
        assertPoints(third, 100f, 100f, 200f, 200f)
    }

    @Test
    fun rewritesOfASnapshotPathReplaceOnlyItsPoints() {
        val path =
            WebPath().apply {
                moveTo(1f, 2f)
                conicTo(3f, 4f, 5f, 6f, 0.5f)
            }
        val snapshot = path.snapshot()
        path.translate(Offset(10f, 10f))
        val translated = path.snapshot()
        assertSame(snapshot.verbs, translated.verbs)
        assertSame(snapshot.conicWeights, translated.conicWeights)
        assertNotSame(snapshot.points, translated.points)
        assertPoints(snapshot, 1f, 2f, 3f, 4f, 5f, 6f)
        assertPoints(translated, 11f, 12f, 13f, 14f, 15f, 16f)
    }

    @Test
    fun readsLeaveThePointsToBeRewrittenInPlace() {
        val path = rectPath(0f, 0f, 10f, 10f)
        val points = path.currentData().points
        path.getBounds()
        path.isConvex
        path.translate(Offset(1f, 1f))
        assertSame(points, path.currentData().points)
        assertEquals(Rect(1f, 1f, 11f, 11f), path.getBounds())
    }

    @Test
    fun operatorsCreateWebPaths() {
        val a = rectPath(0f, 0f, 10f, 10f)
        val b = rectPath(5f, 5f, 15f, 15f)
        val union = a + b
        assertTrue(union is WebPath)
        assertClose(175.0, areaOf(union), 1e-3)
        assertClose(75.0, areaOf((a - b) as WebPath), 1e-3)
        assertClose(25.0, areaOf((a and b) as WebPath), 1e-3)
        assertClose(150.0, areaOf((a xor b) as WebPath), 1e-3)
        assertClose(175.0, areaOf((a or b) as WebPath), 1e-3)
    }
}
