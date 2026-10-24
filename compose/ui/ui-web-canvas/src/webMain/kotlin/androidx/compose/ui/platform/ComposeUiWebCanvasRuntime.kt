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

package androidx.compose.ui.platform

import androidx.annotation.VisibleForTesting
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.platform.PlatformGraphicsRegistry
import androidx.compose.ui.text.platform.PlatformTextRegistry
import androidx.compose.ui.webcanvas.graphics.WebCanvasGraphics
import androidx.compose.ui.webcanvas.text.WebCanvasText

/**
 * Registers the web implementation of the Compose graphics and text primitives, which draws
 * through the browser's Canvas 2D API.
 */
@InternalComposeUiApi
fun registerWebCanvasComposeImplementation() {
    PlatformGraphicsRegistry.register(WebCanvasGraphics)
    PlatformTextRegistry.register(WebCanvasText)
}

/**
 * Unregisters the web implementation. Intended for tests to avoid leaking the registered
 * implementation as global state across test cases.
 */
@VisibleForTesting
@InternalComposeUiApi
fun clearWebCanvasComposeImplementation() {
    PlatformGraphicsRegistry.clear()
    PlatformTextRegistry.clear()
}
