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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.webcanvas.dom.CssColors
import androidx.compose.ui.webcanvas.graphics.AccelerationWatch.Companion.MaxWatchFrames
import androidx.compose.ui.webcanvas.graphics.AccelerationWatch.Companion.WatchFrames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * A page is found drawn in software by the time of its clears (see [AccelerationWatch]), and is then
 * drawn into a new canvas.
 */
class AccelerationWatchTest {
    /** The clears, of [milliseconds] each, after which [watch] says software, or `null` if it doesn't within [limit]. */
    private fun clearsUntilSoftware(watch: AccelerationWatch, milliseconds: Double, limit: Int = 4 * MaxWatchFrames): Int? {
        for (clear in 1..limit) {
            if (watch.cleared(milliseconds)) return clear
        }
        return null
    }

    @Test
    fun clearsThatTakeNoTimeNeverSaySoftware() {
        assertEquals(null, clearsUntilSoftware(AccelerationWatch(), 0.0))
    }

    @Test
    fun clearsThatTakeTimeSaySoftwareAtTheEndOfTheWindow() {
        assertEquals(WatchFrames, clearsUntilSoftware(AccelerationWatch(), 1.0))
    }

    @Test
    fun aWindowSaysSoftwarePastThreeStepsOfTheTimer() {
        // With a timer of 1 ms steps, a window of 60 clears of 0.05 ms each on average has 3.
        for ((steps, inSoftware) in listOf(3 to false, 4 to true)) {
            val watch = AccelerationWatch()
            val verdicts = List(WatchFrames) { clear -> watch.cleared(if (clear < steps) 1.0 else 0.0) }
            assertEquals(List(WatchFrames - 1) { false } + inSoftware, verdicts, "$steps steps")
        }
    }

    @Test
    fun windowsAfterSoftwareGrowUpToTheLongest() {
        val watch = AccelerationWatch()
        val windows = List(7) { clearsUntilSoftware(watch, 1.0) }
        assertEquals(listOf(60, 120, 240, 480, 960, 960, 960), windows)
    }

    @Test
    fun aPageFoundInSoftwareDrawsIntoANewCanvasOfItsSize() {
        val watch = AccelerationWatch()
        // A window of clears in software but for its last, which a clear of the page completes.
        repeat(WatchFrames - 1) { watch.cleared(1.0) }
        val page = AtlasPage(watch)
        // Sized, which clears it without a clearRect; then cleared, which the watch takes as software.
        page.prepare(300, 200)
        val first = page.canvas
        page.prepare(300, 200)
        val renewed = page.canvas
        assertNotSame(first, renewed)
        assertEquals(0, first.width, "the first canvas freed")
        assertEquals(512 to 256, renewed.width to renewed.height)
        assertEquals(512 to 256, page.target.width to page.target.height)
        val ctx = page.target.ctx
        ctx.fillStyle = CssColors.of(Color.Red.toArgb())
        ctx.fillRect(10.0, 10.0, 1.0, 1.0)
        assertEquals(Color.Red.toArgb(), argbAt(ctx.getImageData(10, 10, 1, 1), 0, 0))
        page.release()
    }

    @Test
    fun aPageThatWebGLReadsKeepsItsCanvas() {
        val watch = AccelerationWatch()
        repeat(WatchFrames - 1) { watch.cleared(1.0) }
        val page = AtlasPage(watch)
        page.prepare(300, 200)
        val first = page.canvas
        page.uploadTexture()
        // The clear that ends a window in software.
        page.prepare(300, 200)
        assertSame(first, page.canvas)
        page.release()
    }

    @Test
    fun aWindowThatDoesNotSaySoftwareBringsTheShortestBack() {
        val watch = AccelerationWatch()
        assertEquals(WatchFrames, clearsUntilSoftware(watch, 1.0))
        // The next window, twice as long, with acceleration.
        repeat(2 * WatchFrames) { assertFalse(watch.cleared(0.0)) }
        assertEquals(WatchFrames, clearsUntilSoftware(watch, 1.0))
    }
}
