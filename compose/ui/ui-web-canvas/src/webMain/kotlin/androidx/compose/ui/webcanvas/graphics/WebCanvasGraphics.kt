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

@file:OptIn(InternalComposeUiApi::class)

package androidx.compose.ui.webcanvas.graphics

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathIterator
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.StampedPathEffectStyle
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.platform.PlatformBlurFilter
import androidx.compose.ui.graphics.platform.PlatformColorFilter
import androidx.compose.ui.graphics.platform.PlatformGraphics
import androidx.compose.ui.graphics.platform.PlatformRenderEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.webcanvas.path.WebPath
import androidx.compose.ui.webcanvas.path.WebPathIterator
import androidx.compose.ui.webcanvas.path.WebPathMeasure
import androidx.compose.ui.webcanvas.path.createChainPathEffect as chainPathEffect
import androidx.compose.ui.webcanvas.path.createCornerPathEffect as cornerPathEffect
import androidx.compose.ui.webcanvas.path.createDashPathEffect as dashPathEffect
import androidx.compose.ui.webcanvas.path.createStampedPathEffect as stampedPathEffect
import kotlin.math.hypot

/** The graphics primitives of the web backend, drawing through the Canvas 2D API. */
internal object WebCanvasGraphics : PlatformGraphics {
    override fun createCanvas(image: ImageBitmap): Canvas = WebCanvas(image.asCanvasImage().drawTarget)

    override fun createPaint(): Paint = WebPaint()

    // Every blend mode can be drawn (Modulate is approximated by Multiply).
    override fun isBlendModeSupported(blendMode: BlendMode): Boolean = true

    // Gradients emulate all the tile modes; image shaders approximate Clamp with Decal.
    override fun isTileModeSupported(tileMode: TileMode): Boolean = true

    override fun createPath(): Path = WebPath()

    override fun createPathIterator(
        path: Path,
        conicEvaluation: PathIterator.ConicEvaluation,
        tolerance: Float,
    ): PathIterator = WebPathIterator(path, conicEvaluation, tolerance)

    override fun createPathMeasure(): PathMeasure = WebPathMeasure()

    override fun createImageBitmap(
        width: Int,
        height: Int,
        config: ImageBitmapConfig,
        hasAlpha: Boolean,
        colorSpace: ColorSpace,
    ): ImageBitmap {
        require(width > 0 && height > 0) { "width and height must be > 0" }
        return CanvasImageBitmap(width, height, config, hasAlpha, colorSpace)
    }

    override fun decodeImageBitmap(bytes: ByteArray): ImageBitmap = ImageDecoding.decode(bytes)

    override fun createLinearGradientShader(
        from: Offset,
        to: Offset,
        colors: List<Color>,
        colorStops: List<Float>?,
        tileMode: TileMode,
    ): Shader =
        gradientShader(colors, colorStops, tileMode, size = hypot(to.x - from.x, to.y - from.y)) {
            LinearGradientShader(from, to, it, tileMode)
        }

    override fun createRadialGradientShader(
        center: Offset,
        radius: Float,
        colors: List<Color>,
        colorStops: List<Float>?,
        tileMode: TileMode,
    ): Shader =
        gradientShader(colors, colorStops, tileMode, size = radius) {
            RadialGradientShader(center, radius, it, tileMode)
        }

    /**
     * The shader of a gradient of [colors] at [colorStops], of length or radius [size], made by
     * [geometric] from its stops unless, like Skia, it's drawn without its geometry: when it has one
     * color, or no size (see [degenerateGradientShader]).
     */
    private inline fun gradientShader(
        colors: List<Color>,
        colorStops: List<Float>?,
        tileMode: TileMode,
        size: Float,
        geometric: (GradientStops) -> WebShader,
    ): Shader {
        validateColorStops(colors, colorStops)
        return Shader(
            when {
                colors.size == 1 -> ColorShader(colors[0].toArgb())
                size <= DegenerateGradientSize -> degenerateGradientShader(colors, colorStops, tileMode)
                else -> geometric(GradientStops(colors, colorStops))
            }
        )
    }

    override fun createSweepGradientShader(
        center: Offset,
        colors: List<Color>,
        colorStops: List<Float>?,
    ): Shader {
        validateColorStops(colors, colorStops)
        return Shader(SweepGradientShader(center, GradientStops(colors, colorStops)))
    }

    override fun createImageShader(
        image: ImageBitmap,
        tileModeX: TileMode,
        tileModeY: TileMode,
    ): Shader {
        val webImage = image as? WebImageBitmap
            ?: throw UnsupportedOperationException("Unsupported image: $image")
        return Shader(ImageShader(webImage, tileModeX, tileModeY))
    }

    override fun createCompositeShader(
        destination: Shader,
        source: Shader,
        blendMode: BlendMode,
    ): Shader = Shader(CompositeShader(destination.webShader, source.webShader, blendMode))

    override fun transformShader(shader: Shader, matrix: Matrix): Shader =
        Shader(shader.webShader.transformed(matrix.toAffine()))

    override fun createBlurRenderEffect(
        renderEffect: RenderEffect?,
        radiusX: Float,
        radiusY: Float,
        edgeTreatment: TileMode,
    ): PlatformRenderEffect =
        WebRenderEffect.Blur(renderEffect?.webRenderEffect, radiusX, radiusY, edgeTreatment)

    override fun createOffsetRenderEffect(
        renderEffect: RenderEffect?,
        offset: Offset,
    ): PlatformRenderEffect = WebRenderEffect.Translate(renderEffect?.webRenderEffect, offset)

    override fun createBlurFilter(radius: Float): PlatformBlurFilter = WebBlurFilter(radius)

    override fun setBlurFilter(paint: Paint, blur: PlatformBlurFilter?) {
        require(blur == null || blur is WebBlurFilter) {
            "Expected WebBlurFilter, got ${blur?.let { it::class }}"
        }
        (paint as WebPaint).blurFilter = blur
    }

    override fun createTintColorFilter(color: Color, blendMode: BlendMode): PlatformColorFilter =
        WebColorFilter.Tint(color, blendMode)

    override fun createColorMatrixColorFilter(colorMatrix: ColorMatrix): PlatformColorFilter =
        colorMatrix.toWebColorFilter()

    override fun createLightingColorFilter(multiply: Color, add: Color): PlatformColorFilter =
        WebColorFilter.Lighting(multiply, add)

    override fun colorMatrixFromFilter(filter: PlatformColorFilter): ColorMatrix =
        when (filter) {
            is WebColorFilter.Matrix -> ColorMatrix(filter.values.copyOf())
            else -> ColorMatrix()
        }

    override fun createCornerPathEffect(radius: Float): PathEffect = cornerPathEffect(radius)

    override fun createDashPathEffect(intervals: FloatArray, phase: Float): PathEffect =
        dashPathEffect(intervals, phase)

    override fun createChainPathEffect(outer: PathEffect, inner: PathEffect): PathEffect =
        chainPathEffect(outer, inner)

    override fun createStampedPathEffect(
        shape: Path,
        advance: Float,
        phase: Float,
        style: StampedPathEffectStyle,
    ): PathEffect = stampedPathEffect(shape, advance, phase, style)
}
