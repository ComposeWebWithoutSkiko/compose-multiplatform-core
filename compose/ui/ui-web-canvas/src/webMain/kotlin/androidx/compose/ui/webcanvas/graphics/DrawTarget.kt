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
import androidx.compose.ui.webcanvas.dom.CanvasElement
import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.CssColors
import androidx.compose.ui.webcanvas.dom.JsStrings
import androidx.compose.ui.webcanvas.dom.createElementCanvas
import androidx.compose.ui.webcanvas.dom.releaseCanvas
import androidx.compose.ui.webcanvas.dom.requireContext2D
import androidx.compose.ui.webcanvas.dom.setLetterSpacing
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.toJsString

/**
 * A surface [WebCanvas] draws into: a 2D context together with the position of its top-left pixel
 * in the device space of the canvas (offscreen layers are positioned within the root surface).
 *
 * It caches the state last applied to the context, to skip the (relatively expensive) calls
 * setting the same value again. So a context has a single target, which all the canvases drawing
 * into it share, its state is saved and restored through [save] and [restore], and code changing
 * the state of the context outside of it calls [invalidateState] or [invalidateTransform]
 * afterwards, unless it changes the state only between a `ctx.save()` and its `ctx.restore()`,
 * which bring the state back as it was known.
 */
internal class DrawTarget(
    val ctx: Context2D,
    var originX: Int,
    var originY: Int,
    var width: Int,
    var height: Int,
) {
    /** Called before drawing, e.g. to mark the image this target draws into as changed. */
    var onDraw: (() -> Unit)? = null

    /**
     * The state last applied to the context, as far as it's known: an unknown value (not valid,
     * NaN or null) is applied again.
     *
     * Transient state (shadow, filter, line dash) is always reset right after the draw that needs
     * it, so it's at its default value at every `save()` and needs no tracking.
     */
    private class State {
        // The transformation.
        var tValid = false
        var ta = 0f
        var tb = 0f
        var tc = 0f
        var td = 0f
        var te = 0f
        var tf = 0f

        var globalAlpha = Double.NaN
        var compositeOperation: JsString? = null

        var fillColor = 0
        var fillColorValid = false
        var fillStyle: JsAny? = null

        var strokeColor = 0
        var strokeColorValid = false
        var strokeStyle: JsAny? = null

        var lineWidth = Double.NaN
        var lineCap: JsString? = null
        var lineJoin: JsString? = null
        var miterLimit = Double.NaN

        var imageSmoothing: Int = -1 // -1: unknown, 0: disabled, 1..3: low, medium, high

        var font: JsString? = null
        var letterSpacing: JsString? = null
        var direction: JsString? = null
        var isTextAlignLeft = false

        fun invalidate() {
            tValid = false
            globalAlpha = Double.NaN
            compositeOperation = null
            fillColorValid = false
            fillStyle = null
            strokeColorValid = false
            strokeStyle = null
            lineWidth = Double.NaN
            lineCap = null
            lineJoin = null
            miterLimit = Double.NaN
            imageSmoothing = -1
            font = null
            letterSpacing = null
            direction = null
            isTextAlignLeft = false
        }

        fun set(other: State) {
            tValid = other.tValid
            ta = other.ta
            tb = other.tb
            tc = other.tc
            td = other.td
            te = other.te
            tf = other.tf
            globalAlpha = other.globalAlpha
            compositeOperation = other.compositeOperation
            fillColor = other.fillColor
            fillColorValid = other.fillColorValid
            fillStyle = other.fillStyle
            strokeColor = other.strokeColor
            strokeColorValid = other.strokeColorValid
            strokeStyle = other.strokeStyle
            lineWidth = other.lineWidth
            lineCap = other.lineCap
            lineJoin = other.lineJoin
            miterLimit = other.miterLimit
            imageSmoothing = other.imageSmoothing
            font = other.font
            letterSpacing = other.letterSpacing
            direction = other.direction
            isTextAlignLeft = other.isTextAlignLeft
        }
    }

    private var state = State()

    /** The states saved by [save], the last one last; the ones past [savedCount] are spare. */
    private val saved = ArrayList<State>()
    private var savedCount = 0

    /** Whether a [save] is open, and with it possibly a clip. */
    val hasSavedState: Boolean get() = savedCount > 0

    /** Saves the state of the context (`ctx.save()`), which [restore] brings back. */
    fun save() {
        ctx.save()
        if (savedCount == saved.size) saved.add(State())
        saved[savedCount++].set(state)
    }

    /** Brings back the state of the context saved last (`ctx.restore()`), known as it was. */
    fun restore() {
        ctx.restore()
        val restored = saved[--savedCount]
        saved[savedCount] = state
        state = restored
    }

    /** Forgets the cached state, after the state of the context was changed directly. */
    fun invalidateState() {
        state.invalidate()
    }

    /** Forgets the transformation set on the context, after it was changed directly. */
    fun invalidateTransform() {
        state.tValid = false
    }

    /** Sets the context transformation to [m], which is expressed in the root device space. */
    fun setTransform(m: Affine) {
        val s = state
        val e = m.e - originX
        val f = m.f - originY
        if (s.tValid && s.ta == m.a && s.tb == m.b && s.tc == m.c && s.td == m.d && s.te == e && s.tf == f) {
            return
        }
        ctx.setTransform(
            m.a.toDouble(),
            m.b.toDouble(),
            m.c.toDouble(),
            m.d.toDouble(),
            e.toDouble(),
            f.toDouble(),
        )
        s.tValid = true
        s.ta = m.a
        s.tb = m.b
        s.tc = m.c
        s.td = m.d
        s.te = e
        s.tf = f
    }

    /** Sets an identity transformation in the pixel space of this target. */
    fun setPixelTransform() {
        val s = state
        if (s.tValid && s.ta == 1f && s.tb == 0f && s.tc == 0f && s.td == 1f && s.te == 0f && s.tf == 0f) {
            return
        }
        ctx.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        s.tValid = true
        s.ta = 1f
        s.tb = 0f
        s.tc = 0f
        s.td = 1f
        s.te = 0f
        s.tf = 0f
    }

    fun setGlobalAlpha(value: Double) {
        if (state.globalAlpha != value) {
            ctx.globalAlpha = value
            state.globalAlpha = value
        }
    }

    fun setCompositeOperation(value: JsString) {
        if (state.compositeOperation !== value) {
            ctx.globalCompositeOperation = value
            state.compositeOperation = value
        }
    }

    fun setFillColor(argb: Int) {
        val s = state
        if (!s.fillColorValid || s.fillColor != argb) {
            ctx.fillStyle = CssColors.of(argb)
            s.fillColor = argb
            s.fillColorValid = true
            s.fillStyle = null
        }
    }

    fun setFillStyle(style: JsAny) {
        val s = state
        if (s.fillStyle !== style) {
            ctx.fillStyle = style
            s.fillStyle = style
            s.fillColorValid = false
        }
    }

    fun setStrokeColor(argb: Int) {
        val s = state
        if (!s.strokeColorValid || s.strokeColor != argb) {
            ctx.strokeStyle = CssColors.of(argb)
            s.strokeColor = argb
            s.strokeColorValid = true
            s.strokeStyle = null
        }
    }

    fun setStrokeStyle(style: JsAny) {
        val s = state
        if (s.strokeStyle !== style) {
            ctx.strokeStyle = style
            s.strokeStyle = style
            s.strokeColorValid = false
        }
    }

    fun setLineWidth(value: Double) {
        if (state.lineWidth != value) {
            ctx.lineWidth = value
            state.lineWidth = value
        }
    }

    fun setLineCap(value: JsString) {
        if (state.lineCap !== value) {
            ctx.lineCap = value
            state.lineCap = value
        }
    }

    fun setLineJoin(value: JsString) {
        if (state.lineJoin !== value) {
            ctx.lineJoin = value
            state.lineJoin = value
        }
    }

    fun setMiterLimit(value: Double) {
        if (state.miterLimit != value) {
            ctx.miterLimit = value
            state.miterLimit = value
        }
    }

    /** 0: disabled, 1: low, 2: medium, 3: high. */
    fun setImageSmoothing(level: Int) {
        if (state.imageSmoothing == level) return
        if (level == 0) {
            ctx.imageSmoothingEnabled = false
        } else {
            ctx.imageSmoothingEnabled = true
            ctx.imageSmoothingQuality = when (level) {
                1 -> JsStrings.low
                2 -> JsStrings.medium
                else -> JsStrings.high
            }
        }
        state.imageSmoothing = level
    }

    fun setFont(value: JsString) {
        if (state.font !== value) {
            ctx.font = value
            state.font = value
        }
    }

    /** Sets the spacing between the letters of the text drawn next, a CSS length. */
    fun setLetterSpacing(value: JsString) {
        if (state.letterSpacing !== value) {
            setLetterSpacing(ctx, value)
            state.letterSpacing = value
        }
    }

    /**
     * Sets the direction of the text drawn next. Text is always positioned by its left end, which
     * the default alignment (`start`) would turn into the right end of right-to-left text.
     */
    fun setDirection(value: JsString) {
        val s = state
        if (s.direction !== value) {
            ctx.direction = value
            s.direction = value
        }
        if (!s.isTextAlignLeft) {
            ctx.textAlign = JsStrings.left
            s.isTextAlignLeft = true
        }
    }
}

/** The composite operation drawing with [blendMode], or `null` for the modes handled specially. */
internal fun compositeOperationFor(blendMode: BlendMode): JsString? = CompositeOperations.of(blendMode)

private object CompositeOperations {
    private val values: Array<JsString?> = Array(29) { null }

    init {
        fun put(mode: BlendMode, operation: String) {
            values[mode.ordinalValue()] = operation.toJsString()
        }
        put(BlendMode.Src, "copy")
        put(BlendMode.SrcOver, "source-over")
        put(BlendMode.DstOver, "destination-over")
        put(BlendMode.SrcIn, "source-in")
        put(BlendMode.DstIn, "destination-in")
        put(BlendMode.SrcOut, "source-out")
        put(BlendMode.DstOut, "destination-out")
        put(BlendMode.SrcAtop, "source-atop")
        put(BlendMode.DstAtop, "destination-atop")
        put(BlendMode.Xor, "xor")
        put(BlendMode.Plus, "lighter")
        // Canvas has no "modulate" (s * d on every channel); multiply is the closest operation.
        put(BlendMode.Modulate, "multiply")
        put(BlendMode.Screen, "screen")
        put(BlendMode.Overlay, "overlay")
        put(BlendMode.Darken, "darken")
        put(BlendMode.Lighten, "lighten")
        put(BlendMode.ColorDodge, "color-dodge")
        put(BlendMode.ColorBurn, "color-burn")
        put(BlendMode.Hardlight, "hard-light")
        put(BlendMode.Softlight, "soft-light")
        put(BlendMode.Difference, "difference")
        put(BlendMode.Exclusion, "exclusion")
        put(BlendMode.Multiply, "multiply")
        put(BlendMode.Hue, "hue")
        put(BlendMode.Saturation, "saturation")
        put(BlendMode.Color, "color")
        put(BlendMode.Luminosity, "luminosity")
        // Clear is drawn with destination-out and an opaque color.
        put(BlendMode.Clear, "destination-out")
    }

    val sourceOver: JsString = values[BlendMode.SrcOver.ordinalValue()]!!
    val copy: JsString = values[BlendMode.Src.ordinalValue()]!!

    fun of(mode: BlendMode): JsString? = values.getOrNull(mode.ordinalValue())
}

internal val SourceOverOperation: JsString get() = CompositeOperations.sourceOver

/** Replaces the destination pixels with the drawn ones, transparent ones included. */
internal val CopyOperation: JsString get() = CompositeOperations.copy

/**
 * Whether drawing with [blendMode] would change pixels outside of the drawn shape: the Canvas 2D
 * API composites these modes over the whole clip, while Skia (and Compose) restricts them to the
 * shape.
 */
internal fun isUnboundedBlendMode(blendMode: BlendMode): Boolean =
    blendMode == BlendMode.Src || blendMode == BlendMode.SrcIn || blendMode == BlendMode.DstIn ||
        blendMode == BlendMode.SrcOut || blendMode == BlendMode.DstAtop

/** The number of the blend mode, from 0 (Clear) to 28 (Luminosity): BlendMode is a value class. */
internal fun BlendMode.ordinalValue(): Int = when (this) {
    BlendMode.Clear -> 0
    BlendMode.Src -> 1
    BlendMode.Dst -> 2
    BlendMode.SrcOver -> 3
    BlendMode.DstOver -> 4
    BlendMode.SrcIn -> 5
    BlendMode.DstIn -> 6
    BlendMode.SrcOut -> 7
    BlendMode.DstOut -> 8
    BlendMode.SrcAtop -> 9
    BlendMode.DstAtop -> 10
    BlendMode.Xor -> 11
    BlendMode.Plus -> 12
    BlendMode.Modulate -> 13
    BlendMode.Screen -> 14
    BlendMode.Overlay -> 15
    BlendMode.Darken -> 16
    BlendMode.Lighten -> 17
    BlendMode.ColorDodge -> 18
    BlendMode.ColorBurn -> 19
    BlendMode.Hardlight -> 20
    BlendMode.Softlight -> 21
    BlendMode.Difference -> 22
    BlendMode.Exclusion -> 23
    BlendMode.Multiply -> 24
    BlendMode.Hue -> 25
    BlendMode.Saturation -> 26
    BlendMode.Color -> 27
    BlendMode.Luminosity -> 28
    else -> 3
}

/**
 * A pool of offscreen canvases backing the layers of [WebCanvas.saveLayer] and the intermediate
 * images of their effects. Layers are created and dropped every frame (e.g. for a fading
 * animation), so their canvases are reused. They're `<canvas>` elements, whose contexts resolve
 * the SVG filters that layers are drawn into them with (see [createElementCanvas]).
 */
internal object LayerCanvasPool {
    private class Entry(
        val canvas: CanvasElement,
        val ctx: Context2D,
        val width: Int,
        val height: Int,
    )

    /** The free canvases, the one returned longest ago first. */
    private val free = ArrayList<Entry>()

    /**
     * What the free canvases may take. Pixels are the only limit: a limit of canvases below what a
     * frame leases would have every canvas returned push out the one that the next lease needs.
     */
    private const val MaxFreePixels = 4096 * 4096

    class Lease internal constructor(
        val canvas: CanvasElement,
        val ctx: Context2D,
        internal val capacityWidth: Int,
        internal val capacityHeight: Int,
    )

    /**
     * Returns a cleared canvas of at least the given size, whose context has the default
     * transformation, alpha, compositing operation and image smoothing.
     */
    fun acquire(width: Int, height: Int): Lease {
        var bestIndex = -1
        var bestArea = Long.MAX_VALUE
        for (i in free.indices) {
            val entry = free[i]
            if (entry.width >= width && entry.height >= height) {
                val area = entry.width.toLong() * entry.height
                // Of equal sizes the one returned last, so that the others age out of the pool.
                if (area <= bestArea) {
                    bestArea = area
                    bestIndex = i
                }
            }
        }
        if (bestIndex >= 0) {
            val entry = free.removeAt(bestIndex)
            val ctx = entry.ctx
            ctx.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            ctx.globalAlpha = 1.0
            ctx.globalCompositeOperation = SourceOverOperation
            ctx.imageSmoothingEnabled = true
            ctx.imageSmoothingQuality = JsStrings.low
            // Clearing the whole canvas lets the browser drop its previous content, instead of
            // keeping a copy for the draws that still refer to it.
            ctx.clearRect(0.0, 0.0, entry.width.toDouble(), entry.height.toDouble())
            return Lease(entry.canvas, ctx, entry.width, entry.height)
        }
        // Round up so that slightly different sizes (e.g. during animations) share canvases.
        val capacityWidth = roundUp(width)
        val capacityHeight = roundUp(height)
        val canvas = createElementCanvas(capacityWidth, capacityHeight)
        return Lease(canvas, requireContext2D(canvas), capacityWidth, capacityHeight)
    }

    fun release(lease: Lease) {
        if (lease.capacityWidth.toLong() * lease.capacityHeight > MaxFreePixels) {
            releaseCanvas(lease.canvas)
            return
        }
        free.add(Entry(lease.canvas, lease.ctx, lease.capacityWidth, lease.capacityHeight))
        var pixels = free.sumOf { it.width.toLong() * it.height }
        while (pixels > MaxFreePixels) {
            val oldest = free.removeAt(0)
            pixels -= oldest.width.toLong() * oldest.height
            releaseCanvas(oldest.canvas)
        }
    }

    private fun roundUp(size: Int): Int = ((size + 63) / 64) * 64
}
