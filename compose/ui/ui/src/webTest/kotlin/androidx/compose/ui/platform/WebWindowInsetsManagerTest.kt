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

import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.events.Event

class WebWindowInsetsManagerTest {
    @Test
    fun safeAreaIsMappedToInsetTypes() = withManager { environment, _, manager ->
        environment.setSafeArea(left = 40f, top = 10f, right = 20f, bottom = 30f)
        manager.onCanvasResized()

        val insets = manager.windowInsets
        assertInsets(insets.statusBars, top = 10)
        assertInsets(insets.navigationBars, bottom = 30)
        assertInsets(insets.systemBars, left = 40, top = 10, right = 20, bottom = 30)
        assertInsets(insets.displayCutout, left = 40, top = 10, right = 20, bottom = 30)
        assertInsets(insets.systemGestures, left = 40, top = 10, right = 20, bottom = 30)
        assertInsets(insets.mandatorySystemGestures, top = 10, bottom = 30)
        assertInsets(insets.tappableElement, top = 10)
    }

    @Test
    fun safeAreaIsClippedOnAllSides() = withManager { environment, canvas, manager ->
        environment.setSafeArea(left = 10f, top = 20f, right = 30f, bottom = 40f)
        environment.move(
            canvas,
            left = 6f,
            top = 15f,
            right = window.innerWidth - 12f,
            bottom = window.innerHeight - 25f
        )
        manager.onCanvasResized()

        assertInsets(manager.windowInsets.systemBars, left = 4, top = 5, right = 18, bottom = 15)
    }

    @Test
    fun canvasOutsideSafeAreaHasZeroInsets() = withManager { environment, canvas, manager ->
        environment.setSafeArea(left = 10f, top = 20f, right = 30f, bottom = 40f)
        environment.move(
            canvas,
            left = 11f,
            top = 21f,
            right = window.innerWidth - 31f,
            bottom = window.innerHeight - 41f
        )
        manager.onCanvasResized()

        assertInsets(manager.windowInsets.systemBars)
    }

    @Test
    fun cssPixelsAreScaledAndRounded() = withManager(
        densityProvider = { Density(1.5f) }
    ) { environment, _, manager ->
        environment.setSafeArea(left = 1f, top = 2.5f, right = 3f, bottom = 4f)
        manager.onCanvasResized()

        assertInsets(manager.windowInsets.systemBars, left = 2, top = 4, right = 5, bottom = 6)
    }

    @Test
    fun resizeReadsUpdatedSafeArea() = withManager { environment, _, manager ->
        val insets = manager.windowInsets
        environment.setSafeArea(top = 10f)
        manager.onCanvasResized()
        assertInsets(insets.statusBars, top = 10)

        environment.setSafeArea(top = 50f)
        manager.onCanvasResized()
        assertInsets(insets.statusBars, top = 50)
    }

    @Test
    fun resizeReadsUpdatedCanvasGeometry() = withManager { environment, canvas, manager ->
        environment.setSafeArea(top = 20f)
        manager.onCanvasResized()
        assertInsets(manager.windowInsets.statusBars, top = 20)

        environment.move(canvas, top = 15f)
        manager.onCanvasResized()
        assertInsets(manager.windowInsets.statusBars, top = 5)
    }

    @Test
    fun resizeReadsUpdatedDensity() {
        var density = Density(1f)
        withManager(densityProvider = { density }) { environment, _, manager ->
            environment.setSafeArea(top = 10f)
            manager.onCanvasResized()
            assertInsets(manager.windowInsets.statusBars, top = 10)

            density = Density(2f)
            manager.onCanvasResized()
            assertInsets(manager.windowInsets.statusBars, top = 20)
        }
    }

    @Test
    fun insetsTakenBeforeAChangeReadTheChangedValue() = withManager { environment, _, manager ->
        val systemBars = manager.windowInsets.systemBars
        val displayCutout = manager.windowInsets.displayCutout
        val systemGestures = manager.windowInsets.systemGestures

        environment.setSafeArea(left = 40f, top = 10f, right = 20f, bottom = 30f)
        manager.onCanvasResized()

        assertInsets(systemBars, left = 40, top = 10, right = 20, bottom = 30)
        assertInsets(displayCutout, left = 40, top = 10, right = 20, bottom = 30)
        assertInsets(systemGestures, left = 40, top = 10, right = 20, bottom = 30)
    }

    @Test
    fun documentScrollReadsUpdatedCanvasGeometry() = withManager { environment, canvas, manager ->
        environment.setSafeArea(top = 20f)
        manager.onCanvasResized()
        assertInsets(manager.windowInsets.statusBars, top = 20)

        environment.move(canvas, top = -30f)
        window.dispatchEvent(Event("scroll"))
        assertInsets(manager.windowInsets.statusBars, top = 50)
    }

    @Test
    fun disposedManagerIgnoresDocumentScroll() = withManager { environment, canvas, manager ->
        environment.setSafeArea(top = 20f)
        manager.onCanvasResized()
        manager.dispose()

        environment.move(canvas, top = -30f)
        window.dispatchEvent(Event("scroll"))
        assertInsets(manager.windowInsets.statusBars, top = 20)
    }

    @Test
    fun visualViewportImeHeightAtScaleOne() {
        assertEquals(0f, visualViewportImeHeight(innerHeight = 800f, height = 800f, scale = 1f, offsetTop = 0f))
        assertEquals(300f, visualViewportImeHeight(innerHeight = 800f, height = 500f, scale = 1f, offsetTop = 0f))
        assertEquals(200f, visualViewportImeHeight(innerHeight = 800f, height = 500f, scale = 1f, offsetTop = 100f))
    }

    @Test
    fun visualViewportImeHeightIgnoresPinchZoom() {
        assertEquals(0f, visualViewportImeHeight(innerHeight = 800f, height = 400f, scale = 2f, offsetTop = 0f))
        assertEquals(0f, visualViewportImeHeight(innerHeight = 800f, height = 400f, scale = 2f, offsetTop = 200f))
    }

    @Test
    fun visualViewportImeHeightUnderPinchZoomIsCappedByKeyboard() {
        assertEquals(150f, visualViewportImeHeight(innerHeight = 800f, height = 250f, scale = 2f, offsetTop = 400f))
        assertEquals(0f, visualViewportImeHeight(innerHeight = 800f, height = 250f, scale = 2f, offsetTop = 550f))
        assertEquals(150f, visualViewportImeHeight(innerHeight = 800f, height = 250f, scale = 2f, offsetTop = 0f))
    }

    @Test
    fun visualViewportImeHeightIsZeroWhenInnerHeightFollowsTheVisualViewport() {
        assertEquals(0f, visualViewportImeHeight(innerHeight = 377f, height = 377f, scale = 1f, offsetTop = 337f))
        assertEquals(0f, visualViewportImeHeight(innerHeight = 578f, height = 578.2f, scale = 1.23f, offsetTop = 135.8f))
    }

    @Test
    fun layoutViewportSizeIgnoresTheViewDippingUnderTheLayout() {
        assertEquals(714f, layoutViewportSize(inner = 578, client = 714))
    }

    @Test
    fun layoutViewportSizeKeepsTheLargerWindow() {
        assertEquals(800f, layoutViewportSize(inner = 800, client = 714))
        assertEquals(1280f, layoutViewportSize(inner = 1280, client = 1265))
    }

    @Test
    fun imeBottomWithoutKeyboardIsTheCanvasBelowTheViewport() {
        assertEquals(160f, imeBottom(keyboard = 0f, innerHeight = 714, clientHeight = 714, canvasBottom = 874f))
    }

    @Test
    fun imeBottomAddsTheKeyboard() {
        assertEquals(497f, imeBottom(keyboard = 337f, innerHeight = 714, clientHeight = 714, canvasBottom = 874f))
    }

    @Test
    fun imeBottomIgnoresThePullToRefreshDip() {
        assertEquals(160f, imeBottom(keyboard = 0f, innerHeight = 578, clientHeight = 714, canvasBottom = 874f))
    }

    private fun withManager(
        densityProvider: () -> Density = { Density(1f) },
        block: (WebInsetsTestEnvironment, Element, WebWindowInsetsManager) -> Unit
    ) {
        val environment = WebInsetsTestEnvironment()
        val canvas = environment.canvas()
        var manager: WebWindowInsetsManager? = null
        try {
            val createdManager = WebWindowInsetsManager(densityProvider, canvas)
            manager = createdManager
            block(environment, canvas, createdManager)
        } finally {
            try {
                manager?.dispose()
            } finally {
                environment.restore()
            }
        }
    }

    private fun assertInsets(
        actual: PlatformInsets,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0
    ) {
        assertEquals(left, actual.left, "Left inset")
        assertEquals(top, actual.top, "Top inset")
        assertEquals(right, actual.right, "Right inset")
        assertEquals(bottom, actual.bottom, "Bottom inset")
    }
}
