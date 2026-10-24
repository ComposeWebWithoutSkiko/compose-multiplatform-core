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

package androidx.compose.ui.webcanvas.graphics

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.webcanvas.dom.CanvasElement
import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.CssColors
import androidx.compose.ui.webcanvas.dom.JsStrings
import androidx.compose.ui.webcanvas.dom.createCanvas
import androidx.compose.ui.webcanvas.dom.createElementCanvas
import androidx.compose.ui.webcanvas.dom.getContext2D
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.toJsString

/**
 * A surface [WebCanvas] draws into: a 2D context together with the position of its top-left pixel
 * in the device space of the canvas (offscreen layers are positioned within the root surface).
 *
 * It caches the state last applied to the context, to skip the (relatively expensive) calls
 * setting the same value again.
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

    // Transformation currently set on the context.
    private var tValid = false
    private var ta = 0f
    private var tb = 0f
    private var tc = 0f
    private var td = 0f
    private var te = 0f
    private var tf = 0f

    private var globalAlpha = Double.NaN
    private var compositeOperation: JsString? = null

    private var fillColor = 0
    private var fillColorValid = false
    private var fillStyle: JsAny? = null

    private var strokeColor = 0
    private var strokeColorValid = false
    private var strokeStyle: JsAny? = null

    private var lineWidth = Double.NaN
    private var lineCap: JsString? = null
    private var lineJoin: JsString? = null
    private var miterLimit = Double.NaN

    private var imageSmoothing: Int = -1 // -1: unknown, 0: disabled, 1..3: low, medium, high

    private var font: JsString? = null
    private var direction: JsString? = null
    private var isTextAlignLeft = false

    /**
     * Forgets the cached state, which is needed after `ctx.restore()`: the context returns to the
     * state of the matching `save()`, which isn't tracked.
     *
     * Transient state (shadow, filter, line dash, letter spacing) is always reset right after the
     * draw that needs it, so it's at its default value at every `save()` and needs no tracking.
     */
    fun invalidateState() {
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
        direction = null
        isTextAlignLeft = false
    }

    /** Forgets the transformation set on the context, after it was changed directly. */
    fun invalidateTransform() {
        tValid = false
    }

    /** Sets the context transformation to [m], which is expressed in the root device space. */
    fun setTransform(m: Affine) {
        val e = m.e - originX
        val f = m.f - originY
        if (tValid && ta == m.a && tb == m.b && tc == m.c && td == m.d && te == e && tf == f) {
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
        tValid = true
        ta = m.a
        tb = m.b
        tc = m.c
        td = m.d
        te = e
        tf = f
    }

    /** Sets an identity transformation in the pixel space of this target. */
    fun setPixelTransform() {
        if (tValid && ta == 1f && tb == 0f && tc == 0f && td == 1f && te == 0f && tf == 0f) return
        ctx.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        tValid = true
        ta = 1f
        tb = 0f
        tc = 0f
        td = 1f
        te = 0f
        tf = 0f
    }

    fun setGlobalAlpha(value: Double) {
        if (globalAlpha != value) {
            ctx.globalAlpha = value
            globalAlpha = value
        }
    }

    fun setCompositeOperation(value: JsString) {
        if (compositeOperation !== value) {
            ctx.globalCompositeOperation = value
            compositeOperation = value
        }
    }

    fun setFillColor(argb: Int) {
        if (!fillColorValid || fillColor != argb) {
            ctx.fillStyle = CssColors.of(argb)
            fillColor = argb
            fillColorValid = true
            fillStyle = null
        }
    }

    fun setFillStyle(style: JsAny) {
        if (fillStyle !== style) {
            ctx.fillStyle = style
            fillStyle = style
            fillColorValid = false
        }
    }

    fun setStrokeColor(argb: Int) {
        if (!strokeColorValid || strokeColor != argb) {
            ctx.strokeStyle = CssColors.of(argb)
            strokeColor = argb
            strokeColorValid = true
            strokeStyle = null
        }
    }

    fun setStrokeStyle(style: JsAny) {
        if (strokeStyle !== style) {
            ctx.strokeStyle = style
            strokeStyle = style
            strokeColorValid = false
        }
    }

    fun setLineWidth(value: Double) {
        if (lineWidth != value) {
            ctx.lineWidth = value
            lineWidth = value
        }
    }

    fun setLineCap(value: JsString) {
        if (lineCap !== value) {
            ctx.lineCap = value
            lineCap = value
        }
    }

    fun setLineJoin(value: JsString) {
        if (lineJoin !== value) {
            ctx.lineJoin = value
            lineJoin = value
        }
    }

    fun setMiterLimit(value: Double) {
        if (miterLimit != value) {
            ctx.miterLimit = value
            miterLimit = value
        }
    }

    /** 0: disabled, 1: low, 2: medium, 3: high. */
    fun setImageSmoothing(level: Int) {
        if (imageSmoothing == level) return
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
        imageSmoothing = level
    }

    fun setFont(value: JsString) {
        if (font !== value) {
            ctx.font = value
            font = value
        }
    }

    /**
     * Sets the direction of the text drawn next. Text is always positioned by its left end, which
     * the default alignment (`start`) would turn into the right end of right-to-left text.
     */
    fun setDirection(value: JsString) {
        if (direction !== value) {
            ctx.direction = value
            direction = value
        }
        if (!isTextAlignLeft) {
            ctx.textAlign = JsStrings.left
            isTextAlignLeft = true
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

// BlendMode is a value class over an Int: 0 Clear .. 28 Luminosity.
private fun BlendMode.ordinalValue(): Int = when (this) {
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
 * animation), so their canvases are reused.
 */
internal object LayerCanvasPool {
    private class Entry(
        val canvas: CanvasElement,
        val ctx: Context2D,
        val width: Int,
        val height: Int,
        val isElement: Boolean,
    )

    private val free = ArrayList<Entry>()
    private const val MaxFreeEntries = 8
    private const val MaxFreePixels = 4096 * 4096

    class Lease internal constructor(
        val canvas: CanvasElement,
        val ctx: Context2D,
        internal val capacityWidth: Int,
        internal val capacityHeight: Int,
        internal val isElement: Boolean,
    )

    /**
     * Returns a cleared canvas of at least the given size, whose context has the default
     * transformation, alpha and compositing operation. [element] asks for a `<canvas>` element,
     * whose context resolves `url()` filters (see [createElementCanvas]).
     */
    fun acquire(width: Int, height: Int, element: Boolean = false): Lease {
        var bestIndex = -1
        var bestArea = Long.MAX_VALUE
        for (i in free.indices) {
            val entry = free[i]
            if (entry.isElement == element && entry.width >= width && entry.height >= height) {
                val area = entry.width.toLong() * entry.height
                if (area < bestArea) {
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
            // Clearing the whole canvas lets the browser drop its previous content, instead of
            // keeping a copy for the draws that still refer to it.
            ctx.clearRect(0.0, 0.0, entry.width.toDouble(), entry.height.toDouble())
            return Lease(entry.canvas, ctx, entry.width, entry.height, element)
        }
        // Round up so that slightly different sizes (e.g. during animations) share canvases.
        val capacityWidth = roundUp(width)
        val capacityHeight = roundUp(height)
        val canvas = if (element) {
            createElementCanvas(capacityWidth, capacityHeight)
        } else {
            createCanvas(capacityWidth, capacityHeight)
        }
        val ctx = getContext2D(canvas, willReadFrequently = false) ?: error("Canvas 2D is not supported")
        return Lease(canvas, ctx, capacityWidth, capacityHeight, element)
    }

    fun release(lease: Lease) {
        val pixels = free.sumOf { it.width.toLong() * it.height } +
            lease.capacityWidth.toLong() * lease.capacityHeight
        if (free.size >= MaxFreeEntries || pixels > MaxFreePixels) {
            // Drop the canvas; the browser frees its memory once it's unreachable.
            lease.canvas.width = 0
            lease.canvas.height = 0
            return
        }
        free.add(
            Entry(lease.canvas, lease.ctx, lease.capacityWidth, lease.capacityHeight, lease.isElement)
        )
    }

    private fun roundUp(size: Int): Int = ((size + 63) / 64) * 64
}
