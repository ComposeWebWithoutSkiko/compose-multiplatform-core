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

package androidx.compose.ui.webcanvas

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.webcanvas.dom.ImageData
import androidx.compose.ui.webcanvas.graphics.assertSamePixels
import androidx.compose.ui.webcanvas.graphics.imageData
import kotlin.test.BeforeTest
import kotlin.test.Test

class TransformedShaderTest {
    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    private fun drawn(shader: Shader): ImageData {
        val image = ImageBitmap(Size, Size)
        Canvas(image).drawRect(0f, 0f, Size.toFloat(), Size.toFloat(), Paint().also { it.shader = shader })
        return image.imageData()
    }

    @Test
    fun aMatrixAppliesAfterTheTransformationTheShaderHas() {
        // Moved by 4, then scaled by 2: 0..8 goes to 8..24 (scaled first, it would go to 4..20).
        val gradient = LinearGradientShader(Offset.Zero, Offset(8f, 0f), Colors)
        val moved = transformedShader(gradient, Matrix().apply { translate(4f) })
        val scaled = transformedShader(moved, Matrix().apply { scale(2f, 2f) })
        assertSamePixels(drawn(LinearGradientShader(Offset(8f, 0f), Offset(24f, 0f), Colors)), drawn(scaled))
    }

    @Test
    fun aRadialGradientScaledByTwoIsTheRadialGradientOfTheCircleScaledByTwo() {
        val scaled = transformedShader(RadialGradientShader(Offset(8f, 8f), 8f, Colors), Matrix().apply { scale(2f, 2f) })
        assertSamePixels(drawn(RadialGradientShader(Offset(16f, 16f), 16f, Colors)), drawn(scaled))
    }

    private companion object {
        const val Size = 32
        val Colors = listOf(Color(0xFF102080), Color(0xFFF0E040), Color(0xFF40A0F0))
    }
}
