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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toArgb

/**
 * The [Paint] of the web backend. It is a plain holder of drawing parameters: the canvas applies
 * them to the browser's 2D context when it draws.
 *
 * Paints are mutable and Compose reuses them between draw calls, so the recording canvas stores
 * [snapshot]s. [version] changes whenever a property changes, which lets a snapshot be shared by
 * all the calls drawn with an unchanged paint.
 */
internal class WebPaint : Paint {
    internal var version: Int = 0
        private set

    private var cachedSnapshot: WebPaint? = null
    private var cachedSnapshotVersion: Int = -1

    override var alpha: Float
        get() = color.alpha
        set(value) {
            color = color.copy(alpha = value)
        }

    override var isAntiAlias: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                version++
            }
        }

    override var color: Color = Color.Black
        set(value) {
            if (field != value) {
                field = value
                argb = value.toArgbFast()
                version++
            }
        }

    /** [color] converted to sRGB ARGB, which is what the canvas sends to the browser. */
    internal var argb: Int = Color.Black.toArgbFast()
        private set

    override var blendMode: BlendMode = BlendMode.SrcOver
        set(value) {
            if (field != value) {
                field = value
                version++
            }
        }

    override var style: PaintingStyle = PaintingStyle.Fill
        set(value) {
            if (field != value) {
                field = value
                version++
            }
        }

    override var strokeWidth: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                version++
            }
        }

    override var strokeCap: StrokeCap = StrokeCap.Butt
        set(value) {
            if (field != value) {
                field = value
                version++
            }
        }

    override var strokeJoin: StrokeJoin = StrokeJoin.Miter
        set(value) {
            if (field != value) {
                field = value
                version++
            }
        }

    override var strokeMiterLimit: Float = 4f
        set(value) {
            if (field != value) {
                field = value
                version++
            }
        }

    override var filterQuality: FilterQuality = FilterQuality.Medium
        set(value) {
            if (field != value) {
                field = value
                version++
            }
        }

    override var shader: Shader? = null
        set(value) {
            if (field !== value) {
                field = value
                version++
            }
        }

    override var colorFilter: ColorFilter? = null
        set(value) {
            if (field != value) {
                field = value
                version++
            }
        }

    override var pathEffect: PathEffect? = null
        set(value) {
            if (field != value) {
                field = value
                version++
            }
        }

    /** A blur of the drawn shape's coverage, set through `Paint.setBlurFilter`. */
    internal var blurFilter: WebBlurFilter? = null
        set(value) {
            if (field != value) {
                field = value
                version++
            }
        }

    /** An effect applied to a layer when it's composited, only used for layer paints. */
    internal var renderEffect: WebRenderEffect? = null
        set(value) {
            if (field != value) {
                field = value
                version++
            }
        }

    /** Returns an immutable copy of this paint, reused as long as the paint doesn't change. */
    internal fun snapshot(): WebPaint {
        val cached = cachedSnapshot
        if (cached != null && cachedSnapshotVersion == version) return cached
        val copy = WebPaint()
        copy.copyFrom(this)
        cachedSnapshot = copy
        cachedSnapshotVersion = version
        return copy
    }

    internal fun copyFrom(other: WebPaint) {
        color = other.color
        isAntiAlias = other.isAntiAlias
        blendMode = other.blendMode
        style = other.style
        strokeWidth = other.strokeWidth
        strokeCap = other.strokeCap
        strokeJoin = other.strokeJoin
        strokeMiterLimit = other.strokeMiterLimit
        filterQuality = other.filterQuality
        shader = other.shader
        colorFilter = other.colorFilter
        pathEffect = other.pathEffect
        blurFilter = other.blurFilter
        renderEffect = other.renderEffect
    }

    internal fun reset() {
        color = Color.Black
        isAntiAlias = true
        blendMode = BlendMode.SrcOver
        style = PaintingStyle.Fill
        strokeWidth = 0f
        strokeCap = StrokeCap.Butt
        strokeJoin = StrokeJoin.Miter
        strokeMiterLimit = 4f
        filterQuality = FilterQuality.Medium
        shader = null
        colorFilter = null
        pathEffect = null
        blurFilter = null
        renderEffect = null
    }
}

/** Casts a Compose [Paint] to the backend's paint, copying it when it comes from elsewhere. */
internal fun Paint.asWebPaint(): WebPaint =
    this as? WebPaint ?: WebPaint().also { copy ->
        copy.color = color
        copy.isAntiAlias = isAntiAlias
        copy.blendMode = blendMode
        copy.style = style
        copy.strokeWidth = strokeWidth
        copy.strokeCap = strokeCap
        copy.strokeJoin = strokeJoin
        copy.strokeMiterLimit = strokeMiterLimit
        copy.filterQuality = filterQuality
        copy.shader = shader
        copy.colorFilter = colorFilter
        copy.pathEffect = pathEffect
    }

/** [Color.toArgb] without the color space conversion for the (common) sRGB colors. */
internal fun Color.toArgbFast(): Int =
    if ((value and 0x3fUL) == 0UL) (value shr 32).toInt() else toArgb()
