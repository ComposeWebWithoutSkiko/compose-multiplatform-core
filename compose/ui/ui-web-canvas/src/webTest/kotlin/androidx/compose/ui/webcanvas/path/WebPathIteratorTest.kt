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
import androidx.compose.ui.graphics.PathHitTester
import androidx.compose.ui.graphics.PathIterator
import androidx.compose.ui.graphics.PathSegment
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class WebPathIteratorTest {
    @Test
    fun emptyIterator() {
        val path = WebPath()

        val iterator = path.iterator()
        assertFalse(iterator.hasNext())

        var count = 0
        for (segment in path) {
            assertEquals(PathSegment.Type.Done, segment.type)
            count++
        }

        assertEquals(0, count)
    }

    @Test
    fun nonEmptyIterator() {
        val path =
            WebPath().apply {
                moveTo(1.0f, 1.0f)
                lineTo(2.0f, 2.0f)
                close()
            }

        val iterator = path.iterator()
        assertTrue(iterator.hasNext())

        // Like Skia's iterator, a line back to the start of the contour precedes the close.
        val types =
            arrayOf(
                PathSegment.Type.Move,
                PathSegment.Type.Line,
                PathSegment.Type.Line,
                PathSegment.Type.Close,
            )
        val points = arrayOf(Offset(1.0f, 1.0f), Offset(2.0f, 2.0f), Offset(1.0f, 1.0f))

        var count = 0
        for (segment in path) {
            assertEquals(types[count], segment.type)
            when (segment.type) {
                PathSegment.Type.Move -> {
                    assertEquals(points[count], Offset(segment.points[0], segment.points[1]))
                }
                PathSegment.Type.Line -> {
                    assertEquals(points[count - 1], Offset(segment.points[0], segment.points[1]))
                    assertEquals(points[count], Offset(segment.points[2], segment.points[3]))
                }
                else -> {}
            }
            count++
        }

        assertEquals(4, count)
    }

    @Test
    fun iteratorStyles() {
        val path =
            WebPath().apply {
                moveTo(1.0f, 1.0f)
                lineTo(2.0f, 2.0f)
                cubicTo(3.0f, 3.0f, 4.0f, 4.0f, 5.0f, 5.0f)
                quadraticTo(7.0f, 7.0f, 8.0f, 8.0f)
                moveTo(10.0f, 10.0f)
                addRoundRect(RoundRect(12.0f, 12.0f, 36.0f, 36.0f, 8.0f, 8.0f))
                close()
            }

        for (evaluation in PathIterator.ConicEvaluation.entries) {
            val iterator1 = path.iterator(evaluation)
            val iterator2 = path.iterator(evaluation)
            val iterator3 = path.iterator(evaluation)

            val points = FloatArray(8)
            val points2 = FloatArray(16)
            var count = 0

            while (iterator1.hasNext() || iterator2.hasNext() || iterator3.hasNext()) {
                val segment = iterator1.next()
                val type = iterator2.next(points)
                val type2 = iterator3.next(points2, 8)
                count++

                assertEquals(type, segment.type)
                assertEquals(type2, segment.type)

                val p = segment.points
                // The points of the segment, and nothing more.
                val floats =
                    when (type) {
                        PathSegment.Type.Move -> 2
                        PathSegment.Type.Line -> 4
                        PathSegment.Type.Quadratic,
                        PathSegment.Type.Conic -> 6
                        PathSegment.Type.Cubic -> 8
                        else -> 0
                    }
                assertEquals(floats, p.size, "points of $type")
                for (i in p.indices) {
                    assertEquals(p[i], points[i])
                    assertEquals(p[i], points2[8 + i])
                }
                if (type == PathSegment.Type.Conic) {
                    assertEquals(points[6], segment.weight)
                    assertEquals(points2[14], segment.weight)
                } else {
                    assertEquals(0f, segment.weight)
                }
            }
            assertEquals(iterator1.calculateSize(), count)
        }
    }

    @Test
    fun done() {
        val path = WebPath().apply { close() }

        val segment = path.iterator().next()

        assertEquals(PathSegment.Type.Done, segment.type)
        assertEquals(0, segment.points.size)
        assertEquals(0.0f, segment.weight)
        assertEquals(PathSegment.Type.Done, path.iterator().next(FloatArray(8)))
    }

    @Test
    fun close() {
        val path =
            WebPath().apply {
                lineTo(10.0f, 12.0f)
                close()
            }

        val iterator = path.iterator()
        // Swallow the move
        iterator.next()
        // Swallow the line
        iterator.next()

        val closingLine = iterator.next()
        assertEquals(PathSegment.Type.Line, closingLine.type)
        assertEquals(Offset(10.0f, 12.0f), Offset(closingLine.points[0], closingLine.points[1]))
        assertEquals(Offset.Zero, Offset(closingLine.points[2], closingLine.points[3]))

        val segment = iterator.next()

        assertEquals(PathSegment.Type.Close, segment.type)
        assertEquals(0, segment.points.size)
        assertEquals(0.0f, segment.weight)
        assertFalse(iterator.hasNext())
    }

    @Test
    fun aContourEndingAtItsStartGetsNoClosingLine() {
        val path =
            WebPath().apply {
                moveTo(1f, 1f)
                lineTo(5f, 1f)
                lineTo(1f, 1f)
                close()
                moveTo(10f, 10f)
                close()
            }
        val types = path.iterator().asSequence().map { it.type }.toList()
        assertEquals(
            listOf(
                PathSegment.Type.Move,
                PathSegment.Type.Line,
                PathSegment.Type.Line,
                PathSegment.Type.Close,
                PathSegment.Type.Move,
                PathSegment.Type.Close,
            ),
            types,
        )
        assertEquals(types.size, path.iterator().calculateSize())
    }

    @Test
    fun pathHitTesterSeesTheClosingEdges() {
        // The left edge of a rectangle exists only as its close.
        val path = WebPath().apply { addRect(Rect(0f, 0f, 10f, 10f)) }
        val hitTester = PathHitTester(path)
        assertTrue(Offset(5f, 5f) in hitTester)
        assertTrue(Offset(9f, 1f) in hitTester)
        assertFalse(Offset(15f, 5f) in hitTester)
        assertFalse(Offset(-5f, 5f) in hitTester)
    }

    @Test
    fun moveTo() {
        val path = WebPath().apply { moveTo(10.0f, 12.0f) }

        val segment = path.iterator().next()

        assertEquals(PathSegment.Type.Move, segment.type)
        val points = segment.points
        assertEquals(2, points.size)
        assertEquals(Offset(10.0f, 12.0f), Offset(points[0], points[1]))
        assertEquals(0.0f, segment.weight)
    }

    @Test
    fun lineTo() {
        val path =
            WebPath().apply {
                moveTo(4.0f, 6.0f)
                lineTo(10.0f, 12.0f)
            }

        val iterator = path.iterator()
        // Swallow the move
        iterator.next()

        val segment = iterator.next()

        assertEquals(PathSegment.Type.Line, segment.type)
        val points = segment.points
        assertEquals(4, points.size)
        assertEquals(Offset(4.0f, 6.0f), Offset(points[0], points[1]))
        assertEquals(Offset(10.0f, 12.0f), Offset(points[2], points[3]))
        assertEquals(0.0f, segment.weight)
    }

    @Test
    fun quadraticTo() {
        val path =
            WebPath().apply {
                moveTo(4.0f, 6.0f)
                quadraticTo(10.0f, 12.0f, 20.0f, 24.0f)
            }

        val iterator = path.iterator()
        // Swallow the move
        iterator.next()

        val segment = iterator.next()

        assertEquals(PathSegment.Type.Quadratic, segment.type)
        val points = segment.points
        assertEquals(6, points.size)
        assertEquals(Offset(4.0f, 6.0f), Offset(points[0], points[1]))
        assertEquals(Offset(10.0f, 12.0f), Offset(points[2], points[3]))
        assertEquals(Offset(20.0f, 24.0f), Offset(points[4], points[5]))
        assertEquals(0.0f, segment.weight)
    }

    @Test
    fun cubicTo() {
        val path =
            WebPath().apply {
                moveTo(4.0f, 6.0f)
                cubicTo(10.0f, 12.0f, 20.0f, 24.0f, 30.0f, 36.0f)
            }

        val iterator = path.iterator()
        // Swallow the move
        iterator.next()

        val segment = iterator.next()

        assertEquals(PathSegment.Type.Cubic, segment.type)
        val points = segment.points
        assertEquals(8, points.size)
        assertEquals(Offset(4.0f, 6.0f), Offset(points[0], points[1]))
        assertEquals(Offset(10.0f, 12.0f), Offset(points[2], points[3]))
        assertEquals(Offset(20.0f, 24.0f), Offset(points[4], points[5]))
        assertEquals(Offset(30.0f, 36.0f), Offset(points[6], points[7]))
        assertEquals(0.0f, segment.weight)
    }

    @Test
    fun conicTo() {
        val path =
            WebPath().apply { addRoundRect(RoundRect(12.0f, 12.0f, 24.0f, 24.0f, 8.0f, 8.0f)) }

        val iterator = path.iterator(PathIterator.ConicEvaluation.AsConic)
        // Swallow the move
        iterator.next()

        val segment = iterator.next()

        assertEquals(PathSegment.Type.Conic, segment.type)
        val points = segment.points
        assertEquals(6, points.size)

        assertEquals(Offset(12.0f, 18.0f), Offset(points[0], points[1]))
        assertEquals(Offset(12.0f, 24.0f), Offset(points[2], points[3]))
        assertEquals(Offset(18.0f, 24.0f), Offset(points[4], points[5]))
        assertClose(0.70710677f, segment.weight, 1e-7f)
    }

    @Test
    fun conicAsQuadratics() {
        val path =
            WebPath().apply { addRoundRect(RoundRect(12.0f, 12.0f, 24.0f, 24.0f, 8.0f, 8.0f)) }

        for (segment in path) {
            if (segment.type == PathSegment.Type.Conic) fail("Found conic, none expected: $segment")
        }
    }

    @Test
    fun convertedQuadraticsFollowTheConic() {
        val path = WebPath().apply { addOval(Rect(0f, 0f, 200f, 200f)) }
        // Radius 100: the error is 6.07 for a single quadratic and is divided by 4 at each
        // subdivision level, so 3 levels (8 quadratics per quarter) are needed to reach 0.25, and
        // 0.001 takes all the 5 levels there are (32 quadratics per quarter).
        for ((tolerance, perQuarter) in listOf(0.25f to 8, 0.001f to 32)) {
            val iterator = path.iterator(PathIterator.ConicEvaluation.AsQuadratics, tolerance)
            val points = FloatArray(8)
            var lastX = Float.NaN
            var lastY = Float.NaN
            var quadratics = 0
            while (iterator.hasNext()) {
                when (iterator.next(points)) {
                    PathSegment.Type.Move -> {
                        lastX = points[0]
                        lastY = points[1]
                    }
                    PathSegment.Type.Quadratic -> {
                        quadratics++
                        // Segments are continuous.
                        assertEquals(lastX, points[0])
                        assertEquals(lastY, points[1])
                        lastX = points[4]
                        lastY = points[5]
                        // End points lie on the circle.
                        val dx = points[4] - 100f
                        val dy = points[5] - 100f
                        assertClose(100f, sqrt(dx * dx + dy * dy), 1e-2f)
                    }
                    PathSegment.Type.Close -> {}
                    else -> fail("Unexpected segment")
                }
            }
            assertEquals(4 * perQuarter, quadratics)
            assertEquals(1 + 4 * perQuarter + 1, iterator.calculateSize())
            assertEquals(6, iterator.calculateSize(includeConvertedConics = false))
        }
    }

    @Test
    fun sizes() {
        val path = WebPath()
        var iterator = path.iterator()

        assertEquals(0, iterator.calculateSize())

        path.addRoundRect(RoundRect(12.0f, 12.0f, 64.0f, 64.0f, 8.0f, 8.0f))

        // Preserve conics and count
        iterator = path.iterator(PathIterator.ConicEvaluation.AsConic)
        assertEquals(10, iterator.calculateSize())
        assertEquals(iterator.calculateSize(false), iterator.calculateSize())

        // Convert conics and count
        iterator = path.iterator(PathIterator.ConicEvaluation.AsQuadratics)
        // simple size, not including conic conversion
        assertEquals(10, iterator.calculateSize(false))
        // now get the size with converted conics
        assertEquals(14, iterator.calculateSize())
    }

    @Test
    fun sizeCountsTheQuadraticsConicsAreIteratedAs() {
        // A conic of an extreme weight needs the most subdivisions, but its first halves are a
        // pair of lines: like Skia, the iterator turns it into 2 quadratics only.
        val path =
            WebPath().apply {
                moveTo(0f, 0f)
                conicTo(512f, 512f, 1f, 0f, (1L shl 40).toFloat())
            }
        val iterator = path.iterator(PathIterator.ConicEvaluation.AsQuadratics)
        val points = FloatArray(8)
        val segments = mutableListOf<PathSegment.Type>()
        segments += iterator.next(points)
        segments += iterator.next(points)
        // Counting doesn't disturb an iteration in the middle of a conic.
        val size = iterator.calculateSize()
        val secondQuad = FloatArray(8)
        segments += iterator.next(secondQuad)
        while (iterator.hasNext()) segments += iterator.next(points)
        val expected = PathSegment.Type.Quadratic
        assertEquals(listOf(PathSegment.Type.Move, expected, expected), segments)
        assertEquals(segments.size, size)
        assertEquals(listOf(512f, 512f, 512f, 512f, 1f, 0f), secondQuad.take(6))
    }

    @Test
    fun iteratorPropertiesAndSnapshotIsolation() {
        val path =
            WebPath().apply {
                moveTo(0f, 0f)
                lineTo(1f, 1f)
            }
        val iterator = WebPathIterator(path, PathIterator.ConicEvaluation.AsConic, 0.5f)
        assertEquals(path, iterator.path)
        assertEquals(PathIterator.ConicEvaluation.AsConic, iterator.conicEvaluation)
        assertEquals(0.5f, iterator.tolerance)
        path.lineTo(2f, 2f)
        assertEquals(2, iterator.calculateSize())
    }

    @Test
    fun nextRequiresEightFloats() {
        val path = WebPath().apply { lineTo(1f, 1f) }
        assertFailsWith<IllegalStateException> { path.iterator().next(FloatArray(8), 1) }
    }
}
