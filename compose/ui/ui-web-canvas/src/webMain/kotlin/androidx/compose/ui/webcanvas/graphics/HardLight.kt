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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.webcanvas.dom.CanvasFeatures

/**
 * The two draws of a paint in [BlendMode.Softlight] where drawing in soft-light reads the canvas back
 * ([CanvasFeatures.softLightReadsBack]): hard-light instead, as [multiply] and [screen], two
 * operations drawn without reading the canvas back. Hard-light is `b·2c` for `c ≤ 0.5` and
 * `screen(2c − 1, b)` otherwise; [multiply] draws the dark half of the colors of the source,
 * `min(2c, 1)`, in Multiply, and [screen] its light half, `max(2c − 1, 0)`, in Screen (see
 * [HardLightHalves]). In every color channel of every pixel one of them leaves the backdrop as it is
 * (a multiplication by 1, a screen of 0), so they draw hard-light exactly over an opaque backdrop.
 * Over a translucent one each also leaves its source: over a transparent backdrop, a source of alpha
 * `a` ends up of alpha `a·(2 − a)`, its colors a mix of the halves.
 *
 * The halves of an image are made of its pixels (see [WebImageBitmap.filteredSource]), so they apply
 * before the image is sampled rather than after.
 *
 * Blends of render effects in soft-light are drawn as hard-light too (see [HardLightForegrounds]).
 * These are still drawn in soft-light: layers composited in it, also in perspective, tints in it
 * (applied in place, or to copies of images), the blends whose foreground [HardLightForegrounds]
 * can't take, [CompositeShader] in it, text with a shadow, whose color doesn't go through the paint,
 * and paints whose color filter is a tint applied as a compositing operation, which the halves can't
 * follow.
 */
internal class HardLightPasses(val multiply: WebPaint, val screen: WebPaint)

/**
 * The foreground of a [WebRenderEffect.Blend] in soft-light drawn as hard-light, like
 * [HardLightPasses]: [multiply] in Multiply, then [screen] in Screen, each the foreground with the
 * dark or the light half ([HardLightHalves]) applied to the pixels of the image its shader fills.
 * Only a foreground filling with an image a pattern tiles has them: through tints that only scale
 * its alpha, which the halves of its colors commute with, and through offsets.
 */
internal class HardLightForegrounds private constructor(
    val multiply: WebRenderEffect,
    val screen: WebRenderEffect,
) {
    internal companion object {
        /** The hard-light draws of [foreground], or `null` if it hasn't any. */
        fun of(foreground: WebRenderEffect?): HardLightForegrounds? {
            val halves = HardLightHalves.plain
            val multiply = foreground.through(halves.dark.webColorFilter) ?: return null
            val screen = foreground.through(halves.light.webColorFilter) ?: return null
            return HardLightForegrounds(multiply, screen)
        }

        /** This effect with [half] applied to the pixels of the image it fills with, if it can be. */
        private fun WebRenderEffect?.through(half: WebColorFilter): WebRenderEffect? =
            when (this) {
                is WebRenderEffect.ShaderFill -> (shader as? ImageShader)
                    ?.takeIf { !it.mayNeedRasterization }
                    ?.let { WebRenderEffect.ShaderFill(FilteredImageShader(it, half)) }
                is WebRenderEffect.ColorFiltered -> {
                    val filter = filter
                    when {
                        // A tint that does nothing: the input as it is.
                        filter == null -> input.through(half)
                        alphaScale != null -> input.through(half)?.let { WebRenderEffect.ColorFiltered(it, filter) }
                        else -> null
                    }
                }
                is WebRenderEffect.Translate -> input.through(half)?.let { WebRenderEffect.Translate(it, offset) }
                is WebRenderEffect.Blur, is WebRenderEffect.Blend, null -> null
            }
    }
}

/**
 * The color filters making the halves of the colors of a source for [HardLightPasses]: [dark],
 * `min(2c, 1)`, and [light], `max(2c − 1, 0)`, on each color channel, the alpha kept.
 */
internal class HardLightHalves private constructor(val dark: ColorFilter, val light: ColorFilter) {
    internal companion object {
        /** The halves of the colors of a source drawn without a color filter. */
        val plain: HardLightHalves by lazy {
            of(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        }

        /**
         * The halves of the colors after the color matrix [matrix], as [colorMatrixOf] gives it (its
         * translation column from 0 to 1). The halves saturate where the colors are clamped, so the
         * products with [matrix] apply them after it.
         */
        fun of(matrix: FloatArray): HardLightHalves =
            HardLightHalves(halfOf(matrix, offset = 0f), halfOf(matrix, offset = -255f))

        /**
         * The color rows of [matrix] doubled and moved by [offset], its alpha row kept, in Compose's
         * layout: the translation column from 0 to 255.
         */
        private fun halfOf(matrix: FloatArray, offset: Float): ColorFilter {
            val values = FloatArray(20) { i ->
                val isColorRow = i < 15
                val value = if (isColorRow) 2 * matrix[i] else matrix[i]
                when {
                    i % 5 != 4 -> value
                    isColorRow -> value * 255 + offset
                    else -> value * 255
                }
            }
            return ColorFilter.colorMatrix(ColorMatrix(values))
        }
    }
}
