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

package androidx.compose.ui.webcanvas.graphics

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import kotlin.math.max

/**
 * Draws triangle meshes. The Canvas 2D API can't interpolate colors over a triangle: the vertex
 * colors are interpolated by WebGL 2 (see [WebCanvas.drawColoredVertices]) or, without it, each
 * triangle is filled with the average of its vertex colors.
 */
internal object Vertices2D {
    /**
     * Draws [vertices] with [paint], whose alpha and color filter apply to their colors, in its
     * blend mode but Dst (which draws nothing, see `WebCanvas.drawVertices`). The [blendMode]
     * combines vertex colors with the paint's shader; meshes drawn with a shader are filled with the
     * paint color, so it's not used.
     */
    fun draw(canvas: WebCanvas, vertices: Vertices, @Suppress("UNUSED_PARAMETER") blendMode: BlendMode, paint: WebPaint) {
        // Interpolated vertex colors need WebGL; without it, each triangle gets its average color.
        if (vertices.colors.isNotEmpty() && paint.shader == null &&
            canvas.drawColoredVertices(vertices, paint)
        ) {
            return
        }
        val positions = vertices.positions
        val colors = vertices.colors

        // Without a shader, the vertex colors are used as they are (the blend mode only applies
        // between vertex colors and a shader), with the paint alpha, which Skia multiplies them by.
        val useVertexColors = colors.isNotEmpty() && paint.shader == null
        val alpha = paint.color.alpha

        forEachTriangle(vertices) { i0, i1, i2 ->
            val color = if (useVertexColors) {
                averageColor(colors[i0], colors[i1], colors[i2]).withAlphaMultiplied(alpha)
            } else {
                paint.argb
            }
            canvas.fillTriangle(
                positions[i0 * 2], positions[i0 * 2 + 1],
                positions[i1 * 2], positions[i1 * 2 + 1],
                positions[i2 * 2], positions[i2 * 2 + 1],
                color,
                paint,
            )
        }
    }

    /** The number of triangles of [vertices]. */
    fun triangleCount(vertices: Vertices): Int {
        val count = if (vertices.indices.isNotEmpty()) vertices.indices.size else vertices.positions.size / 2
        return if (vertices.vertexMode == VertexMode.Triangles) count / 3 else max(0, count - 2)
    }

    /**
     * Calls [triangle] with the vertices of each triangle of [vertices], in order. The indices are
     * unsigned 16-bit numbers, as Skia reads them: [Vertices] keeps them as shorts, which wrap past
     * 32767.
     */
    inline fun forEachTriangle(vertices: Vertices, triangle: (Int, Int, Int) -> Unit) {
        val mode = vertices.vertexMode
        for (t in 0 until triangleCount(vertices)) {
            // Where the vertices of the triangle are among the indices.
            val first = when (mode) {
                VertexMode.Triangles -> 3 * t
                VertexMode.TriangleStrip -> t
                else -> 0
            }
            val second = if (mode == VertexMode.Triangles) 3 * t + 1 else t + 1
            triangle(vertexAt(vertices, first), vertexAt(vertices, second), vertexAt(vertices, second + 1))
        }
    }

    /** The vertex of the [i]th index of [vertices], or the [i]th vertex without indices. */
    fun vertexAt(vertices: Vertices, i: Int): Int =
        if (vertices.indices.isNotEmpty()) vertices.indices[i].toInt() and 0xFFFF else i

    private fun averageColor(c0: Int, c1: Int, c2: Int): Int {
        fun channel(shift: Int): Int =
            (((c0 ushr shift) and 0xFF) + ((c1 ushr shift) and 0xFF) + ((c2 ushr shift) and 0xFF) + 1) / 3
        return argbOf(channel(24), channel(16), channel(8), channel(0))
    }
}
