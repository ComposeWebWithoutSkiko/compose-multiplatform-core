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
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebPathOpsTest {

    private fun op(a: Path, b: Path, operation: PathOperation): WebPath {
        val result = WebPath()
        assertTrue(result.op(a, b, operation), "op $operation failed")
        return result
    }

    @Test
    fun overlappingRects() {
        val a = rectPath(0f, 0f, 10f, 10f)
        val b = rectPath(5f, 5f, 15f, 15f)

        val intersect = op(a, b, PathOperation.Intersect)
        assertClose(25.0, areaOf(intersect), 1e-3)
        assertRectClose(Rect(5f, 5f, 10f, 10f), intersect.getBounds())

        val union = op(a, b, PathOperation.Union)
        assertClose(175.0, areaOf(union), 1e-3)
        assertRectClose(Rect(0f, 0f, 15f, 15f), union.getBounds())
        assertEquals(1, contourCount(union.snapshot()))

        val difference = op(a, b, PathOperation.Difference)
        assertClose(75.0, areaOf(difference), 1e-3)
        assertTrue(difference.snapshot().contains(2f, 2f))
        assertFalse(difference.snapshot().contains(7f, 7f))

        val reverse = op(a, b, PathOperation.ReverseDifference)
        assertClose(75.0, areaOf(reverse), 1e-3)
        assertTrue(reverse.snapshot().contains(12f, 12f))
        assertFalse(reverse.snapshot().contains(2f, 2f))
        assertFalse(reverse.snapshot().contains(7f, 7f))

        val xor = op(a, b, PathOperation.Xor)
        assertClose(150.0, areaOf(xor), 1e-3)
        assertFalse(xor.snapshot().contains(7f, 7f))
        assertTrue(xor.snapshot().contains(2f, 2f))
        assertTrue(xor.snapshot().contains(12f, 12f))
    }

    @Test
    fun resultUsesEvenOddAndOnlyLines() {
        val result = op(circlePath(0f, 0f, 10f), rectPath(0f, 0f, 20f, 20f), PathOperation.Union)
        assertEquals(PathFillType.EvenOdd, result.fillType)
        assertTrue(verbsOf(result).all { it == M || it == L || it == Z })
    }

    @Test
    fun disjointRects() {
        val a = rectPath(0f, 0f, 10f, 10f)
        val b = rectPath(20f, 20f, 30f, 30f)
        assertTrue(op(a, b, PathOperation.Intersect).isEmpty)
        val union = op(a, b, PathOperation.Union)
        assertClose(200.0, areaOf(union), 1e-3)
        assertEquals(2, contourCount(union.snapshot()))
        assertClose(100.0, areaOf(op(a, b, PathOperation.Difference)), 1e-3)
        assertClose(100.0, areaOf(op(a, b, PathOperation.ReverseDifference)), 1e-3)
        assertClose(200.0, areaOf(op(a, b, PathOperation.Xor)), 1e-3)
    }

    @Test
    fun nestedRectsMakeAHole() {
        val outer = rectPath(0f, 0f, 20f, 20f)
        val inner = rectPath(5f, 5f, 15f, 15f)
        val ring = op(outer, inner, PathOperation.Difference)
        assertClose(300.0, areaOf(ring), 1e-3)
        assertEquals(2, contourCount(ring.snapshot()))
        assertTrue(ring.snapshot().contains(2f, 2f))
        assertFalse(ring.snapshot().contains(10f, 10f))
        // Holes are oriented opposite to outer contours, so non-zero filling works as well.
        ring.fillType = PathFillType.NonZero
        assertFalse(ring.snapshot().contains(10f, 10f))
        assertTrue(ring.snapshot().contains(2f, 2f))

        assertClose(100.0, areaOf(op(outer, inner, PathOperation.Intersect)), 1e-3)
        assertClose(400.0, areaOf(op(outer, inner, PathOperation.Union)), 1e-3)
        assertTrue(op(outer, inner, PathOperation.ReverseDifference).isEmpty)
    }

    @Test
    fun coincidentShapes() {
        val a = rectPath(0f, 0f, 10f, 10f)
        val b = rectPath(0f, 0f, 10f, 10f, Path.Direction.Clockwise)
        assertClose(100.0, areaOf(op(a, b, PathOperation.Intersect)), 1e-3)
        assertClose(100.0, areaOf(op(a, b, PathOperation.Union)), 1e-3)
        assertTrue(op(a, b, PathOperation.Difference).isEmpty)
        assertTrue(op(a, b, PathOperation.Xor).isEmpty)
        assertTrue(op(a, a, PathOperation.Xor).isEmpty)

        val circle = circlePath(50f, 50f, 30f)
        val sameCircle = circlePath(50f, 50f, 30f)
        assertTrue(op(circle, sameCircle, PathOperation.Difference).isEmpty)
        assertClose(PI * 900, areaOf(op(circle, sameCircle, PathOperation.Union)), PI * 900 * 0.01)
    }

    @Test
    fun rectsSharingAnEdgeMerge() {
        val union = op(rectPath(0f, 0f, 10f, 10f), rectPath(10f, 0f, 20f, 10f), PathOperation.Union)
        assertClose(200.0, areaOf(union), 1e-3)
        // A single rectangle: collinear vertices are removed.
        assertEquals(listOf(M, L, L, L, Z), verbsOf(union))
        assertEquals(Rect(0f, 0f, 20f, 10f), union.snapshot().asRect())

        val intersect =
            op(rectPath(0f, 0f, 10f, 10f), rectPath(10f, 0f, 20f, 10f), PathOperation.Intersect)
        assertTrue(intersect.isEmpty)
    }

    @Test
    fun rectsTouchingAtACorner() {
        val union =
            op(rectPath(0f, 0f, 10f, 10f), rectPath(10f, 10f, 20f, 20f), PathOperation.Union)
        assertClose(200.0, areaOf(union), 1e-3)
        assertEquals(2, contourCount(union.snapshot()))
        assertEquals(listOf(M, L, L, L, Z, M, L, L, L, Z), verbsOf(union))
    }

    @Test
    fun overlappingCircles() {
        val a = circlePath(50f, 50f, 50f)
        val b = circlePath(100f, 50f, 50f)
        // Lens area for two circles of radius r at distance d.
        val r = 50.0
        val d = 50.0
        val lens = 2 * r * r * acos(d / (2 * r)) - (d / 2) * sqrt(4 * r * r - d * d)
        val circle = PI * r * r
        assertClose(lens, areaOf(op(a, b, PathOperation.Intersect)), lens * 0.01)
        assertClose(2 * circle - lens, areaOf(op(a, b, PathOperation.Union)), circle * 0.01)
        assertClose(circle - lens, areaOf(op(a, b, PathOperation.Difference)), circle * 0.01)
        assertClose(2 * (circle - lens), areaOf(op(a, b, PathOperation.Xor)), circle * 0.01)
    }

    @Test
    fun fillTypesOfTheOperandsAreHonored() {
        val empty = WebPath()
        val evenOdd =
            WebPath().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, 10f, 10f))
                addRect(Rect(5f, 5f, 15f, 15f))
            }
        assertClose(150.0, areaOf(op(evenOdd, empty, PathOperation.Union)), 1e-3)

        val nonZeroSameDirection =
            WebPath().apply {
                addRect(Rect(0f, 0f, 10f, 10f))
                addRect(Rect(5f, 5f, 15f, 15f))
            }
        assertClose(175.0, areaOf(op(nonZeroSameDirection, empty, PathOperation.Union)), 1e-3)

        val nonZeroOppositeDirections =
            WebPath().apply {
                addRect(Rect(0f, 0f, 10f, 10f), Path.Direction.CounterClockwise)
                addRect(Rect(5f, 5f, 15f, 15f), Path.Direction.Clockwise)
            }
        assertClose(150.0, areaOf(op(nonZeroOppositeDirections, empty, PathOperation.Union)), 1e-3)

        // A frame (even-odd) does not contain a rect lying in its hole.
        val frame =
            WebPath().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, 10f, 10f))
                addRect(Rect(2f, 2f, 8f, 8f))
            }
        val center = rectPath(4f, 4f, 6f, 6f)
        assertTrue(op(frame, center, PathOperation.Intersect).isEmpty)
        assertClose(68.0, areaOf(op(frame, center, PathOperation.Union)), 1e-3)
    }

    @Test
    fun selfIntersectingStar() {
        val star = WebPath()
        for (i in 0 until 5) {
            val angle = -PI / 2 + i * 4 * PI / 5
            val x = (50 + 40 * cos(angle)).toFloat()
            val y = (50 + 40 * sin(angle)).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        val empty = WebPath()

        val nonZero = op(star, empty, PathOperation.Union)
        assertTrue(nonZero.snapshot().contains(50f, 50f))
        assertTrue(nonZero.snapshot().contains(50f, 15f)) // tip

        star.fillType = PathFillType.EvenOdd
        val evenOdd = op(star, empty, PathOperation.Union)
        assertFalse(evenOdd.snapshot().contains(50f, 50f))
        assertTrue(evenOdd.snapshot().contains(50f, 15f))

        // The difference between both is the inner pentagon.
        val pentagon = areaOf(nonZero) - areaOf(evenOdd)
        val innerRadius = 40 * cos(2 * PI / 5) / cos(PI / 5)
        val expected = 5.0 / 2 * innerRadius * innerRadius * sin(2 * PI / 5)
        assertClose(expected, pentagon, 1e-2)
    }

    @Test
    fun hitTestingWithATinyRect() {
        // The pattern used by ShapeContainingUtil.isInPath.
        val circle = circlePath(100f, 100f, 50f)
        fun hit(x: Float, y: Float): Boolean {
            val touch =
                WebPath().apply { addRect(Rect(x - 0.005f, y - 0.005f, x + 0.005f, y + 0.005f)) }
            val result = WebPath()
            result.op(circle, touch, PathOperation.Intersect)
            return !result.isEmpty
        }
        assertTrue(hit(100f, 100f))
        assertTrue(hit(70f, 70f))
        assertTrue(hit(100f, 51f))
        assertTrue(hit(149f, 100f))
        assertFalse(hit(60f, 60f)) // inside the bounds but outside the circle
        assertFalse(hit(100f, 49f))
        assertFalse(hit(200f, 200f))

        // Large coordinates.
        val far = circlePath(100000f, 100000f, 500f)
        val touchInside =
            WebPath().apply { addRect(Rect(99999.995f, 100000f, 100000.005f, 100000.01f)) }
        assertFalse(WebPath().apply { op(far, touchInside, PathOperation.Intersect) }.isEmpty)
    }

    @Test
    fun emptyOperands() {
        val empty = WebPath()
        val rect = rectPath(0f, 0f, 10f, 10f)
        assertTrue(op(empty, empty, PathOperation.Union).isEmpty)
        assertTrue(op(rect, empty, PathOperation.Intersect).isEmpty)
        assertClose(100.0, areaOf(op(rect, empty, PathOperation.Union)), 1e-3)
        assertClose(100.0, areaOf(op(rect, empty, PathOperation.Difference)), 1e-3)
        assertClose(100.0, areaOf(op(rect, empty, PathOperation.Xor)), 1e-3)
        assertTrue(op(empty, rect, PathOperation.Difference).isEmpty)
        assertClose(100.0, areaOf(op(empty, rect, PathOperation.ReverseDifference)), 1e-3)
    }

    @Test
    fun degenerateOperands() {
        val line =
            WebPath().apply {
                moveTo(0f, 0f)
                lineTo(10f, 10f)
            }
        val rect = rectPath(0f, 0f, 10f, 10f)
        assertTrue(op(line, rect, PathOperation.Intersect).isEmpty)
        assertTrue(op(line, WebPath(), PathOperation.Union).isEmpty)
        assertClose(100.0, areaOf(op(line, rect, PathOperation.Union)), 1e-3)

        val moves =
            WebPath().apply {
                moveTo(1f, 1f)
                moveTo(2f, 2f)
            }
        assertTrue(op(moves, rect, PathOperation.Intersect).isEmpty)

        // Zero area rect.
        val flat = rectPath(0f, 5f, 10f, 5f)
        assertTrue(op(flat, rect, PathOperation.Intersect).isEmpty)
        assertClose(100.0, areaOf(op(flat, rect, PathOperation.Union)), 1e-3)
    }

    @Test
    fun nonFiniteOperandsFail() {
        val bad =
            WebPath().apply {
                moveTo(0f, 0f)
                lineTo(Float.NaN, 10f)
                lineTo(10f, 10f)
            }
        val result = rectPath(0f, 0f, 1f, 1f)
        assertFalse(result.op(bad, rectPath(0f, 0f, 10f, 10f), PathOperation.Union))
        // The destination is left unmodified.
        assertEquals(Rect(0f, 0f, 1f, 1f), result.getBounds())
        assertEquals(PathFillType.NonZero, result.fillType)
    }

    @Test
    fun destinationCanBeAnOperand() {
        val path = rectPath(0f, 0f, 10f, 10f)
        assertTrue(path.op(path, rectPath(5f, 0f, 15f, 10f), PathOperation.Union))
        assertClose(150.0, areaOf(path), 1e-3)
        assertTrue(path.op(rectPath(0f, 0f, 10f, 20f), path, PathOperation.Intersect))
        assertClose(100.0, areaOf(path), 1e-3)
    }

    @Test
    fun curvedShapesKeepTheirArea() {
        val ring =
            WebPath().apply {
                fillType = PathFillType.EvenOdd
                addOval(Rect(0f, 0f, 100f, 100f))
                addOval(Rect(25f, 25f, 75f, 75f))
            }
        val half = rectPath(-10f, -10f, 50f, 110f)
        val expected = (PI * 2500 - PI * 625) / 2
        assertClose(expected, areaOf(op(ring, half, PathOperation.Intersect)), expected * 0.01)
    }

    @Test
    fun randomPolygonsMatchPointContainment() {
        val random = Random(1234)
        repeat(12) { iteration ->
            val a = randomPolygon(random, 4 + random.nextInt(8), 100f)
            val b = randomPolygon(random, 4 + random.nextInt(8), 100f)
            // Add a shared edge and a duplicated vertex to stress degenerate cases.
            if (iteration % 3 == 0) b.addPath(a)
            a.fillType = if (random.nextBoolean()) PathFillType.EvenOdd else PathFillType.NonZero
            b.fillType = if (random.nextBoolean()) PathFillType.EvenOdd else PathFillType.NonZero
            assertOpsMatchContainment(a, b, "iteration $iteration") { i, j ->
                Offset(1.3f + i * 2.4917f, 0.7f + j * 2.5063f)
            }
        }
    }

    @Test
    fun degenerateGridPolygonsMatchPointContainment() {
        // Small integer coordinates produce many collinear and overlapping edges, shared
        // vertices and edges going through vertices.
        val random = Random(42)
        repeat(DegenerateIterations) { iteration ->
            val a = randomGridPolygons(random)
            val b = randomGridPolygons(random)
            assertOpsMatchContainment(a, b, "iteration $iteration") { i, j ->
                Offset(0.1234f + i * 0.2617f, 0.0771f + j * 0.2593f)
            }
        }
    }

    @Test
    fun nearlyCollinearPolygonsMatchPointContainment() {
        // Vertices within 1e-4 of a few lines: nearly coincident edges and crossings that are
        // rounded onto the grid.
        val random = Random(7)
        repeat(DegenerateIterations) { iteration ->
            val a = nearlyCollinearPolygons(random)
            val b = nearlyCollinearPolygons(random)
            assertOpsMatchContainment(a, b, "iteration $iteration") { i, j ->
                Offset(0.1234f + i * 0.2617f, 0.0771f + j * 0.2593f)
            }
        }
    }

    private fun nearlyCollinearPolygons(random: Random): WebPath =
        WebPath().apply {
            fillType = if (random.nextBoolean()) PathFillType.EvenOdd else PathFillType.NonZero
            repeat(1 + random.nextInt(3)) {
                val count = 3 + random.nextInt(8)
                for (k in 0 until count) {
                    val t = random.nextInt(11).toFloat()
                    val eps = (random.nextFloat() - 0.5f) * 1e-4f
                    val x: Float
                    val y: Float
                    when (random.nextInt(3)) {
                        0 -> {
                            x = t
                            y = 5f + eps
                        }
                        1 -> {
                            x = 5f + eps
                            y = t
                        }
                        else -> {
                            x = t + eps
                            y = t
                        }
                    }
                    if (k == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        }

    private fun randomGridPolygons(random: Random): WebPath =
        WebPath().apply {
            fillType = if (random.nextBoolean()) PathFillType.EvenOdd else PathFillType.NonZero
            repeat(1 + random.nextInt(3)) {
                moveTo(random.nextInt(11).toFloat(), random.nextInt(11).toFloat())
                repeat(2 + random.nextInt(6)) {
                    lineTo(random.nextInt(11).toFloat(), random.nextInt(11).toFloat())
                }
                close()
            }
        }

    private fun assertOpsMatchContainment(
        a: WebPath,
        b: WebPath,
        message: String,
        sample: (Int, Int) -> Offset,
    ) {
        val dataA = a.snapshot()
        val dataB = b.snapshot()
        for (operation in AllOperations) {
            val result = op(a, b, operation).snapshot()
            var mismatches = 0
            var firstMismatch: Offset? = null
            for (i in 0 until 40) {
                for (j in 0 until 40) {
                    val p = sample(i, j)
                    // The classification of points on the outlines is ambiguous.
                    if (
                        isNearOutline(dataA, p.x, p.y, 1e-3) || isNearOutline(dataB, p.x, p.y, 1e-3)
                    ) {
                        continue
                    }
                    val inA = dataA.contains(p.x, p.y)
                    val inB = dataB.contains(p.x, p.y)
                    val expected =
                        when (operation) {
                            PathOperation.Difference -> inA && !inB
                            PathOperation.Intersect -> inA && inB
                            PathOperation.Union -> inA || inB
                            PathOperation.Xor -> inA != inB
                            else -> inB && !inA
                        }
                    if (result.contains(p.x, p.y) != expected) {
                        mismatches++
                        if (firstMismatch == null) firstMismatch = p
                    }
                }
            }
            assertEquals(
                0,
                mismatches,
                "$message, $operation, first at $firstMismatch: $dataA / $dataB -> $result",
            )
        }
    }

    @Test
    fun manyOverlappingCircles() {
        // Union of many shapes, combined incrementally like user code would do.
        var acc: Path = WebPath()
        for (i in 0 until 10) {
            val circle = circlePath(20f + i * 7f, 50f + (i % 3) * 5f, 15f)
            acc = acc + circle
        }
        val data = (acc as WebPath).snapshot()
        assertTrue(data.contains(20f, 50f))
        assertTrue(data.contains(83f, 50f))
        assertFalse(data.contains(50f, 90f))
        assertEquals(1, contourCount(data))
    }

    private fun randomPolygon(random: Random, count: Int, size: Float): WebPath =
        WebPath().apply {
            moveTo(random.nextFloat() * size, random.nextFloat() * size)
            repeat(count - 1) { lineTo(random.nextFloat() * size, random.nextFloat() * size) }
            close()
        }

    private companion object {
        const val DegenerateIterations = 60

        val AllOperations =
            listOf(
                PathOperation.Difference,
                PathOperation.Intersect,
                PathOperation.Union,
                PathOperation.Xor,
                PathOperation.ReverseDifference,
            )
    }
}
