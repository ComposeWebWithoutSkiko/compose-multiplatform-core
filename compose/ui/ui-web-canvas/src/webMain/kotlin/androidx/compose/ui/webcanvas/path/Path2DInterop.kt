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

import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.createCanvas
import androidx.compose.ui.webcanvas.dom.getContext2D
import androidx.compose.ui.webcanvas.graphics.toJs
import kotlin.js.JsAny
import kotlin.js.js

/**
 * Returns a browser `Path2D` describing this path. The object is built once and cached on the
 * snapshot.
 *
 * Conics (from arcs, ovals and round rects) have no Canvas2D equivalent and are converted into
 * quadratic Béziers with Skia's `chopIntoQuadsPOW2`, using a tolerance of at most 0.1 units (finer
 * for small paths, which are likely to be drawn scaled up). The fill rule is not part of a
 * `Path2D`; pass [PathData.fillType] to `fill()`/`clip()`.
 */
internal fun PathData.toPath2D(): JsAny {
    path2D?.let {
        return it
    }
    val path = newPath2D()
    val pts = points
    val tolerance = displayConicTolerance(this)
    var quads: FloatArray? = null
    var p = 0
    var w = 0
    for (i in 0 until verbCount) {
        when (verbs[i]) {
            PathVerb.Move -> {
                path2DMoveTo(path, pts[2 * p], pts[2 * p + 1])
                p += 1
            }
            PathVerb.Line -> {
                path2DLineTo(path, pts[2 * p], pts[2 * p + 1])
                p += 1
            }
            PathVerb.Quad -> {
                path2DQuadTo(path, pts[2 * p], pts[2 * p + 1], pts[2 * p + 2], pts[2 * p + 3])
                p += 2
            }
            PathVerb.Conic -> {
                val buffer = quads ?: conicQuadBuffer().also { quads = it }
                val count = chopConicIntoQuads(pts, p, conicWeights[w++], tolerance, buffer)
                for (q in 0 until count) {
                    val o = 2 + 4 * q
                    path2DQuadTo(path, buffer[o], buffer[o + 1], buffer[o + 2], buffer[o + 3])
                }
                p += 2
            }
            PathVerb.Cubic -> {
                path2DCubicTo(
                    path,
                    pts[2 * p],
                    pts[2 * p + 1],
                    pts[2 * p + 2],
                    pts[2 * p + 3],
                    pts[2 * p + 4],
                    pts[2 * p + 5],
                )
                p += 3
            }
            PathVerb.Close -> path2DClose(path)
        }
    }
    path2D = path
    return path
}

/** Tolerance used to approximate conics by quadratics for display. */
private fun displayConicTolerance(data: PathData): Float {
    val extent = maxOf(data.right - data.left, data.bottom - data.top)
    return (extent * 0.001f).coerceIn(0.01f, MaxDisplayConicTolerance)
}

/** The largest distance between a conic and the quadratics drawing it (see [toPath2D]), in path units. */
internal const val MaxDisplayConicTolerance = 0.1f

/**
 * Returns true if the point ([x], [y]) is inside this path according to its fill type, by the
 * browser's hit test of its [Path2D][toPath2D]: points on the outline are inside, like with Skia's
 * `SkPath::contains`.
 */
internal fun PathData.contains(x: Float, y: Float): Boolean =
    hitTestContext.isPointInPath(toPath2D(), x.toDouble(), y.toDouble(), fillType.toJs())

/** A context for hit tests, whose transformation stays the identity. */
private val hitTestContext: Context2D by lazy {
    getContext2D(createCanvas(1, 1), willReadFrequently = false)
        ?: error("Canvas 2D is not supported")
}

private fun newPath2D(): JsAny = js("new Path2D()")

private fun path2DMoveTo(path: JsAny, x: Float, y: Float): Unit = js("path.moveTo(x, y)")

private fun path2DLineTo(path: JsAny, x: Float, y: Float): Unit = js("path.lineTo(x, y)")

private fun path2DQuadTo(path: JsAny, x1: Float, y1: Float, x2: Float, y2: Float): Unit =
    js("path.quadraticCurveTo(x1, y1, x2, y2)")

private fun path2DCubicTo(
    path: JsAny,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    x3: Float,
    y3: Float,
): Unit = js("path.bezierCurveTo(x1, y1, x2, y2, x3, y3)")

private fun path2DClose(path: JsAny): Unit = js("path.closePath()")
