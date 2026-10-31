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

package androidx.compose.ui.webcanvas.dom

import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.js
import kotlin.js.toJsString

// Minimal bindings to the parts of the Canvas 2D API the backend uses. They are declared here
// (instead of using kotlinx-browser) to reach APIs that are newer than the published bindings, such
// as `letterSpacing`, `filter`, `roundRect` or `createConicGradient`, and to pass JS strings that
// are converted once and cached on the Kotlin side.

/** A `CanvasRenderingContext2D` or an `OffscreenCanvasRenderingContext2D`. */
internal external interface Context2D : JsAny {
    val canvas: CanvasElement
    var globalAlpha: Double
    var globalCompositeOperation: JsString
    var fillStyle: JsAny
    var strokeStyle: JsAny
    var lineWidth: Double
    var lineCap: JsString
    var lineJoin: JsString
    var miterLimit: Double
    var lineDashOffset: Double
    var font: JsString
    var textAlign: JsString
    var textBaseline: JsString
    var direction: JsString
    var imageSmoothingEnabled: Boolean
    var imageSmoothingQuality: JsString
    var shadowColor: JsString
    var shadowBlur: Double
    var shadowOffsetX: Double
    var shadowOffsetY: Double
    var filter: JsString

    fun save()
    fun restore()
    fun setTransform(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double)
    fun transform(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double)

    fun beginPath()
    fun closePath()
    fun moveTo(x: Double, y: Double)
    fun lineTo(x: Double, y: Double)
    fun rect(x: Double, y: Double, w: Double, h: Double)
    fun arc(x: Double, y: Double, radius: Double, startAngle: Double, endAngle: Double, counterclockwise: Boolean)
    fun ellipse(
        x: Double,
        y: Double,
        radiusX: Double,
        radiusY: Double,
        rotation: Double,
        startAngle: Double,
        endAngle: Double,
        counterclockwise: Boolean,
    )

    fun fill(fillRule: JsString)
    fun fill(path: JsAny, fillRule: JsString)
    fun stroke()
    fun stroke(path: JsAny)
    fun clip(fillRule: JsString)
    fun clip(path: JsAny, fillRule: JsString)
    fun isPointInPath(path: JsAny, x: Double, y: Double, fillRule: JsString): Boolean

    fun fillRect(x: Double, y: Double, w: Double, h: Double)
    fun strokeRect(x: Double, y: Double, w: Double, h: Double)
    fun clearRect(x: Double, y: Double, w: Double, h: Double)

    fun fillText(text: JsString, x: Double, y: Double)
    fun strokeText(text: JsString, x: Double, y: Double)
    fun measureText(text: JsString): TextMetrics

    fun drawImage(image: JsAny, dx: Double, dy: Double)
    fun drawImage(image: JsAny, dx: Double, dy: Double, dw: Double, dh: Double)
    fun drawImage(
        image: JsAny,
        sx: Double,
        sy: Double,
        sw: Double,
        sh: Double,
        dx: Double,
        dy: Double,
        dw: Double,
        dh: Double,
    )

    fun createLinearGradient(x0: Double, y0: Double, x1: Double, y1: Double): CanvasGradient
    fun createRadialGradient(
        x0: Double,
        y0: Double,
        r0: Double,
        x1: Double,
        y1: Double,
        r1: Double,
    ): CanvasGradient
    fun createPattern(image: JsAny, repetition: JsString): CanvasPattern?

    fun getImageData(sx: Int, sy: Int, sw: Int, sh: Int): ImageData
    fun putImageData(data: ImageData, dx: Int, dy: Int)
    fun putImageData(
        data: ImageData,
        dx: Int,
        dy: Int,
        dirtyX: Int,
        dirtyY: Int,
        dirtyWidth: Int,
        dirtyHeight: Int,
    )
}

internal external interface CanvasGradient : JsAny {
    fun addColorStop(offset: Double, color: JsString)
}

internal external interface CanvasPattern : JsAny

internal external interface TextMetrics : JsAny {
    val width: Double
    val actualBoundingBoxLeft: Double
    val actualBoundingBoxRight: Double
    val actualBoundingBoxAscent: Double
    val actualBoundingBoxDescent: Double
}

/** The `fontBoundingBoxAscent` of [metrics]: NaN where the browser lacks it (Firefox before 116). */
// language=js
internal fun fontBoundingBoxAscentOf(metrics: TextMetrics): Double = js("+metrics.fontBoundingBoxAscent")

/** The `fontBoundingBoxDescent` of [metrics]: NaN where the browser lacks it (Firefox before 116). */
// language=js
internal fun fontBoundingBoxDescentOf(metrics: TextMetrics): Double = js("+metrics.fontBoundingBoxDescent")

internal external interface ImageData : JsAny {
    val width: Int
    val height: Int
}

/** An `HTMLCanvasElement` or an `OffscreenCanvas`. */
internal external interface CanvasElement : JsAny {
    var width: Int
    var height: Int
}

/** Creates a conic gradient, or returns `null` if the browser doesn't support them. */
// language=js
internal fun createConicGradientOrNull(
    ctx: Context2D,
    startAngle: Double,
    x: Double,
    y: Double,
): CanvasGradient? =
    js("(typeof ctx.createConicGradient === 'function') ? ctx.createConicGradient(startAngle, x, y) : null")

// language=js
internal fun setLineDash(ctx: Context2D, intervals: JsAny): Unit = js("ctx.setLineDash(intervals)")

// language=js
internal fun clearLineDash(ctx: Context2D): Unit = js("ctx.setLineDash([])")

/**
 * Sets the letter spacing of [ctx], a CSS length. Browsers without `ctx.letterSpacing` (Safari before
 * 18.4, Firefox before 115) ignore it in measurement and drawing alike.
 */
// language=js
internal fun setLetterSpacing(ctx: Context2D, value: JsString): Unit = js("ctx.letterSpacing = value")

// language=js
internal fun setFontKerning(ctx: Context2D, value: JsString): Unit = js("ctx.fontKerning = value")

/**
 * Adds a round rectangle with elliptical corners of the same radii to the current path. Uses
 * `roundRect` when available and falls back to elliptical arcs otherwise.
 */
// language=js
internal fun addRoundRect(
    ctx: Context2D,
    x: Double,
    y: Double,
    w: Double,
    h: Double,
    rx: Double,
    ry: Double,
): Unit = js(
    """{
        if (typeof ctx.roundRect === 'function') {
            ctx.roundRect(x, y, w, h, [{x: rx, y: ry}]);
        } else {
            const r = x + w, b = y + h;
            ctx.moveTo(x + rx, y);
            ctx.lineTo(r - rx, y);
            ctx.ellipse(r - rx, y + ry, rx, ry, 0, -Math.PI / 2, 0, false);
            ctx.lineTo(r, b - ry);
            ctx.ellipse(r - rx, b - ry, rx, ry, 0, 0, Math.PI / 2, false);
            ctx.lineTo(x + rx, b);
            ctx.ellipse(x + rx, b - ry, rx, ry, 0, Math.PI / 2, Math.PI, false);
            ctx.lineTo(x, y + ry);
            ctx.ellipse(x + rx, y + ry, rx, ry, 0, Math.PI, Math.PI * 1.5, false);
            ctx.closePath();
        }
    }"""
)

// language=js
internal fun setPatternTransform(
    pattern: CanvasPattern,
    a: Double,
    b: Double,
    c: Double,
    d: Double,
    e: Double,
    f: Double,
): Unit = js(
    "{ if (typeof pattern.setTransform === 'function') pattern.setTransform(new DOMMatrix([a, b, c, d, e, f])); }"
)

/**
 * Creates a `<canvas>` element of the given size, which isn't part of the page: unlike an
 * `OffscreenCanvas`, its 2D context resolves `url()` references in `ctx.filter`, to the document
 * (see [filterScopeOf] and [androidx.compose.ui.webcanvas.graphics.SvgColorFilters]). Falls back
 * to [createCanvas] without a document.
 */
// language=js
internal fun createElementCanvas(width: Int, height: Int): CanvasElement = js(
    """{
        if (typeof document === 'undefined') {
            return new OffscreenCanvas(width, height);
        }
        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        return canvas;
    }"""
)

/**
 * Creates a canvas to draw offscreen: an `OffscreenCanvas` when the browser supports it with a 2D
 * context, a detached `<canvas>` element otherwise.
 */
// language=js
internal fun createCanvas(width: Int, height: Int): CanvasElement = js(
    """{
        if (typeof OffscreenCanvas !== 'undefined') {
            return new OffscreenCanvas(width, height);
        }
        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        return canvas;
    }"""
)

/**
 * The node whose tree resolves the `url()` references of the filters of [ctx] (`ctx.filter`): the
 * shadow root of a `<canvas>` element in a shadow tree, or else its document. `null` for an
 * `OffscreenCanvas`, which resolves none.
 */
// language=js
internal fun filterScopeOf(ctx: Context2D): JsAny? = js(
    """{
        const canvas = ctx.canvas;
        if (typeof HTMLCanvasElement === 'undefined' || !(canvas instanceof HTMLCanvasElement)) return null;
        const root = canvas.getRootNode();
        return (typeof ShadowRoot !== 'undefined' && root instanceof ShadowRoot) ? root : canvas.ownerDocument;
    }"""
)

// language=js
internal fun getContext2D(canvas: JsAny, willReadFrequently: Boolean): Context2D? =
    js("canvas.getContext('2d', { willReadFrequently: willReadFrequently })")

/** The 2D context of a canvas created to draw offscreen, which the browser always provides. */
internal fun requireContext2D(canvas: JsAny, willReadFrequently: Boolean = false): Context2D =
    getContext2D(canvas, willReadFrequently) ?: error("Canvas 2D is not supported")

/** Frees the memory of [canvas] right away, rather than when it's collected. */
internal fun releaseCanvas(canvas: CanvasElement) {
    canvas.width = 0
    canvas.height = 0
}

/**
 * A context that throws whenever it's used: the context of a canvas that must not draw anything,
 * so that a draw reaching it fails instead of drawing.
 */
// language=js
internal fun createThrowingContext2D(): Context2D = js(
    """new Proxy({}, {
        get(target, name) { throw new Error('This context must not be used: ' + String(name)); },
        set(target, name) { throw new Error('This context must not be used: ' + String(name)); },
    })"""
)

/** A JS array of numbers, built incrementally from Kotlin. */
internal external interface JsNumberArray : JsAny

// language=js
internal fun newJsNumberArray(): JsNumberArray = js("[]")

// language=js
internal fun pushNumber(array: JsNumberArray, value: Double): Unit = js("array.push(value)")

/** Builds a JS array holding [values], suitable for `setLineDash`. */
internal fun FloatArray.toJsNumberArray(): JsAny {
    val array = newJsNumberArray()
    for (value in this) {
        pushNumber(array, value.toDouble())
    }
    return array
}

/** Feature detection results for the Canvas 2D API of the current browser. */
internal object CanvasFeatures {
    /** Whether `ctx.filter` is applied (missing in Safari, 27.2 included). */
    val filter: Boolean by lazy { detectCanvasFilter() }
}

// language=js
private fun detectCanvasFilter(): Boolean = js(
    """{
        try {
            const canvas = (typeof OffscreenCanvas !== 'undefined')
                ? new OffscreenCanvas(1, 1)
                : document.createElement('canvas');
            const ctx = canvas.getContext('2d');
            if (!ctx || !('filter' in ctx)) return false;
            ctx.filter = 'blur(1px)';
            return ctx.filter === 'blur(1px)';
        } catch (e) {
            return false;
        }
    }"""
)

/** Frequently used JS strings, converted once. */
internal object JsStrings {
    val nonzero = "nonzero".toJsString()
    val evenodd = "evenodd".toJsString()
    val empty = "".toJsString()
    val none = "none".toJsString()

    val butt = "butt".toJsString()
    val round = "round".toJsString()
    val square = "square".toJsString()
    val miter = "miter".toJsString()
    val bevel = "bevel".toJsString()

    val left = "left".toJsString()
    val alphabetic = "alphabetic".toJsString()
    val ltr = "ltr".toJsString()
    val rtl = "rtl".toJsString()
    val normal = "normal".toJsString()
    val zeroPx = "0px".toJsString()

    val low = "low".toJsString()
    val medium = "medium".toJsString()
    val high = "high".toJsString()

    val repeat = "repeat".toJsString()
    val repeatX = "repeat-x".toJsString()
    val repeatY = "repeat-y".toJsString()
    val noRepeat = "no-repeat".toJsString()

    val transparent = "rgba(0,0,0,0)".toJsString()
}
