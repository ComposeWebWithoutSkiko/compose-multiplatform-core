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

import androidx.compose.ui.webcanvas.dom.CanvasElement
import androidx.compose.ui.webcanvas.dom.createCanvas
import androidx.compose.ui.webcanvas.dom.releaseCanvas
import androidx.compose.ui.webcanvas.dom.requireContext2D

/**
 * The shadows of elevated layers, painted once and from then on drawn as images: they would be
 * painted again in every frame otherwise (see [ShadowPainter]).
 *
 * A shadow gets its image the second time its shape casts it (see [Requests]), so that shadows
 * that change in every frame (elevation, size or transformation animations, the resizing of the
 * window, which moves the light) are painted directly, and the others are painted once. Images are
 * keyed by what their pixels depend on: the shape up to a translation, the linear part of the
 * transformation, the blur and the color, so that shapes alike share them. The alpha is applied
 * when an image is drawn, so fading doesn't make new ones.
 *
 * The images drawn least recently make room for new ones, except those drawn since the previous
 * frame of the renderer drawing (see [startFrame]): when these fill the budget, no image is made,
 * and the shadows that don't fit are painted directly, instead of images being made and evicted
 * in every frame. Drawing outside of frames, the images kept are those drawn since the previous
 * frame of the renderer that started a frame last.
 */
internal object ShadowSprites {
    /** A painted shadow at full alpha, keyed by [key], last drawn at the tick [lastDrawn]. */
    class Sprite(
        val key: Key,
        val canvas: CanvasElement,
        val width: Int,
        val height: Int,
        var lastDrawn: Double,
    )

    /**
     * What the pixels of a sprite depend on. The keys of the sprites are never changed: shadows
     * are looked up with a key reused for every lookup, and a copy of it is kept.
     */
    class Key {
        private var shape: Any? = null
        private var shapeHash = 0
        private var a = 0f
        private var b = 0f
        private var c = 0f
        private var d = 0f
        private var sigma = 0f
        private var rgb = 0

        fun set(shape: ShadowShape, matrix: Affine, sigma: Float, rgb: Int): Key {
            this.shape = shape.key
            shapeHash = shape.keyHash
            a = matrix.a
            b = matrix.b
            c = matrix.c
            d = matrix.d
            this.sigma = sigma
            this.rgb = rgb
            return this
        }

        fun setFrom(other: Key) {
            shape = other.shape
            shapeHash = other.shapeHash
            a = other.a
            b = other.b
            c = other.c
            d = other.d
            sigma = other.sigma
            rgb = other.rgb
        }

        fun copy(): Key = Key().also { it.setFrom(this) }

        override fun equals(other: Any?): Boolean =
            other is Key &&
                shapeHash == other.shapeHash &&
                a == other.a &&
                b == other.b &&
                c == other.c &&
                d == other.d &&
                sigma == other.sigma &&
                rgb == other.rgb &&
                shape == other.shape

        override fun hashCode(): Int {
            var hash = shapeHash
            hash = 31 * hash + a.hashBits()
            hash = 31 * hash + b.hashBits()
            hash = 31 * hash + c.hashBits()
            hash = 31 * hash + d.hashBits()
            hash = 31 * hash + sigma.hashBits()
            return 31 * hash + rgb
        }
    }

    /**
     * The last two shadows a layer was asked for (its ambient and its spot shadow): a shadow asked
     * for again gets an image. The layer keeps them through the shapes its outline makes, new for
     * every new outline object (see [ShadowShape.spriteRequests]).
     */
    class Requests {
        private val first = Key()
        private val second = Key()
        private var replacesFirst = true

        /** Whether [key] was asked for last time; remembers it otherwise. */
        fun isRepeated(key: Key): Boolean {
            if (key == first || key == second) return true
            (if (replacesFirst) first else second).setFrom(key)
            replacesFirst = !replacesFirst
            return false
        }
    }

    private const val BudgetPixels = 4 * 1024 * 1024

    /** The largest sprite: a fraction of the budget, so that a new one never needs all the room. */
    const val MaxSpritePixels = BudgetPixels / 4

    /** The sprites, the ones drawn least recently first. */
    private val sprites = LinkedHashMap<Key, Sprite>()
    private var pixels = 0

    /** The key of every lookup. */
    private val probe = Key()

    /**
     * Counts the frames of all the renderers. A double counts exactly as far as needed, and is a
     * number in JavaScript, unlike a long.
     */
    private var tick = 0.0

    /** The sprites drawn at this tick or later aren't evicted. */
    private var keptSince = 0.0

    /** The number of images made since the last [clear]. */
    internal var created = 0
        private set

    internal fun clear() {
        for (sprite in sprites.values) releaseCanvas(sprite.canvas)
        sprites.clear()
        pixels = 0
        created = 0
    }

    /**
     * Starts a frame of a renderer whose previous frame started at [previousStart] (0 for its
     * first frame), and returns the start of this one. Until the next frame, the sprites drawn
     * since [previousStart] are kept: those of its previous frame and of the frames of other
     * renderers since then.
     */
    fun startFrame(previousStart: Double): Double {
        keptSince = previousStart
        return ++tick
    }

    /**
     * Returns the image of the shadow of [shape], transformed by the linear part of [matrix] and
     * blurred with [sigma] (device pixels) in the opaque color [rgb], or `null` if the shadow
     * should be painted directly: when the shape has no key, the first time, or when there's no
     * room. The image is [width] x [height] pixels, at most [MaxSpritePixels], its top-left corner
     * at ([left], [top]) in the device space of [matrix].
     */
    fun spriteFor(
        shape: ShadowShape,
        matrix: Affine,
        sigma: Float,
        rgb: Int,
        left: Float,
        top: Float,
        width: Int,
        height: Int,
    ): Sprite? {
        if (shape.key == null) return null
        val key = probe.set(shape, matrix, sigma, rgb)
        sprites[key]?.let { sprite ->
            if (sprite.lastDrawn != tick) {
                sprites.remove(sprite.key)
                sprites[sprite.key] = sprite
                sprite.lastDrawn = tick
            }
            return sprite
        }
        val requests = shape.spriteRequests ?: return null
        if (!requests.isRepeated(key)) return null
        val size = width * height
        // Still remembered by the shape: it gets an image once there's room.
        if (!makeRoom(size)) return null
        val canvas = createCanvas(width, height)
        ShadowPainter.paint(
            DrawTarget(requireContext2D(canvas), 0, 0, width, height),
            shape, matrix, -left, -top, sigma, rgb,
        )
        val sprite = Sprite(key.copy(), canvas, width, height, lastDrawn = tick)
        sprites[sprite.key] = sprite
        pixels += size
        created++
        return sprite
    }

    /**
     * Evicts the sprites drawn least recently, but none of those kept (see [startFrame]), until
     * [size] more pixels fit in the budget. Returns whether they fit.
     */
    private fun makeRoom(size: Int): Boolean {
        val eldestFirst = sprites.values.iterator()
        while (pixels + size > BudgetPixels) {
            val sprite = eldestFirst.next()
            if (sprite.lastDrawn >= keptSince) return false
            eldestFirst.remove()
            pixels -= sprite.width * sprite.height
            releaseCanvas(sprite.canvas)
        }
        return true
    }
}
