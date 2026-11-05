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

import androidx.compose.ui.webcanvas.dom.JsStrings

/**
 * The shadows of a frame that the browser blurs and that no image of [ShadowSprites] serves (see
 * [ShadowPainter.blurs]), painted ahead of the frame into slots of pages of their own (see
 * [LayerAtlas]) and drawn from there.
 *
 * A shadow blurred where it's drawn, in a page of the atlas or in the window, slows down the frames
 * whose shadows change all the time, such as those of tilted shapes in a page that scales: the
 * larger the canvas the blur ran in, the further apart the frames came, measured in Chrome on a
 * Pixel 9 Pro, in the transition from the landing page of Emotecraft Library (emotes.redlance.org),
 * whose two tilted cards cast 4 shadows in a page that scales: 100 to 158 ms with the shadows
 * blurred in the page of the atlas the page is drawn into (4096 x 2304), 91 to 167 ms with each
 * clipped there to 1000 x 1000 pixels around it, 67 to 92 ms with each blurred in a canvas of its
 * own of 2048 x 2304, 50 to 83 ms of 1000 x 1000, and 42 to 67 ms without the blur. Where the time
 * goes isn't established: the traces of the phone show no stages of the GPU. In Chrome on an M4 Pro
 * (`micro/shadows.html` of compose-web-benchmark), 80 shadows whose blur changes in every frame
 * drew 172 to 186 frames per second blurred in place, 132 each blurred in a canvas of its own right
 * before it's drawn, 146 all blurred first into canvases of their own, 243 to 250 all blurred first
 * into one canvas of up to 4096 pixels, and 290 into canvases of up to 1024 pixels
 * ([LayerAtlas.ShadowPageSize]).
 *
 * A slot holds the device pixels of a shadow within the bounds of the clip, painted as they'd be
 * painted in place. Slots are found by everything their pixels depend on, so that a frame drawn
 * otherwise than planned paints in place only the shadows it doesn't find.
 */
internal class ShadowSlots {
    /** A shadow to paint ahead, and where it's painted. */
    class Slot {
        /** The shape casting the shadow (see [isSameShape]). */
        private var shape: ShadowShape? = null

        /** Places the shape in the root device space, before the offset. */
        private val matrix = Affine()
        private var offsetX = 0f
        private var offsetY = 0f
        private var sigma = 0f
        private var rgb = 0

        /** The device pixels of the slot: [width] x [height] at ([left], [top]). */
        var left = 0
            private set
        var top = 0
            private set
        var width = 0
            private set
        var height = 0
            private set

        /** Where the shadow is painted: at ([slotX], [slotY]) of [page], if the atlas had room. */
        var page: AtlasPage? = null
            private set
        var slotX = 0
            private set
        var slotY = 0
            private set

        /**
         * Sets this to the shadow of [shape], placed by [matrix] and then moved by ([offsetX],
         * [offsetY]) in the root device space, blurred by [sigma] pixels in the opaque color [rgb],
         * whose slot covers the [width] x [height] device pixels at ([left], [top]).
         */
        fun set(
            shape: ShadowShape,
            matrix: Affine,
            offsetX: Float,
            offsetY: Float,
            sigma: Float,
            rgb: Int,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
        ): Slot {
            this.shape = shape
            this.matrix.set(matrix)
            this.offsetX = offsetX
            this.offsetY = offsetY
            this.sigma = sigma
            this.rgb = rgb
            this.left = left
            this.top = top
            this.width = width
            this.height = height
            page = null
            return this
        }

        /** Whether this is the shadow of [other]: the same pixels in the same place. */
        fun isShadowOf(other: Slot): Boolean =
            left == other.left && top == other.top && width == other.width &&
                height == other.height && sigma == other.sigma && rgb == other.rgb &&
                offsetX == other.offsetX && offsetY == other.offsetY &&
                matrix.contentEquals(other.matrix) &&
                isSameShape(checkNotNull(shape), checkNotNull(other.shape))

        /**
         * Whether [a] and [b] are the same shape: the same object, or shapes with the same key,
         * the same up to a translation, at the same place. A shape that is made anew wherever it's
         * drawn, such as an outline projected in perspective, so finds its slot.
         */
        private fun isSameShape(a: ShadowShape, b: ShadowShape): Boolean {
            if (a === b) return true
            val key = a.key ?: return false
            return a.keyHash == b.keyHash && key == b.key &&
                a.path.left == b.path.left && a.path.top == b.path.top
        }

        fun place(page: AtlasPage, x: Int, y: Int) {
            this.page = page
            slotX = x
            slotY = y
        }

        /**
         * Paints the shadow into its slot, through the target of its page, clipped to the slot, as
         * it would be painted in place.
         */
        fun paint() {
            val target = checkNotNull(page).target
            // The slot shows the device pixels of the shadow.
            target.originX = left - slotX
            target.originY = top - slotY
            target.save()
            target.setPixelTransform()
            val ctx = target.ctx
            ctx.beginPath()
            ctx.rect(slotX.toDouble(), slotY.toDouble(), width.toDouble(), height.toDouble())
            ctx.clip(JsStrings.nonzero)
            target.setCompositeOperation(SourceOverOperation)
            target.setGlobalAlpha(1.0)
            ShadowPainter.paint(target, checkNotNull(shape), matrix, offsetX, offsetY, sigma, rgb)
            target.restore()
        }

        /** Drops the references to what the slot draws. */
        fun clear() {
            shape = null
            page = null
        }
    }

    /** The slots of the frame, then spare ones: they're reused from frame to frame. */
    private val slots = ArrayList<Slot>()

    /** The number of slots of the frame. */
    var count = 0
        private set

    /**
     * The slot the last search found, -1 before any: the shadows are met in the order they were
     * noted, so the next one is likely right after it.
     */
    private var lastFound = -1

    val isEmpty: Boolean get() = count == 0

    operator fun get(index: Int): Slot = slots[index]

    /** The slot of the next shadow of the frame, to set (see [Slot.set]). */
    fun next(): Slot {
        val slot = if (count < slots.size) slots[count] else Slot().also { slots.add(it) }
        count++
        return slot
    }

    /** Paints the shadows that have a place into their slots, and returns how many. */
    fun paint(): Int {
        var painted = 0
        for (i in 0 until count) {
            val slot = slots[i]
            if (slot.page == null) continue
            slot.paint()
            painted++
        }
        return painted
    }

    /**
     * Returns the slot of [shadow] painted ahead, searching from the one after the slot found last,
     * or `null` if there's none.
     */
    fun find(shadow: Slot): Slot? {
        for (i in 1..count) {
            val index = (lastFound + i) % count
            val slot = slots[index]
            if (slot.page != null && slot.isShadowOf(shadow)) {
                lastFound = index
                return slot
            }
        }
        return null
    }

    /** Forgets the slots, and what they draw. */
    fun clear() {
        for (i in 0 until count) slots[i].clear()
        count = 0
        lastFound = -1
    }
}
