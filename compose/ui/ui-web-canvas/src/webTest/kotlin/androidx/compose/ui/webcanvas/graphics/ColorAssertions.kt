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

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.assertTrue

internal fun assertColor(
    expected: Color,
    actual: Color,
    tolerance: Float = 0.02f,
    message: String? = null,
) {
    assertTrue(
        abs(expected.red - actual.red) <= tolerance &&
            abs(expected.green - actual.green) <= tolerance &&
            abs(expected.blue - actual.blue) <= tolerance &&
            abs(expected.alpha - actual.alpha) <= tolerance,
        "${message?.let { "$it: " } ?: ""}expected $expected but was $actual",
    )
}
