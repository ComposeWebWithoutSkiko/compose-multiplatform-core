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

package androidx.compose.ui.test

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The offscreen surface the content of a test is drawn into: a Skia raster surface on the
 * Skia-based targets, a canvas on the web.
 */
internal interface TestRenderSurface {
    /** Clears the surface to transparent. */
    fun clear()

    /** Lets [block] draw into the surface. */
    fun draw(block: (Canvas) -> Unit)

    /** Returns a copy of the surface content. */
    fun capture(): ImageBitmap

    /** Returns a copy of the given part of the surface content. */
    fun capture(left: Int, top: Int, right: Int, bottom: Int): ImageBitmap
}

internal expect fun createTestRenderSurface(width: Int, height: Int): TestRenderSurface

/** Registers the graphics and text implementation of the platform. */
internal expect fun registerTestComposeImplementation()

/** The current value of a monotonic clock, in nanoseconds. */
internal expect fun testNanoTime(): Long
