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

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.graphics.platform.PlatformGraphicsContext
import androidx.compose.ui.text.font.Font

// The bridge between the shared non-Android code and the rendering backend of a particular
// platform family: the Skia-based targets (desktop, native) are backed by ui-skiko, while the web
// targets draw through the browser's Canvas 2D API, backed by ui-web-canvas.

/**
 * Creates the graphics context the root of a Compose hierarchy records its layers with.
 *
 * @param measureDrawBounds whether layers should compute the bounds of their drawing
 * @param snapshotCache whether layers may cache the result of their drawing
 */
@OptIn(InternalComposeUiApi::class)
internal expect fun createPlatformGraphicsContext(
    measureDrawBounds: Boolean,
    snapshotCache: Boolean,
): PlatformGraphicsContext

/** The cache that the compositions of this platform share graphics resources through, if any. */
internal expect val platformGraphicsResourceCache: GraphicsResourceCache?

/** Creates the deprecated [Font.ResourceLoader] provided through `LocalFontLoader`. */
@Suppress("DEPRECATION")
internal expect fun createPlatformFontLoader(): Font.ResourceLoader

/** The current value of the monotonic clock used to timestamp synthetic events, in nanoseconds. */
internal expect fun platformNanoTime(): Long

/** The theme (light or dark) the system currently uses. */
@Suppress("DEPRECATION")
internal expect fun currentPlatformSystemTheme(): SystemTheme
