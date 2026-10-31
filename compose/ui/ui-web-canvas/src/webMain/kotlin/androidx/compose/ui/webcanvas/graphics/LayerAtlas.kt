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
import androidx.compose.ui.webcanvas.dom.createElementCanvas
import androidx.compose.ui.webcanvas.dom.releaseCanvas
import androidx.compose.ui.webcanvas.dom.requireContext2D
import kotlin.js.JsAny
import kotlin.math.max
import kotlin.math.min

/**
 * The pages that the content of the offscreen layers of a frame is drawn into (see [FramePlan]),
 * a set of pages per level of nesting: a level is drawn from the level below it, and drawing into
 * a canvas that was just drawn as an image costs a synchronization with the GPU every time.
 *
 * @param maxPixels the pixels the layers of a frame can take in the pages
 */
internal class LayerAtlas(private val maxPixels: Long = MaxPixels) {
    /** What the pages of a level hold, apart from each other, in the order they're laid out. */
    private enum class SlotKind {
        /** The slots of the layers composited as they are. */
        Layers,

        /**
         * The slots of the layers in perspective, whose pages WebGL uploads (see
         * [PerspectiveProjector]), so no larger than a texture.
         */
        Perspective,

        /**
         * The images of the blurs of the layers prepared ahead (see [FramePlan.Job.blur]), after
         * the slots they're made from.
         */
        Blurs,

        /** The images of the foregrounds of the effects of layers ([FramePlan.Job.foreground]). */
        Foregrounds,

        /** The images the effects of layers work in ([FramePlan.Job.result]), after what they read. */
        Results,
    }

    /** The pages of each level, of each kind. */
    private val levels = ArrayList<Array<AtlasLevel>>()

    /**
     * Gives the jobs of [plan] their slots, and the images of their blurs and effects to the ones
     * that prepare them, level by level as long as [maxPixels] last, and clears the pages that get
     * slots. The jobs without a slot are drawn where they're met. Pages that go unused for a while
     * are released, so this is called for every frame, layers or not.
     */
    fun layOut(plan: FramePlan) {
        while (levels.size < plan.levelCount) {
            levels.add(Array(SlotKind.entries.size) { AtlasLevel(SlotKind.entries[it]) })
        }
        var pixelsLeft = maxPixels
        for (level in levels.indices) {
            // Levels deeper than the plan's lay out no job: their pages go unused.
            val jobs = plan.jobsAt(level)
            for (pages in levels[level]) pixelsLeft -= pages.layOut(jobs, pixelsLeft)
        }
    }

    /** The pages of a level holding the slots of a [kind], filled shelf by shelf. */
    private class AtlasLevel(private val kind: SlotKind) {
        private val pages = ArrayList<AtlasPage>()
        private var pageCount = 0

        // The page being filled, and its slots.
        private var page: AtlasPage? = null
        private val shelves = Shelves()

        /** Gives [jobs] slots while [pixels] last. Returns the pixels of the jobs that got one. */
        fun layOut(jobs: List<FramePlan.Job>, pixels: Long): Long {
            pageCount = 0
            page = null
            var used = 0L
            for (job in jobs) {
                if (!holds(job)) continue
                val jobPixels = widthOf(job).toLong() * heightOf(job)
                if (used + jobPixels > pixels) continue
                used += jobPixels
                place(job)
            }
            finishPage()
            // Pages that stay unused for a while are dropped.
            for (i in pageCount until pages.size) pages[i].idle()
            while (pages.size > pageCount && pages.last().isIdleForLong) {
                pages.removeAt(pages.lastIndex).release()
            }
            return used
        }

        private fun holds(job: FramePlan.Job): Boolean =
            when (kind) {
                SlotKind.Layers -> !job.isInPerspective
                SlotKind.Perspective -> job.isInPerspective
                SlotKind.Blurs -> job.blur.isPrepared && job.page != null
                SlotKind.Foregrounds -> job.foreground.effect != null && job.page != null
                SlotKind.Results -> job.result.effect != null && job.page != null
            }

        private fun widthOf(job: FramePlan.Job): Int =
            if (kind == SlotKind.Blurs) job.blur.slotWidth else job.width

        private fun heightOf(job: FramePlan.Job): Int =
            if (kind == SlotKind.Blurs) job.blur.slotHeight else job.height

        /** The largest size of a page holding several slots. */
        private fun pageSize(): Int =
            if (kind == SlotKind.Perspective) min(PageSize, WebGLRenderer.maxTextureSize) else PageSize

        private fun place(job: FramePlan.Job, slotPage: AtlasPage, slotX: Int, slotY: Int) {
            when (kind) {
                SlotKind.Layers, SlotKind.Perspective -> job.place(slotPage, slotX, slotY)
                SlotKind.Blurs -> job.blur.place(slotPage, slotX, slotY)
                SlotKind.Foregrounds -> job.foreground.place(slotPage, slotX, slotY)
                SlotKind.Results -> job.result.place(slotPage, slotX, slotY)
            }
        }

        private fun place(job: FramePlan.Job) {
            val width = widthOf(job)
            val height = heightOf(job)
            val pageSize = pageSize()
            if (width > pageSize || height > pageSize) {
                // Larger than a page: a page of its own.
                val own = nextPage()
                own.prepare(width, height)
                place(job, own, 0, 0)
                return
            }
            var current = page
            if (current == null || !shelves.place(width, height)) {
                current = startPage(pageSize)
                shelves.place(width, height)
            }
            place(job, current, shelves.x, shelves.y)
        }

        private fun startPage(pageSize: Int): AtlasPage {
            finishPage()
            shelves.reset(pageSize)
            return nextPage().also { page = it }
        }

        private fun finishPage() {
            page?.prepare(shelves.width, shelves.height)
            page = null
        }

        private fun nextPage(): AtlasPage {
            if (pageCount == pages.size) pages.add(AtlasPage())
            return pages[pageCount++]
        }
    }

    companion object {
        /** The largest size of a page holding several layers. */
        internal const val PageSize = 4096

        /** The pixels between two slots. */
        internal const val Gutter = 1

        /** The pixels the layers of a frame can take in the pages, by default. */
        internal const val MaxPixels = 4096L * 4096L
    }
}

/** A canvas holding the slots of layers, with the [DrawTarget] they're drawn through. */
internal class AtlasPage {
    /** A `<canvas>` element, which resolves SVG filters when layers are composited into it. */
    val canvas: CanvasElement = createElementCanvas(0, 0)
    val target = DrawTarget(requireContext2D(canvas), originX = 0, originY = 0, width = 0, height = 0)

    private var framesTooLarge = 0
    private var framesUnused = 0

    /** The top-left part of the page that its slots take. */
    var usedWidth = 0
        private set
    var usedHeight = 0
        private set

    val isIdleForLong: Boolean get() = framesUnused >= PatienceFrames

    /**
     * The texture WebGL draws the slots of the page from, in perspective or in place (see
     * [PerspectiveProjector], [SlotsThroughWebGL]), once uploaded: of the size of the canvas, and
     * deleted with its storage, so that animated layers don't allocate one in every frame. Its
     * content is the one of the batch of draws that uploaded it last, premultiplied
     * ([uploadTexture]) or not ([uploadPixels]): each batch uploads the pages it draws from. It's of
     * the generation [textureGeneration] of the context, whose loss drops it.
     */
    var texture: JsAny? = null
        private set
    private var textureGeneration = 0

    /**
     * Uploads the used part of the page into its [texture], premultiplied, with the cleared pixels
     * right after it: the slots at its right and bottom edges are sampled up to half a pixel past
     * them, and the texture holds older frames further away.
     */
    fun uploadTexture() {
        upload { current ->
            WebGLRenderer.uploadPage(
                current,
                canvas,
                textureWidth = target.width,
                textureHeight = target.height,
                width = min(usedWidth + LayerAtlas.Gutter, target.width),
                height = min(usedHeight + LayerAtlas.Gutter, target.height),
            )
        }
    }

    /**
     * Uploads the used part of the page into its [texture] through its pixels: the non-premultiplied
     * bytes that its context reads, as they are, for the shaders that read the texture texel by
     * texel within the slots and premultiply them (see [WebGLRenderer.uploadPagePixels]).
     *
     * The pixels read are a copy of the used part of the page, 4 bytes per pixel at each upload,
     * made where canvases get no SVG filters (Safari), for the slots WebGL redraws in place
     * ([SlotsThroughWebGL]). It pays for itself there, measured in Safari: a page of 1.6 Mpx took
     * 5.1 to 5.6 ms to upload itself, 6.2 ms unpremultiplied (and 1 off at times), and 2.6 to 2.7 ms
     * to be read and its bytes uploaded as they are (4.4 to 4.6 ms if the upload premultiplies them,
     * hence the shaders do); on the main thread, a frame of 24 blurred layers took 7.8 ms instead of
     * 11.1, one of filtered layers 4.8 ms instead of 9.5.
     */
    fun uploadPixels() {
        upload { current ->
            WebGLRenderer.uploadPagePixels(
                current,
                target.ctx.getImageData(0, 0, usedWidth, usedHeight),
                textureWidth = target.width,
                textureHeight = target.height,
            )
        }
    }

    /** Uploads into the [texture] that [into] is given, if it's of this context, and keeps the one it returns. */
    private inline fun upload(into: (current: JsAny?) -> JsAny) {
        val generation = SharedWebGL.generation
        texture = into(texture.takeIf { textureGeneration == generation })
        textureGeneration = generation
    }

    /**
     * Makes the page at least [neededWidth] x [neededHeight] pixels, and clears it. It shrinks only
     * after being [ShrinkRatio] times too large for a while, so that animated layers don't resize
     * it in every frame.
     */
    fun prepare(neededWidth: Int, neededHeight: Int) {
        framesUnused = 0
        usedWidth = neededWidth
        usedHeight = neededHeight
        val width = target.width
        val height = target.height
        val isTooSmall = neededWidth > width || neededHeight > height
        val isTooLarge = ShrinkRatio * neededWidth * neededHeight <= width.toLong() * height
        framesTooLarge = if (isTooLarge) framesTooLarge + 1 else 0
        when {
            isTooSmall ->
                resize(max(width, roundUp(neededWidth)), max(height, roundUp(neededHeight)))
            framesTooLarge >= PatienceFrames -> resize(roundUp(neededWidth), roundUp(neededHeight))
            else -> {
                target.setPixelTransform()
                target.ctx.clearRect(0.0, 0.0, width.toDouble(), height.toDouble())
            }
        }
    }

    fun idle() {
        framesUnused++
    }

    fun release() {
        releaseCanvas(canvas)
        dropTexture()
    }

    /** Resizes the canvas, which clears it and resets its context. */
    private fun resize(width: Int, height: Int) {
        framesTooLarge = 0
        canvas.width = width
        canvas.height = height
        target.width = width
        target.height = height
        target.invalidateState()
        dropTexture()
    }

    private fun dropTexture() {
        val current = texture ?: return
        // A texture of a lost context is gone already.
        if (textureGeneration == SharedWebGL.generation) WebGLRenderer.deleteTexture(current)
        texture = null
    }

    private companion object {
        /** Pages grow by steps of this size, so that growing layers don't resize them often. */
        const val SizeStep = 256

        /** How many times larger than needed a page is before it shrinks. */
        const val ShrinkRatio = 4L

        /** How many frames a page stays too large, or unused, before it shrinks or is dropped. */
        const val PatienceFrames = 120

        fun roundUp(size: Int): Int = (size + SizeStep - 1) / SizeStep * SizeStep
    }
}
