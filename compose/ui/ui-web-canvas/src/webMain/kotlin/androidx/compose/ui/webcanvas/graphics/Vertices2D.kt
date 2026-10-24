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

/**
 * Draws triangle meshes. The Canvas 2D API can't interpolate colors over a triangle: the vertex
 * colors are interpolated by WebGL 2 (see [WebCanvas.drawColoredVertices]) or, without it, each
 * triangle is filled with the average of its vertex colors.
 */
internal object Vertices2D {
    /**
     * The [blendMode] combines vertex colors with the paint's shader; meshes drawn with a shader
     * are filled with the paint color, so it's not used.
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
        val indices = vertices.indices
        val vertexCount = positions.size / 2
        val indexCount = if (indices.isNotEmpty()) indices.size else vertexCount
        fun vertex(i: Int): Int = if (indices.isNotEmpty()) indices[i].toInt() else i

        // Without a shader, the vertex colors are used as they are (the blend mode only applies
        // between vertex colors and a shader).
        val useVertexColors = colors.isNotEmpty() && paint.shader == null
        val fallbackColor = paint.argb
        val drawBlendMode = paint.blendMode

        fun triangle(i0: Int, i1: Int, i2: Int) {
            if (i0 >= vertexCount || i1 >= vertexCount || i2 >= vertexCount) return
            val color = if (useVertexColors) {
                averageColor(colors[i0], colors[i1], colors[i2])
            } else {
                fallbackColor
            }
            canvas.fillTriangle(
                positions[i0 * 2], positions[i0 * 2 + 1],
                positions[i1 * 2], positions[i1 * 2 + 1],
                positions[i2 * 2], positions[i2 * 2 + 1],
                color,
                drawBlendMode,
            )
        }

        when (vertices.vertexMode) {
            VertexMode.Triangles -> {
                var i = 0
                while (i + 2 < indexCount) {
                    triangle(vertex(i), vertex(i + 1), vertex(i + 2))
                    i += 3
                }
            }
            VertexMode.TriangleStrip -> {
                for (i in 0 until indexCount - 2) {
                    triangle(vertex(i), vertex(i + 1), vertex(i + 2))
                }
            }
            VertexMode.TriangleFan -> {
                for (i in 1 until indexCount - 1) {
                    triangle(vertex(0), vertex(i), vertex(i + 1))
                }
            }
        }
    }

    private fun averageColor(c0: Int, c1: Int, c2: Int): Int {
        fun channel(shift: Int): Int =
            (((c0 ushr shift) and 0xFF) + ((c1 ushr shift) and 0xFF) + ((c2 ushr shift) and 0xFF) + 1) / 3
        return argbOf(channel(24), channel(16), channel(8), channel(0))
    }
}
