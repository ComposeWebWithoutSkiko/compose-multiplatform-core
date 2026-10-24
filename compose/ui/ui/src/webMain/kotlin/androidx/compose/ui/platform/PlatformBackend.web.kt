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
import androidx.compose.ui.webcanvas.WebCanvasGraphicsContext
import androidx.compose.ui.webcanvas.text.WebFontResourceLoader
import kotlin.js.js

@OptIn(InternalComposeUiApi::class)
internal actual fun createPlatformGraphicsContext(
    measureDrawBounds: Boolean,
    snapshotCache: Boolean,
): PlatformGraphicsContext = WebCanvasGraphicsContext()

@Suppress("DEPRECATION")
@OptIn(InternalComposeUiApi::class)
internal actual fun createPlatformFontLoader(): Font.ResourceLoader = WebFontResourceLoader()

internal actual fun platformNanoTime(): Long = (performanceNow() * 1_000_000.0).toLong()

@Suppress("DEPRECATION")
internal actual fun currentPlatformSystemTheme(): SystemTheme =
    if (prefersDarkColorScheme()) SystemTheme.Dark else SystemTheme.Light

// language=js
private fun performanceNow(): Double = js("performance.now()")

// language=js
private fun prefersDarkColorScheme(): Boolean =
    js("!!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches)")
