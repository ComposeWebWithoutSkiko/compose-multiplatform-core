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
import kotlin.js.JsAny
import kotlin.js.JsReference
import kotlin.js.js
import kotlin.js.toJsReference
import kotlin.js.unsafeCast

/**
 * The [Paint] of the web backend. It is a plain holder of drawing parameters: the canvas applies
 * them to the browser's 2D context when it draws.
 *
 * Paints are mutable and Compose reuses them between draw calls, so the recording canvas stores
 * [snapshot]s: immutable paints shared by all the equal paints.
 */
internal class WebPaint : Paint {
    /** Changes whenever a property changes: an unchanged paint finds its snapshot at once. */
    private var version: Int = 0

    private var cachedSnapshot: WebPaint? = null
    private var cachedSnapshotVersion: Int = -1

    /** Whether this is a snapshot, shared by the paints equal to it, which must not change. */
    private var isShared = false

    private fun changed() {
        check(!isShared) { "A shared paint must not change" }
        version++
    }

    override var alpha: Float
        get() = color.alpha
        set(value) {
            color = color.copy(alpha = value)
        }

    override var isAntiAlias: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                changed()
            }
        }

    override var color: Color = Color.Black
        set(value) {
            if (field != value) {
                field = value
                argb = value.toArgbFast()
                changed()
            }
        }

    /** [color] converted to sRGB ARGB, which is what the canvas sends to the browser. */
    internal var argb: Int = Color.Black.toArgbFast()
        private set

    override var blendMode: BlendMode = BlendMode.SrcOver
        set(value) {
            if (field != value) {
                field = value
                changed()
            }
        }

    override var style: PaintingStyle = PaintingStyle.Fill
        set(value) {
            if (field != value) {
                field = value
                changed()
            }
        }

    override var strokeWidth: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                changed()
            }
        }

    override var strokeCap: StrokeCap = StrokeCap.Butt
        set(value) {
            if (field != value) {
                field = value
                changed()
            }
        }

    override var strokeJoin: StrokeJoin = StrokeJoin.Miter
        set(value) {
            if (field != value) {
                field = value
                changed()
            }
        }

    override var strokeMiterLimit: Float = 4f
        set(value) {
            if (field != value) {
                field = value
                changed()
            }
        }

    override var filterQuality: FilterQuality = FilterQuality.Medium
        set(value) {
            if (field != value) {
                field = value
                changed()
            }
        }

    override var shader: Shader? = null
        set(value) {
            if (field !== value) {
                field = value
                changed()
            }
        }

    override var colorFilter: ColorFilter? = null
        set(value) {
            if (field != value) {
                field = value
                appliedColorFilter = value?.webColorFilter?.applied
                changed()
            }
        }

    /**
     * The color filter that drawing with this paint applies: that of [colorFilter], but none for a
     * tint that Skia makes no filter for ([WebColorFilter.Tint.isNoOpBlend]).
     */
    internal var appliedColorFilter: WebColorFilter? = null
        private set

    override var pathEffect: PathEffect? = null
        set(value) {
            if (field != value) {
                field = value
                changed()
            }
        }

    /** A blur of the drawn shape's coverage, set through `Paint.setBlurFilter`. */
    internal var blurFilter: WebBlurFilter? = null
        set(value) {
            if (field != value) {
                field = value
                changed()
            }
        }

    /** An effect applied to a layer when it's composited, only used for layer paints. */
    internal var renderEffect: WebRenderEffect? = null
        set(value) {
            if (field != value) {
                field = value
                changed()
            }
        }

    /**
     * Returns an immutable paint equal to this one: the same for all equal paints (see
     * [SharedPaints]), so that recording a paint copies nothing once a paint like it was recorded.
     */
    internal fun snapshot(): WebPaint {
        if (isShared) return this
        val cached = cachedSnapshot
        if (cached != null && cachedSnapshotVersion == version) return cached
        val shared = SharedPaints.of(this)
        cachedSnapshot = shared
        cachedSnapshotVersion = version
        return shared
    }

    /** Whether this paint draws like [other]. */
    internal fun isEqualTo(other: WebPaint): Boolean =
        color == other.color && isAntiAlias == other.isAntiAlias &&
            blendMode == other.blendMode && style == other.style &&
            strokeWidth == other.strokeWidth && strokeCap == other.strokeCap &&
            strokeJoin == other.strokeJoin && strokeMiterLimit == other.strokeMiterLimit &&
            filterQuality == other.filterQuality && shader === other.shader &&
            colorFilter == other.colorFilter && pathEffect == other.pathEffect &&
            blurFilter == other.blurFilter && renderEffect == other.renderEffect

    /** A hash code of what [isEqualTo] compares. */
    internal fun stateHashCode(): Int {
        var hash = color.hashCode()
        hash = 31 * hash + isAntiAlias.hashCode()
        hash = 31 * hash + blendMode.hashCode()
        hash = 31 * hash + style.hashCode()
        hash = 31 * hash + strokeWidth.hashCode()
        hash = 31 * hash + strokeCap.hashCode()
        hash = 31 * hash + strokeJoin.hashCode()
        hash = 31 * hash + strokeMiterLimit.hashCode()
        hash = 31 * hash + filterQuality.hashCode()
        hash = 31 * hash + shader.hashCode()
        hash = 31 * hash + colorFilter.hashCode()
        hash = 31 * hash + pathEffect.hashCode()
        hash = 31 * hash + blurFilter.hashCode()
        hash = 31 * hash + renderEffect.hashCode()
        return hash
    }

    internal companion object {
        /** A new snapshot equal to [paint], to share (see [SharedPaints]). */
        fun newSnapshot(paint: WebPaint): WebPaint =
            WebPaint().also {
                it.copyFrom(paint)
                it.isShared = true
            }
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

/**
 * The snapshots of paints (see [WebPaint.snapshot]), one per state: Compose draws with a few paints
 * that it changes from draw to draw (`CanvasDrawScope` has one to fill and one to stroke), whose
 * states repeat from frame to frame. A state seen for the first time gets a snapshot of its own.
 * The table only finds the snapshots, holding them weakly: a snapshot lives as long as the display
 * lists and paints that use it, and so do its shader and effects, which may hold rasters and
 * images.
 */
private object SharedPaints {
    /** The size of the hash table, a power of two, twice the most slots it fills. */
    private const val Size = 4096
    private const val MaxCount = Size / 2

    /** The snapshots, as JS `WeakRef`s. */
    private val snapshots = arrayOfNulls<JsAny>(Size)

    /** The hash codes of the states of [snapshots]: a lookup dereferences only those of its own. */
    private val hashes = IntArray(Size)

    /** The slots filled since the table was last emptied, whose snapshots may be gone since. */
    private var count = 0

    fun of(paint: WebPaint): WebPaint {
        val hash = paint.stateHashCode()
        var i = hash and (Size - 1)
        // A slot of this hash code whose snapshot is gone, to fill again.
        var gone = -1
        while (true) {
            val weakRef = snapshots[i] ?: break
            if (hashes[i] == hash) {
                val snapshot = deref(weakRef)?.unsafeCast<JsReference<WebPaint>>()?.get()
                if (snapshot == null) {
                    if (gone < 0) gone = i
                } else if (snapshot.isEqualTo(paint)) {
                    return snapshot
                }
            }
            i = (i + 1) and (Size - 1)
        }
        if (gone >= 0) {
            i = gone
        } else {
            if (count == MaxCount) {
                // The snapshots in use stay with the display lists, only their references go.
                snapshots.fill(null)
                count = 0
                i = hash and (Size - 1)
            }
            count++
        }
        val snapshot = WebPaint.newSnapshot(paint)
        snapshots[i] = newWeakRef(snapshot.toJsReference())
        hashes[i] = hash
        return snapshot
    }
}

// language=js
private fun newWeakRef(target: JsAny): JsAny = js("new WeakRef(target)")

// language=js
private fun deref(weakRef: JsAny): JsAny? = js("weakRef.deref()")

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
