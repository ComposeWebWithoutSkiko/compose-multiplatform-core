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

package androidx.compose.ui.webcanvas

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.webcanvas.graphics.WebRenderEffect
import androidx.compose.ui.webcanvas.graphics.webColorFilter
import androidx.compose.ui.webcanvas.graphics.webRenderEffect
import androidx.compose.ui.webcanvas.graphics.webShader

// The render effects that libraries build out of `ImageFilter` on Skia.

/** An effect that replaces a layer with [shader], in the space of the content of the layer. */
@InternalComposeUiApi
public fun shaderRenderEffect(shader: Shader): RenderEffect =
    WebRenderEffect.ShaderFill(shader.webShader).asComposeRenderEffect()

/** An effect that applies [colorFilter] to the result of [input], or to the layer if `null`. */
@InternalComposeUiApi
public fun colorFilterRenderEffect(colorFilter: ColorFilter, input: RenderEffect?): RenderEffect =
    WebRenderEffect.ColorFiltered(input?.webRenderEffect, colorFilter.webColorFilter)
        .asComposeRenderEffect()

/**
 * An effect that draws the result of [foreground] over the one of [background] in [blendMode], the
 * layer standing for a `null` one.
 */
@InternalComposeUiApi
public fun blendRenderEffect(
    blendMode: BlendMode,
    background: RenderEffect?,
    foreground: RenderEffect?,
): RenderEffect =
    WebRenderEffect.Blend(blendMode, background?.webRenderEffect, foreground?.webRenderEffect)
        .asComposeRenderEffect()
