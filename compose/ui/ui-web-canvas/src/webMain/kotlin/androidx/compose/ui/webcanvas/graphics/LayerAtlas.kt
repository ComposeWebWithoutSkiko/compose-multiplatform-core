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
import androidx.compose.ui.webcanvas.dom.CanvasFeatures
import androidx.compose.ui.webcanvas.dom.createElementCanvas
import androidx.compose.ui.webcanvas.dom.performanceNow
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

/**
 * A canvas holding the slots of layers, with the [DrawTarget] they're drawn through.
 *
 * Where the browser takes a canvas off acceleration for good once too many of its frames failed
 * ([CanvasFeatures.profilesAcceleration], Firefox), the page makes its canvas again when it finds it
 * drawn in software ([AccelerationWatch]): a page whose content is new frame after frame, as an app
 * starts, fails its frames, while a new canvas, whose first frames find that content in the caches
 * that Firefox's accelerated canvases share (glyphs, paths, surfaces: `SharedContextWebgl` in
 * `dom/canvas/DrawTargetWebgl.h`), keeps acceleration. Measured in Firefox 159 on macOS, scrolling the
 * emote catalog of Emotecraft Library (emotes.redlance.org): its page was drawn in software from its
 * 37th, 65th or 91st frame on, depending on when the content came; replaying the app's recorded
 * Canvas 2D calls at 120 Hz, a page made again after the start drew 120 frames per second, against
 * 55 without. Making it again at a set frame doesn't do: content comes by time and Firefox counts
 * frames, so at 240 Hz a page made again after 120 frames lost acceleration again at its 56th. Made
 * again when found in software, the page was, once, at its 122nd frame at 120 Hz and at its 182nd
 * at 240 Hz, and the catalog drew 60, 120 and 160 frames per second at 60, 120 and 240 Hz (at 240,
 * Firefox's GPU process works all the time), against 52, 56 and 56. The window canvas and the
 * pooled canvases of layers aren't made again: in that catalog, their draws took at most 0.16 ms
 * per frame in the tab, as accelerated canvases' do.
 *
 * [accelerationWatch] tells that the canvas was taken off acceleration, where the browser does that
 * for good, and is `null` elsewhere, and once WebGL reads the page ([texture]): such a page stays
 * drawn the way Firefox chooses. Measured in Firefox 159 at 120 Hz with the `perspective` scenario of
 * compose-web-benchmark (24 layers in perspective, whose page WebGL uploads in every frame), its page
 * made again and accelerated drew 73 to 91 frames per second, against 98 to 101 with the page left
 * in software. Why an accelerated page uploads more slowly isn't established.
 */
internal class AtlasPage(
    private var accelerationWatch: AccelerationWatch? =
        if (CanvasFeatures.profilesAcceleration) AccelerationWatch() else null,
) {
    /** A `<canvas>` element, which resolves SVG filters when layers are composited into it. */
    var canvas: CanvasElement = createElementCanvas(0, 0)
        private set
    var target = targetOf(canvas)
        private set

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

    /**
     * Uploads into the [texture] that [into] is given, if it's of this context, and keeps the one it
     * returns. The page is no longer watched (see [accelerationWatch]).
     */
    private inline fun upload(into: (current: JsAny?) -> JsAny) {
        val generation = SharedWebGL.generation
        texture = into(texture.takeIf { textureGeneration == generation })
        textureGeneration = generation
        accelerationWatch = null
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
                if (clearShowsSoftware(width, height)) {
                    renewCanvas()
                    resize(width, height)
                }
            }
        }
    }

    /**
     * Clears the [width] x [height] page, and returns whether the time of its clears shows it drawn
     * in software (see [AccelerationWatch]).
     */
    private fun clearShowsSoftware(width: Int, height: Int): Boolean {
        val watch = accelerationWatch
        val start = if (watch != null) performanceNow() else 0.0
        target.ctx.clearRect(0.0, 0.0, width.toDouble(), height.toDouble())
        return watch != null && watch.cleared(performanceNow() - start)
    }

    fun idle() {
        framesUnused++
    }

    fun release() {
        releaseCanvas(canvas)
        dropTexture()
    }

    /**
     * Makes the canvas again, empty and of no size: while the page is prepared, so before anything of
     * the frame is drawn into it. The jobs of the frame hold the page, not its canvas.
     */
    private fun renewCanvas() {
        release()
        canvas = createElementCanvas(0, 0)
        target = targetOf(canvas)
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

        /** The target of an empty page drawn into through [canvas]. */
        fun targetOf(canvas: CanvasElement): DrawTarget =
            DrawTarget(requireContext2D(canvas), originX = 0, originY = 0, width = 0, height = 0)
    }
}

/**
 * Tells, from the time the clears of a page take, when the browser draws it in software, which no
 * API tells: a clear of a canvas drawn with acceleration doesn't take longer as the canvas grows
 * (0.001 to 0.027 ms, measured below), while one of a canvas drawn in software takes time in
 * proportion to its size.
 *
 * Measured in Firefox 159 on macOS (an M4 Pro), the main-thread time of a `clearRect` of a whole
 * canvas, each canvas getting a few draws in every frame. Over 100 frames, twelve canvases: about
 * 0.001 ms with acceleration, whatever the size; in software 0.08 ms at 1024 x 768, 0.13 at
 * 1792 x 768, 0.40 at 2048 x 2048, 0.56 at 3328 x 1792 and 0.96 at 4096 x 2560. Over 2400 frames at
 * 120 Hz, six canvases, four times: in software 0.10 to 0.12 ms at 0.31 Mpx, 0.16 to 0.17 at 0.52
 * and 0.30 to 0.32 at 1.05, about three times as long per megapixel; with acceleration 0.002 to
 * 0.011 ms at 0.52 and 1.05 Mpx, but 0.023 to 0.027 at 0.31 Mpx, all along the run.
 *
 * Firefox gives the time in steps of 1 ms by default (`privacy.reduceTimerPrecision`), so the time
 * of a clear is read over a window of [WatchFrames] clears, whose mean came close to it (0.81 ms at
 * 4096 x 2560, 0.09 at 1024 x 768). A window says software if its clears took more than
 * [SoftwareClearMs] each on average: four steps of 1 ms or more in a window of [WatchFrames] clears,
 * proportionally more in the longer ones. Over the 40 windows of the second measurement, those of
 * pages in software said so in 32 to 35 at 0.31 Mpx, 37 to 39 at 0.52 and all 40 at 1.05; those of
 * accelerated pages in none or one at 0.52 and 1.05 Mpx, and in 2 to 6 at 0.31 Mpx, whose clears
 * catch 1.5 steps a window on average: such a page is made again now and then while it's
 * accelerated, which took 0.10 to 0.40 ms for a page of 4096 x 2560 in Firefox 159, against 0.03 ms
 * for a frame of it.
 *
 * After a window that said software the next one is twice as long, up to [MaxWatchFrames], and
 * after one that didn't, [WatchFrames] long again: content new in every frame takes every new canvas
 * off acceleration again, and makes one at most every [MaxWatchFrames] frames, while a page found
 * drawn in software later, once stable, is found within a window or two.
 */
internal class AccelerationWatch {
    private var window = WatchFrames
    private var clears = 0
    private var time = 0.0

    /**
     * Takes the time a clear of the page took, in milliseconds, and returns whether the page is
     * drawn in software: at the end of a window whose clears took more than [SoftwareClearMs] each
     * on average.
     */
    fun cleared(milliseconds: Double): Boolean {
        time += milliseconds
        if (++clears < window) return false
        val inSoftware = time > SoftwareClearMs * window
        window = if (inSoftware) min(2 * window, MaxWatchFrames) else WatchFrames
        clears = 0
        time = 0.0
        return inSoftware
    }

    internal companion object {
        /** The clears of a window, after one that didn't say software. */
        const val WatchFrames = 60

        /**
         * The clears of the longest window, which bounds how often a page is made again for nothing:
         * where Firefox never accelerates canvases (no GPU, or `gfx.canvas.accelerated` off), every
         * page found in software is made again once in this many frames. Measured in Firefox 159 with
         * `gfx.canvas.accelerated` off, making a page of 4096 x 2560 again and drawing its first frame
         * took 2.0 to 7.8 ms against 1.9 ms for a frame of it: one frame up to 6 ms longer every
         * 960, every 8 s at 120 Hz, under 0.01 ms per frame on average.
         */
        const val MaxWatchFrames = 960

        /** The mean time of a clear, in milliseconds, past which a window says software. */
        const val SoftwareClearMs = 0.05
    }
}
