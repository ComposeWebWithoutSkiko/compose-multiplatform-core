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

import kotlin.math.max

/**
 * Lays out rectangles side by side on shelves, [LayerAtlas.Gutter] pixels apart, within a square:
 * the slots of a page of the atlas (see [LayerAtlas]), and the separate parts of the canvas of the
 * shared WebGL context that slots are drawn into at once (see [PerspectiveProjector],
 * [SlotsThroughWebGL]).
 */
internal class Shelves {
    private var maxSize = 0
    private var shelfX = 0
    private var shelfY = 0
    private var shelfHeight = 0

    /** Where the rectangle placed last is. */
    var x = 0
        private set
    var y = 0
        private set

    /** The part of the square that the rectangles take. */
    var width = 0
        private set
    val height: Int get() = shelfY + shelfHeight

    /** Starts a layout within a square of [maxSize] pixels. */
    fun reset(maxSize: Int) {
        this.maxSize = maxSize
        shelfX = 0
        shelfY = 0
        shelfHeight = 0
        width = 0
    }

    /**
     * Places a [width] x [height] rectangle at ([x], [y]) and returns `true`, or returns `false` if
     * it doesn't fit, leaving the layout as it was.
     */
    fun place(width: Int, height: Int): Boolean {
        // On this shelf, or else on the next one, below it.
        val wraps = shelfX + width > maxSize
        val x = if (wraps) 0 else shelfX
        val y = if (wraps) shelfY + shelfHeight + LayerAtlas.Gutter else shelfY
        if (width > maxSize || y + height > maxSize) return false
        this.x = x
        this.y = y
        shelfX = x + width + LayerAtlas.Gutter
        shelfY = y
        shelfHeight = if (wraps) height else max(shelfHeight, height)
        this.width = max(this.width, shelfX - LayerAtlas.Gutter)
        return true
    }
}
