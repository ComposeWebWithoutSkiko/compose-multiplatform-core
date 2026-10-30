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

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PathDataTest {

    @Test
    fun asRect() {
        for (direction in Path.Direction.entries) {
            assertEquals(
                Rect(1f, 2f, 3f, 4f),
                rectPath(1f, 2f, 3f, 4f, direction).snapshot().asRect(),
            )
        }
        // Built with lines, starting from another corner, with an explicit closing line.
        val manual =
            WebPath().apply {
                moveTo(10f, 20f)
                lineTo(10f, 0f)
                lineTo(0f, 0f)
                lineTo(0f, 20f)
                lineTo(10f, 20f)
                close()
            }
        assertEquals(Rect(0f, 0f, 10f, 20f), manual.snapshot().asRect())

        // Not rectangles.
        assertNull(polygonPath(0f, 0f, 10f, 0f, 10f, 10f, 0f, 11f).snapshot().asRect())
        assertNull(
            WebPath()
                .apply {
                    moveTo(0f, 0f)
                    lineTo(10f, 0f)
                    lineTo(10f, 10f)
                    lineTo(0f, 10f)
                }
                .snapshot()
                .asRect(),
            "open contour",
        )
        assertNull(rectPath(0f, 0f, 10f, 0f).snapshot().asRect(), "empty rect")
        assertNull(
            rectPath(0f, 0f, 1f, 1f).apply { addRect(Rect(2f, 2f, 3f, 3f)) }.snapshot().asRect()
        )
        assertNull(circlePath(5f, 5f, 5f).snapshot().asRect())
        assertNull(WebPath().snapshot().asRect())
    }

    @Test
    fun asRoundRect() {
        val out = FloatArray(12)
        // Corners of their own radii, one of them square.
        val radii = floatArrayOf(10f, 5f, 0f, 0f, 20f, 20f, 4f, 8f)
        for (clockwise in listOf(true, false)) {
            for (start in 0 until 8) {
                val path = WebPath().apply { addRoundRect(0f, 0f, 100f, 50f, radii, clockwise, start) }
                assertTrue(path.snapshot().asRoundRect(out), "clockwise $clockwise from $start")
                assertContentEquals(floatArrayOf(0f, 0f, 100f, 50f) + radii, out)
            }
        }
        // A circle, as an oval.
        assertTrue(circlePath(5f, 5f, 5f).snapshot().asRoundRect(out))
        assertContentEquals(floatArrayOf(0f, 0f, 10f, 10f, 5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f), out)

        // Not rounded rectangles.
        assertFalse(rectPath(0f, 0f, 10f, 10f).snapshot().asRoundRect(out), "a rectangle")
        assertFalse(
            WebPath()
                .apply {
                    addRoundRect(RoundRect(0f, 0f, 10f, 10f, 2f, 2f))
                    addRoundRect(RoundRect(20f, 0f, 30f, 10f, 2f, 2f))
                }
                .snapshot()
                .asRoundRect(out),
            "two contours",
        )
        val open = WebPath().apply {
            moveTo(0f, 5f)
            conicTo(0f, 0f, 5f, 0f, QuarterConicWeight)
            conicTo(10f, 0f, 10f, 5f, QuarterConicWeight)
            conicTo(10f, 10f, 5f, 10f, QuarterConicWeight)
            conicTo(0f, 10f, 0f, 5f, QuarterConicWeight)
        }
        assertFalse(open.snapshot().asRoundRect(out), "open")
        val flatCorners = WebPath().apply {
            moveTo(0f, 5f)
            conicTo(0f, 0f, 5f, 0f, 0.5f)
            conicTo(10f, 0f, 10f, 5f, 0.5f)
            conicTo(10f, 10f, 5f, 10f, 0.5f)
            conicTo(0f, 10f, 0f, 5f, 0.5f)
            close()
        }
        assertFalse(flatCorners.snapshot().asRoundRect(out), "not quarter ellipses")
        val crossing = WebPath().apply {
            moveTo(0f, 8f)
            conicTo(0f, 0f, 8f, 0f, QuarterConicWeight)
            lineTo(2f, 0f)
            conicTo(10f, 0f, 10f, 8f, QuarterConicWeight)
            conicTo(10f, 10f, 8f, 10f, QuarterConicWeight)
            conicTo(0f, 10f, 0f, 8f, QuarterConicWeight)
            close()
        }
        assertFalse(crossing.snapshot().asRoundRect(out), "corners overlapping along an edge")
        // Around the bounds once more by lines through their corners: even-odd, nothing inside.
        val twice = WebPath().apply {
            moveTo(0f, 3f)
            conicTo(0f, 0f, 3f, 0f, QuarterConicWeight)
            lineTo(10f, 0f)
            lineTo(10f, 10f)
            lineTo(0f, 10f)
            lineTo(0f, 0f)
            lineTo(7f, 0f)
            conicTo(10f, 0f, 10f, 3f, QuarterConicWeight)
            lineTo(10f, 7f)
            conicTo(10f, 10f, 7f, 10f, QuarterConicWeight)
            lineTo(3f, 10f)
            conicTo(0f, 10f, 0f, 7f, QuarterConicWeight)
            close()
        }
        assertFalse(twice.snapshot().asRoundRect(out), "twice around")
        val cubic = WebPath().apply {
            moveTo(0f, 5f)
            conicTo(0f, 0f, 5f, 0f, QuarterConicWeight)
            conicTo(10f, 0f, 10f, 5f, QuarterConicWeight)
            cubicTo(10f, 7f, 7f, 10f, 5f, 10f)
            conicTo(0f, 10f, 0f, 5f, QuarterConicWeight)
            close()
        }
        assertFalse(cubic.snapshot().asRoundRect(out), "a cubic")
        assertFalse(WebPath().snapshot().asRoundRect(out), "empty")
    }

    @Test
    fun containsHonorsFillType() {
        val path =
            WebPath().apply {
                addRect(Rect(0f, 0f, 10f, 10f))
                addRect(Rect(2f, 2f, 8f, 8f))
            }
        assertTrue(path.snapshot().contains(1f, 1f))
        assertTrue(path.snapshot().contains(5f, 5f))
        path.fillType = PathFillType.EvenOdd
        assertTrue(path.snapshot().contains(1f, 1f))
        assertFalse(path.snapshot().contains(5f, 5f))
        assertFalse(path.snapshot().contains(11f, 5f))
        assertFalse(path.snapshot().contains(Float.NaN, 5f))
    }

    @Test
    fun containsCurvesAndEdges() {
        val circle = circlePath(50f, 50f, 50f).snapshot()
        assertTrue(circle.contains(50f, 50f))
        assertTrue(circle.contains(50f, 1f))
        assertFalse(circle.contains(5f, 5f))
        assertFalse(circle.contains(50f, -1f))

        // Points on the outline are inside, like Skia.
        val rect = rectPath(0f, 0f, 10f, 10f).snapshot()
        assertTrue(rect.contains(0f, 5f))
        assertTrue(rect.contains(10f, 10f))
        assertFalse(WebPath().snapshot().contains(0f, 0f))
    }

    @Test
    fun boundsAndFiniteness() {
        val data =
            WebPath()
                .apply {
                    moveTo(-5f, 3f)
                    cubicTo(10f, -20f, 30f, 40f, 0f, 0f)
                }
                .snapshot()
        assertEquals(Rect(-5f, -20f, 30f, 40f), data.bounds)
        assertTrue(data.isFinite)
        assertEquals(-5f, data.left)
        assertEquals(40f, data.bottom)

        val infinite = WebPath().apply { lineTo(Float.POSITIVE_INFINITY, 0f) }.snapshot()
        assertFalse(infinite.isFinite)
        assertEquals(Rect.Zero, infinite.bounds)
    }

    @Test
    fun emptyPathData() {
        val empty = WebPath().snapshot()
        assertTrue(empty.isEmpty)
        assertEquals(Rect.Zero, empty.bounds)
        assertTrue(empty.isConvex)
    }

    @Test
    fun sameGeometry() {
        val rect = rectPath(0f, 0f, 10f, 10f).snapshot()
        assertTrue(rect.hasSameGeometry(rectPath(0f, 0f, 10f, 10f).snapshot()))
        assertFalse(rect.hasSameGeometry(rectPath(0f, 0f, 10f, 11f).snapshot()))
        assertFalse(
            rect.hasSameGeometry(
                rectPath(0f, 0f, 10f, 10f).apply { fillType = PathFillType.EvenOdd }.snapshot()
            )
        )
        // The same points, other verbs.
        val lineThenQuad =
            WebPath().apply {
                moveTo(0f, 0f)
                lineTo(10f, 0f)
                quadraticTo(10f, 10f, 0f, 10f)
            }
        val quadThenLine =
            WebPath().apply {
                moveTo(0f, 0f)
                quadraticTo(10f, 0f, 10f, 10f)
                lineTo(0f, 10f)
            }
        assertFalse(lineThenQuad.snapshot().hasSameGeometry(quadThenLine.snapshot()))
        // The same points, other conic weights.
        fun conic(weight: Float) =
            WebPath()
                .apply {
                    moveTo(0f, 0f)
                    conicTo(10f, 0f, 10f, 10f, weight)
                }
                .snapshot()
        assertTrue(conic(0.5f).hasSameGeometry(conic(0.5f)))
        assertFalse(conic(0.5f).hasSameGeometry(conic(0.6f)))
    }

    @Test
    fun path2DIsCachedPerSnapshot() {
        val path = rectPath(0f, 0f, 10f, 10f)
        val data = path.snapshot()
        assertNull(data.path2D)
        data.toPath2D()
        assertSame(data, path.snapshot())
        val cached = data.path2D
        assertTrue(cached != null)
        // A mutation produces a new snapshot without a Path2D; the old one keeps its own.
        path.lineTo(20f, 20f)
        assertNull(path.snapshot().path2D)
        assertTrue(data.path2D != null)
    }

    @Test
    fun path2DMatchesTheGeometry() {
        val path =
            WebPath().apply {
                addRect(Rect(0f, 0f, 100f, 100f))
                addOval(Rect(20f, 20f, 80f, 80f))
                moveTo(110f, 0f)
                quadraticTo(150f, 100f, 190f, 0f)
                close()
                moveTo(200f, 0f)
                cubicTo(200f, 100f, 300f, 100f, 300f, 0f)
                close()
                addRoundRect(RoundRect(0f, 120f, 100f, 200f, 30f, 30f))
            }
        for (fillType in listOf(PathFillType.NonZero, PathFillType.EvenOdd)) {
            path.fillType = fillType
            val data = path.snapshot()
            var checked = 0
            for (i in 0 until 60) {
                for (j in 0 until 40) {
                    val x = 0.5f + i * 5.13f
                    val y = 0.5f + j * 5.07f
                    if (isNearOutline(data, x, y, 0.5)) continue
                    assertEquals(
                        isInsideFlattened(data, x, y),
                        data.contains(x, y),
                        "($x, $y) with $fillType",
                    )
                    checked++
                }
            }
            assertTrue(checked > 2000)
        }
    }

    @Test
    fun containsCountsPointsOnTheOutlineAsInside() {
        val data = rectPath(0f, 0f, 10f, 10f).snapshot()
        assertTrue(data.contains(0f, 5f))
        assertTrue(data.contains(10f, 5f))
        assertTrue(data.contains(5f, 10f))
        assertTrue(data.contains(10f, 10f))
        assertFalse(data.contains(10.01f, 5f))
        assertFalse(data.contains(5f, -0.01f))
    }
}
