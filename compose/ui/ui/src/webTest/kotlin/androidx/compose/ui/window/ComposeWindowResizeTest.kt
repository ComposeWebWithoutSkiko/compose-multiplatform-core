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

package androidx.compose.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.OnCanvasTests
import androidx.compose.ui.graphics.Color
import kotlin.coroutines.resume
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.HTMLElement

/** A window draws its resized canvas before the browser paints it. */
class ComposeWindowResizeTest : OnCanvasTests {

    @Test
    fun aResizedWindowIsDrawnBeforeTheBrowserPaints() = runApplicationTest {
        withContainer { container ->
            createComposeWindow {
                Box(Modifier.fillMaxSize().background(Color.Red))
            }
            awaitIdle()
            assertEquals(255, alphaAfterResize(container), AlphaMessage)
        }
    }

    @Test
    fun anAnimationKeepsItsFrameTimesInOrderThroughAResize() = runApplicationTest {
        withContainer { container ->
            // An animation frame is pending whenever the window is resized.
            val frameTimes = mutableListOf<Long>()
            createComposeWindow {
                Box(Modifier.fillMaxSize().background(Color.Red))
                LaunchedEffect(Unit) {
                    while (true) withFrameNanos { frameTimes += it }
                }
            }
            awaitAnimationFrame()
            assertEquals(255, alphaAfterResize(container), AlphaMessage)
            repeat(3) { awaitAnimationFrame() }
            for (i in 1 until frameTimes.size) {
                assertTrue(frameTimes[i] >= frameTimes[i - 1], "The frame times went back: $frameTimes")
            }
        }
    }

    /** Runs [block] with the container of the windows at a size of its own, then restores it. */
    private suspend fun withContainer(block: suspend (HTMLElement) -> Unit) {
        val container = getContainer() as HTMLElement
        val originalStyle = container.style.cssText
        try {
            // Real geometry, independent of the size of Karma's default test container.
            container.style.cssText = "position: fixed; left: 0; top: 0; width: 200px; height: 150px;"
            block(container)
        } finally {
            getComposeWindowOrNull()?.dispose()
            container.style.cssText = originalStyle
        }
    }

    /**
     * Narrows [container] by a pixel, and returns the alpha of the middle of the canvas as the browser
     * paints it next.
     */
    private suspend fun alphaAfterResize(container: HTMLElement): Int {
        var observer: JsAny? = null
        try {
            return suspendCancellableCoroutine { continuation ->
                var notifications = 0
                observer = observeAfterTheWindow(container, getCanvas()) { alpha ->
                    notifications++
                    when (notifications) {
                        // An observation starts with a notification of the size as it is: resize in
                        // the next animation frame, before the ResizeObservers of that rendering.
                        1 -> window.requestAnimationFrame {
                            container.style.width = "${container.clientWidth - 1}px"
                        }
                        2 -> continuation.resume(alpha)
                    }
                }
            }
        } finally {
            observer?.let(::disconnect)
        }
    }

    private companion object {
        const val AlphaMessage = "The alpha of the canvas when the browser paints it after a resize"
    }
}

/**
 * Calls [onResize] with the alpha of the middle pixel of [canvas] whenever [container] is resized,
 * from a ResizeObserver made after the window's, which runs after it in the same rendering of the
 * page, before the browser paints: the canvas as the browser paints it. Returns the observer.
 */
// language=js
private fun observeAfterTheWindow(container: JsAny, canvas: JsAny, onResize: (Int) -> Unit): JsAny = js(
    """{
        const ctx = canvas.getContext('2d');
        const observer = new ResizeObserver(
            () => onResize(ctx.getImageData(canvas.width >> 1, canvas.height >> 1, 1, 1).data[3])
        );
        observer.observe(container);
        return observer;
    }"""
)

// language=js
private fun disconnect(observer: JsAny): Unit = js("observer.disconnect()")
