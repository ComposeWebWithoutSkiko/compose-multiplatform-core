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

package androidx.compose.ui.draw

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class DropShadowWebTest {
    @Test
    fun aTranslucentShadowKeepsItsAlpha() {
        val scene = ImageComposeScene(
            60,
            60,
            content = {
                Box(
                    Modifier.padding(10.dp).size(40.dp)
                        .dropShadow(RectangleShape, Shadow(4.dp, Color.Red.copy(alpha = 0.5f))),
                )
            },
        )
        scene.use {
            val argb = IntArray(1)
            it.render().readPixels(argb, 30, 30, width = 1, height = 1)
            val pixel = Color(argb[0])
            assertTrue(abs(pixel.alpha - 0.5f) <= 0.02f, "expected an alpha of 0.5 but was $pixel")
            assertTrue(pixel.red > 0.95f && pixel.green < 0.05f && pixel.blue < 0.05f, "expected red but was $pixel")
        }
    }
}
