/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.compose.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlin.test.Test
import kotlin.test.assertTrue

class TextTests : OnCanvasTests {

    private fun assertBaselineFitsFontSize(fontSize: Float, baseline: Float) {
        assertTrue(
            baseline in fontSize * 0.7f..fontSize * 1.4f,
            "The baseline $baseline doesn't match the ascent of a $fontSize font",
        )
    }

    @Test
    // https://github.com/JetBrains/compose-multiplatform/issues/4078
    fun baselineShouldBeNotZero() = runApplicationTest {
        val headingOnPositioned = mutableStateOf(10f)
        val subtitleOnPositioned = mutableStateOf(10f)

        createComposeWindow {
            val density = LocalDensity.current.density
            Row {
                Text(
                    "Heading",
                    modifier = Modifier.alignByBaseline()
                        .onGloballyPositioned {
                            headingOnPositioned.value = it[FirstBaseline] / density
                        },
                    style = MaterialTheme.typography.h4
                )
                Text(
                    " — Subtitle",
                    modifier = Modifier.alignByBaseline()
                        .onGloballyPositioned {
                            subtitleOnPositioned.value = it[FirstBaseline] / density
                        },
                    style = MaterialTheme.typography.subtitle1
                )
            }
        }

        awaitIdle()

        // The exact baselines depend on the font, which is the browser's system font, and on the
        // line height of the style: check that they are where the ascent of a font of that size
        // puts them, give or take the extra line height (h4: 34sp, subtitle1: 16sp).
        assertBaselineFitsFontSize(fontSize = 34f, baseline = headingOnPositioned.value)
        assertBaselineFitsFontSize(fontSize = 16f, baseline = subtitleOnPositioned.value)
    }
}