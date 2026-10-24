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

@file:OptIn(ExperimentalWasmJsInterop::class)

package androidx.compose.ui.webcanvas.path

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.test.Test
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
    fun asOval() {
        val rect = Rect(10f, 20f, 50f, 40f)
        for (direction in Path.Direction.entries) {
            assertEquals(rect, WebPath().apply { addOval(rect, direction) }.snapshot().asOval())
        }
        // Full arcs start at other points.
        assertEquals(rect, WebPath().apply { addArc(rect, 90f, 360f) }.snapshot().asOval())
        assertEquals(rect, WebPath().apply { addArc(rect, 180f, -360f) }.snapshot().asOval())
        // Round rects with large radii are ovals.
        assertEquals(
            Rect(0f, 0f, 10f, 10f),
            WebPath()
                .apply { addRoundRect(RoundRect(0f, 0f, 10f, 10f, 5f, 5f)) }
                .snapshot()
                .asOval(),
        )
        // Translated ovals.
        val moved =
            WebPath().apply {
                addOval(rect)
                translate(androidx.compose.ui.geometry.Offset(0.1f, 0.3f))
            }
        assertRectClose(rect.translate(0.1f, 0.3f), moved.snapshot().asOval()!!, 1e-4f)

        assertNull(WebPath().apply { addArc(rect, 0f, 270f) }.snapshot().asOval())
        assertNull(
            WebPath().apply { addRoundRect(RoundRect(rect, CornerRadius(4f))) }.snapshot().asOval()
        )
        assertNull(rectPath(0f, 0f, 1f, 1f).snapshot().asOval())
        // Rotated by 45 degrees: still conics, but not an axis-aligned oval.
        val rotated =
            WebPath().apply {
                addOval(rect)
                transform(Matrix().apply { rotateZ(45f) })
            }
        assertNull(rotated.snapshot().asOval())
    }

    @Test
    fun asRoundRect() {
        val roundRect = RoundRect(0f, 0f, 100f, 50f, 10f, 5f)
        for (direction in Path.Direction.entries) {
            assertEquals(
                roundRect,
                WebPath().apply { addRoundRect(roundRect, direction) }.snapshot().asRoundRect(),
            )
        }
        val perCorner =
            RoundRect(
                Rect(0f, 0f, 100f, 50f),
                topLeft = CornerRadius(10f, 5f),
                topRight = CornerRadius(0f),
                bottomRight = CornerRadius(20f, 20f),
                bottomLeft = CornerRadius(4f, 8f),
            )
        assertEquals(
            perCorner,
            WebPath().apply { addRoundRect(perCorner) }.snapshot().asRoundRect(),
        )

        // Radii are reported after Skia's normalization.
        val scaled = WebPath().apply { addRoundRect(RoundRect(0f, 0f, 100f, 10f, 20f, 20f)) }
        assertEquals(RoundRect(0f, 0f, 100f, 10f, 5f, 5f), scaled.snapshot().asRoundRect())

        assertNull(rectPath(0f, 0f, 10f, 10f).snapshot().asRoundRect())
        assertNull(circlePath(5f, 5f, 5f).snapshot().asRoundRect())
        assertNull(
            WebPath()
                .apply {
                    addRoundRect(roundRect)
                    addRoundRect(roundRect)
                }
                .snapshot()
                .asRoundRect()
        )
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
        assertTrue(PathData.Empty.isEmpty)
        assertEquals(Rect.Zero, PathData.Empty.bounds)
        assertTrue(PathData.Empty.isConvex)
        assertEquals(0, PathData.Empty.toWebPath().snapshot().verbCount)
    }

    @Test
    fun toWebPathCopiesTheGeometry() {
        val data = rectPath(0f, 0f, 10f, 10f).apply { fillType = PathFillType.EvenOdd }.snapshot()
        val copy = data.toWebPath()
        assertEquals(PathFillType.EvenOdd, copy.fillType)
        copy.lineTo(50f, 50f)
        assertEquals(5, data.verbCount)
        assertEquals(listOf(M, L, L, L, Z, M, L), verbsOf(copy))
    }

    @Test
    fun path2DIsCachedPerSnapshot() {
        val path = rectPath(0f, 0f, 10f, 10f)
        val data = path.snapshot()
        assertNull(data.path2D)
        path.toPath2D()
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
            val path2D = data.toPath2D()
            val rule = canvasFillRule(data)
            var checked = 0
            for (i in 0 until 60) {
                for (j in 0 until 40) {
                    val x = 0.5f + i * 5.13f
                    val y = 0.5f + j * 5.07f
                    if (isNearOutline(data, x, y, 0.5)) continue
                    assertEquals(
                        data.contains(x, y),
                        isPointInPath(path2D, x.toDouble(), y.toDouble(), rule),
                        "($x, $y) with $fillType",
                    )
                    checked++
                }
            }
            assertTrue(checked > 2000)
        }
    }
}

private fun isPointInPath(path: JsAny, x: Double, y: Double, rule: String): Boolean =
    js("new OffscreenCanvas(1, 1).getContext('2d').isPointInPath(path, x, y, rule)")
