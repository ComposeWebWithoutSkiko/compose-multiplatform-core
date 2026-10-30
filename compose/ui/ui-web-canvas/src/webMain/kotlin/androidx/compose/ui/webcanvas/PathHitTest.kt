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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.webcanvas.path.asWebPath
import androidx.compose.ui.webcanvas.path.contains

/**
 * Returns true if [position] is inside this path according to its fill type, by the browser's hit
 * test of the `Path2D` the path is drawn with: points on the outline are inside, like with Skia's
 * `SkPath::contains`.
 */
@InternalComposeUiApi
public fun Path.isPointInPath(position: Offset): Boolean =
    asWebPath().currentData().contains(position.x, position.y)
