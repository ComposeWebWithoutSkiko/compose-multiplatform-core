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

@file:OptIn(ExperimentalWasmJsInterop::class)

package androidx.compose.ui.platform

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.events.EventTargetListener
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.getVisualViewportScale
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js
import kotlinx.browser.window
import org.w3c.dom.DOMRect
import org.w3c.dom.Element
import org.w3c.dom.events.EventTarget

private class WebWindowInsets(
    private val safeArea: () -> PlatformInsets,
    private val keyboard: () -> PlatformInsets,
) : PlatformWindowInsets {
    private val liveSafeArea = liveInsets(safeArea)
    private val liveKeyboard = liveInsets(keyboard)

    override val statusBars: PlatformInsets
        get() = PlatformInsets(getTop = { safeArea().top })
    override val navigationBars: PlatformInsets
        get() = PlatformInsets(getBottom = { safeArea().bottom })
    override val systemBars: PlatformInsets
        get() = liveSafeArea
    override val displayCutout: PlatformInsets
        get() = liveSafeArea
    override val ime: PlatformInsets
        get() = liveKeyboard
    override val systemGestures: PlatformInsets
        get() = liveSafeArea
    override val mandatorySystemGestures: PlatformInsets
        get() = PlatformInsets(getTop = { safeArea().top }, getBottom = { safeArea().bottom })
    override val tappableElement: PlatformInsets
        get() = PlatformInsets(getTop = { safeArea().top })
}

/**
 * Insets that read [current] whenever a side is asked for, as the other insets here do: a reader
 * in layout then follows a change without recomposing, rather than keeping the value of the call.
 */
private fun liveInsets(current: () -> PlatformInsets): PlatformInsets = PlatformInsets(
    getLeft = { current().left },
    getTop = { current().top },
    getRight = { current().right },
    getBottom = { current().bottom },
)

/**
 * Reads system window insets (safe area and IME) from the browser and exposes them as Compose
 * state.
 *
 * Safe area insets are read from CSS `env(safe-area-inset-*)` environment variables via CSS custom
 * properties, and re-read whenever the canvas is resized or the document scrolls: a scrolled page
 * moves the canvas without resizing it, and the insets follow where it lies.
 *
 * IME (virtual keyboard) insets are tracked using:
 * - **VirtualKeyboard API** when available — the most precise source.
 * - **VisualViewport API** as a fallback for Safari and Firefox — derived from `window.innerHeight`
 *   and the visual viewport's height, scale and offset (see [visualViewportImeHeight]).
 *
 * All insets are clipped to the portion of the system UI zones that the [composeScene] actually
 * overlaps. For example, if the canvas is positioned below the status bar, the top inset will be
 * zero; if it extends into the navigation bar area, the bottom inset will reflect the overlap.
 *
 */
internal class WebWindowInsetsManager(
    private val densityProvider: () -> Density,
    private val canvas: Element
) {
    private var canvasRect: DOMRect = canvas.getBoundingClientRect()
        set(value) {
            field = value
            readAndUpdateSafeArea()
            readAndUpdateIme()
        }

    private val safeAreaInsets = mutableStateOf(PlatformInsets.Zero)
    private val imeInsets = mutableStateOf(PlatformInsets.Zero)

    val windowInsets: PlatformWindowInsets = WebWindowInsets(
        safeArea = { safeAreaInsets.value },
        keyboard = { imeInsets.value },
    )

    private val hasVirtualKeyboardApi: Boolean = hasVirtualKeyboard()

    private val imeEventsListener: EventTargetListener?

    private val scrollListener = EventTargetListener(window).apply {
        addDisposableEvent("scroll", passive = true) { readCanvasRect() }
    }

    init {
        installSafeAreaCssProperties()
        imeEventsListener = initImeTracking()
    }

    fun dispose() {
        imeEventsListener?.dispose()
        scrollListener.dispose()
    }

    fun onCanvasResized() {
        readCanvasRect()
    }

    private fun readCanvasRect() {
        canvasRect = canvas.getBoundingClientRect()
    }

    private fun initImeTracking(): EventTargetListener? {
        return if (hasVirtualKeyboardApi) {
            enableVirtualKeyboardOverlay()
            val vk = getVirtualKeyboard() ?: return null
            EventTargetListener(vk).apply {
                addDisposableEvent("geometrychange") { readAndUpdateIme() }
            }
        } else {
            val vv = getVisualViewport() ?: return null
            EventTargetListener(vv).apply {
                addDisposableEvent("resize") { readAndUpdateIme() }
            }
        }
    }

    private fun readAndUpdateSafeArea() {
        val vw = layoutViewportSize(window.innerWidth, readRootClientWidth())
        val vh = layoutViewportSize(window.innerHeight, readRootClientHeight())
        val adjustedLeft = maxOf(0f, readCssVarLeft() - canvasRect.left.toFloat())
        val adjustedTop = maxOf(0f, readCssVarTop() - canvasRect.top.toFloat())
        val adjustedRight = maxOf(0f, readCssVarRight() - (vw - canvasRect.right.toFloat()))
        val adjustedBottom = maxOf(0f, readCssVarBottom() - (vh - canvasRect.bottom.toFloat()))

        safeAreaInsets.value = with(densityProvider()) {
            PlatformInsets(
                left = adjustedLeft.dp.roundToPx(),
                top = adjustedTop.dp.roundToPx(),
                right = adjustedRight.dp.roundToPx(),
                bottom = adjustedBottom.dp.roundToPx()
            )
        }
    }

    private fun readAndUpdateIme() {
        val rawHeight = if (hasVirtualKeyboardApi) {
            readVirtualKeyboardHeight()
        } else {
            readVisualViewportImeHeight()
        }
        val adjustedBottom = imeBottom(
            keyboard = rawHeight,
            innerHeight = window.innerHeight,
            clientHeight = readRootClientHeight(),
            canvasBottom = canvasRect.bottom.toFloat()
        )

        imeInsets.value = with(densityProvider()) {
            PlatformInsets(bottom = adjustedBottom.dp.roundToPx())
        }
    }
}

/**
 * Installs CSS custom properties on `document.documentElement` that mirror `env(safe-area-inset-*)`.
 *
 * Setting them on the root element (rather than inside a canvas shadow root) works around a WebKit
 * bug where `env()` values return 0 in canvas-based shadow roots on some iOS versions.
 */
// language=js
private fun installSafeAreaCssProperties(): Unit = js(
    """(function() {
        let s = document.documentElement.style;
        s.setProperty('--cmp-safe-top', 'env(safe-area-inset-top, 0px)');
        s.setProperty('--cmp-safe-right', 'env(safe-area-inset-right, 0px)');
        s.setProperty('--cmp-safe-bottom', 'env(safe-area-inset-bottom, 0px)');
        s.setProperty('--cmp-safe-left', 'env(safe-area-inset-left, 0px)');
    })()"""
)

// language=js
private fun readCssVarTop(): Float =
    js("(parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--cmp-safe-top')) || 0)")

// language=js
private fun readCssVarRight(): Float =
    js("(parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--cmp-safe-right')) || 0)")

// language=js
private fun readCssVarBottom(): Float =
    js("(parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--cmp-safe-bottom')) || 0)")

// language=js
private fun readCssVarLeft(): Float =
    js("(parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--cmp-safe-left')) || 0)")

/**
 * The viewport's size along one axis as the page is laid out to it. `window.inner*` drops below the
 * root's `clientWidth`/`clientHeight` while iOS Safari's pull-to-refresh takes part of the view,
 * which moves no browser bar over the page; where the window is larger than the layout viewport,
 * `inner*` is the larger and stays the answer.
 */
internal fun layoutViewportSize(inner: Int, client: Int): Float = maxOf(inner, client).toFloat()

/**
 * The keyboard's inset from the canvas's bottom: [keyboard] above the bottom of the viewport the
 * page is laid out to (see [layoutViewportSize]), plus the part of the canvas below that viewport.
 */
internal fun imeBottom(keyboard: Float, innerHeight: Int, clientHeight: Int, canvasBottom: Float): Float =
    maxOf(0f, keyboard - (layoutViewportSize(innerHeight, clientHeight) - canvasBottom))

// language=js
private fun readRootClientWidth(): Int = js("document.documentElement.clientWidth")

// language=js
private fun readRootClientHeight(): Int = js("document.documentElement.clientHeight")

// language=js
private fun hasVirtualKeyboard(): Boolean = js("('virtualKeyboard' in navigator)")

/**
 * Enables VirtualKeyboard overlay mode so the browser does not resize the layout viewport when
 * the virtual keyboard appears, allowing us to read and apply IME insets ourselves.
 *
 * See https://developer.mozilla.org/en-US/docs/Web/API/VirtualKeyboard/overlaysContent
 */
// language=js
private fun enableVirtualKeyboardOverlay(): Unit =
    js("(navigator.virtualKeyboard.overlaysContent = true)")

// language=js
private fun getVirtualKeyboard(): EventTarget? = js("navigator.virtualKeyboard")

/** Returns the current keyboard height in CSS pixels (0 when keyboard is hidden). */
// language=js
private fun readVirtualKeyboardHeight(): Float =
    js("(navigator.virtualKeyboard.boundingRect.height || 0)")

// --- IME: VisualViewport API fallback (Safari, Firefox) ---

/**
 * Returns the browser's VisualViewport, which represents the visible portion of the viewport
 * after browser UI and the virtual keyboard have reduced it.
 *
 * See https://developer.mozilla.org/en-US/docs/Web/API/VisualViewport
 */
// language=js
private fun getVisualViewport(): EventTarget? = js("(window.visualViewport || null)")

/**
 * Estimates the IME height in CSS pixels from the visual viewport geometry.
 * Returns 0 when the keyboard is not visible.
 */
private fun readVisualViewportImeHeight(): Float = visualViewportImeHeight(
    innerHeight = window.innerHeight.toFloat(),
    height = readVisualViewportHeight(),
    scale = getVisualViewportScale(),
    offsetTop = readVisualViewportOffsetTop()
)

/**
 * The layout viewport below the visual viewport, as far as the keyboard reaches: below that, a
 * pinch-zoom hides the page, not a keyboard. [height] and [offsetTop] are in page pixels, so the
 * keyboard itself is `innerHeight / scale - height` of them.
 */
internal fun visualViewportImeHeight(
    innerHeight: Float,
    height: Float,
    scale: Float,
    offsetTop: Float
): Float = maxOf(0f, minOf(innerHeight - height - offsetTop, innerHeight / scale - height))

// language=js
private fun readVisualViewportHeight(): Float = js("window.visualViewport.height")

// language=js
private fun readVisualViewportOffsetTop(): Float = js("window.visualViewport.offsetTop")
