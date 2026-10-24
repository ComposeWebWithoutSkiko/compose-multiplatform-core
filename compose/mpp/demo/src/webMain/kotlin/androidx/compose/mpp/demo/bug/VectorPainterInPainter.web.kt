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

package androidx.compose.mpp.demo.bug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

// The icon of drawable/ic_image_outline.xml, built in code.
@Composable
internal actual fun imageOutlinePainter(): Painter {
    val icon = remember {
        ImageVector.Builder(
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 5f)
                verticalLineTo(19f)
                lineTo(5f, 19f)
                lineTo(5f, 5f)
                horizontalLineTo(19f)
                moveTo(19f, 3f)
                lineTo(5f, 3f)
                curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f)
                verticalLineTo(19f)
                curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
                horizontalLineTo(19f)
                curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
                lineTo(21f, 5f)
                curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
                close()
                moveTo(14.14f, 11.86f)
                lineToRelative(-3f, 3.87f)
                lineTo(9f, 13.14f)
                lineTo(6f, 17f)
                horizontalLineTo(18f)
                lineToRelative(-3.86f, -5.14f)
                close()
            }
        }.build()
    }
    return rememberVectorPainter(icon)
}
