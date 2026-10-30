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

@file:OptIn(InternalComposeUiApi::class)

package androidx.compose.ui.webcanvas.graphics

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.webcanvas.dom.ImageData
import androidx.compose.ui.webcanvas.path.WebPath
import androidx.compose.ui.webcanvas.text.directionalRun
import kotlin.js.toJsString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A clip to a rounded rectangle is applied to the context only for the draws that may reach out of
 * it, with the pixels of the clip applied at once. The draws inside the shape are compared with
 * themselves drawn without the clip: Safari draws text and gradients under a clip that isn't a
 * rectangle a little differently, even where the clip leaves them whole.
 */
class DeferredClipTest {
    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    private val card = RoundRect(10f, 10f, 70f, 50f, CornerRadius(10f))

    private fun paint(color: Color) = Paint().also { it.color = color }

    private fun clipOf(shape: RoundRect): Path = Path().apply { addRoundRect(shape) }

    /**
     * A clip to [shape] that isn't a rounded rectangle, so applied at once: its contour followed by
     * a move to a corner of its bounds, which neither draws nor changes the bounds.
     */
    private fun clipAtOnceOf(shape: RoundRect): Path = clipOf(shape).apply { moveTo(shape.left, shape.top) }

    /** The pixels of [scene] drawn within [clip], if any. */
    private fun drawn(clip: Path?, scene: WebCanvas.() -> Unit): ImageData {
        val image = ImageBitmap(Width, Height)
        val canvas = Canvas(image) as WebCanvas
        canvas.save()
        if (clip != null) canvas.clipPath(clip)
        canvas.scene()
        canvas.restore()
        canvas.finish()
        return image.imageData()
    }

    /**
     * Draws [scene] clipped to [shape], asserts that it has the pixels of [expected], by default
     * those of the clip applied at once, and returns the calls of `save`, `restore`, `clip` and
     * `fillText` it made.
     */
    private fun drawnClippedTo(
        shape: RoundRect = card,
        expected: (() -> ImageData)? = null,
        scene: WebCanvas.() -> Unit,
    ): List<String> {
        lateinit var pixels: ImageData
        val calls = contextCallsDuring(listOf("save", "restore", "clip", "fillText")) {
            pixels = drawn(clipOf(shape), scene)
        }
        val reference = expected?.invoke() ?: drawn(clipAtOnceOf(shape), scene)
        assertEquals(0, maxPremultipliedDifference(pixels, reference), "the pixels differ")
        return calls.names
    }

    /** Draws [scene], which lies inside the [card], clipped to it, as [drawnClippedTo]: as unclipped. */
    private fun drawnInsideTheCard(scene: WebCanvas.() -> Unit): List<String> =
        drawnClippedTo(expected = { drawn(clip = null, scene) }, scene = scene)

    private fun List<String>.clips(): Int = count { it == "clip" }

    /** Whether the call at [index] is made under a `clip` that no `restore` undid before it. */
    private fun List<String>.isUnderClip(index: Int): Boolean {
        val clipped = arrayListOf(false)
        for (name in subList(0, index)) {
            when (name) {
                "save" -> clipped.add(clipped.last())
                "restore" -> clipped.removeAt(clipped.lastIndex)
                "clip" -> clipped[clipped.lastIndex] = true
            }
        }
        return clipped.last()
    }

    private fun text(text: String, size: Int = 12, shadow: TextShadow? = null, pixelRatio: Float = 1f) = TextRunDrawing(
        directionalRun(text, isRtl = false),
        "${size}px sans-serif".toJsString(),
        paint(Color.Black).asWebPaint(),
        isRtl = false,
        letterSpacing = null,
        shadow = shadow,
        pixelRatio = pixelRatio,
    )

    @Test
    fun textWithinTheShapeIsDrawnWithoutTheClip() {
        val calls = drawnInsideTheCard { drawText(text("Text"), 22f, 34f) }
        assertEquals(0, calls.clips(), "$calls")
    }

    @Test
    fun aBackgroundOfTheWholeShapeIsClippedAloneAndTheTextOverItIsNot() {
        val textInside: WebCanvas.() -> Unit = { drawText(text("Text"), 22f, 34f) }
        val backgroundClippedAlone = {
            drawn(clip = null) {
                save()
                clipPath(clipAtOnceOf(card))
                drawBackground()
                restore()
                textInside()
            }
        }
        val calls = drawnClippedTo(expected = backgroundClippedAlone) {
            drawBackground()
            textInside()
        }
        assertEquals(1, calls.clips(), "$calls")
        assertFalse(calls.isUnderClip(calls.indexOf("fillText")), "$calls")
    }

    private fun WebCanvas.drawBackground() =
        drawRoundRect(card.left, card.top, card.right, card.bottom, 10f, 10f, paint(Color.Blue))

    @Test
    fun aDrawReachingOutAfterTheBackgroundKeepsItsClip() {
        val calls = drawnClippedTo {
            drawBackground()
            drawRect(0f, 30f, Width.toFloat(), 34f, paint(Color.Red))
            drawRect(0f, 40f, Width.toFloat(), 44f, paint(Color.Red))
        }
        assertEquals(1, calls.clips(), "$calls")
    }

    @Test
    fun twoBackgroundsApplyTheClipOnce() {
        val calls = drawnClippedTo {
            drawBackground()
            drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint(Color.Red.copy(alpha = 0.5f)))
        }
        assertEquals(1, calls.clips(), "$calls")
    }

    @Test
    fun theClipOpenForTheBackgroundIsClosedByWhatChangesTheClips() {
        val reachingOut: WebCanvas.() -> Unit = { drawRect(0f, 30f, Width.toFloat(), 34f, paint(Color.Red)) }
        drawnClippedTo {
            save()
            drawBackground()
            restore()
            reachingOut()
        }
        drawnClippedTo {
            drawBackground()
            saveLayer(Rect(0f, 0f, Width.toFloat(), Height.toFloat()), Paint().apply { alpha = 0.5f })
            reachingOut()
            restore()
            reachingOut()
        }
        drawnClippedTo {
            drawBackground()
            clipRect(0f, 0f, 40f, Height.toFloat(), ClipOp.Intersect)
            reachingOut()
        }
        drawnClippedTo {
            drawBackground()
            clipPath(clipOf(RoundRect(20f, 20f, 60f, 40f, CornerRadius(6f))))
            reachingOut()
        }
    }

    @Test
    fun siblingsReachingOutOfTheShapeApplyTheClipOnce() {
        val calls = drawnClippedTo {
            repeat(3) { i ->
                save()
                translate(0f, 15f + 10f * i)
                drawRect(0f, 0f, Width.toFloat(), 4f, paint(Color.Red))
                restore()
            }
        }
        assertEquals(1, calls.clips(), "$calls")
    }

    @Test
    fun theClipAppliedForADeeperDrawStaysOnceItsSavesAreRestored() {
        val calls = drawnClippedTo {
            save()
            save()
            drawRect(0f, 30f, Width.toFloat(), 34f, paint(Color.Red))
            restore()
            restore()
            drawRect(0f, 40f, Width.toFloat(), 44f, paint(Color.Red))
        }
        assertEquals(1, calls.clips(), "$calls")
    }

    @Test
    fun aLayerIsClippedWhereItIsComposited() {
        drawnClippedTo {
            saveLayer(Rect(0f, 0f, Width.toFloat(), Height.toFloat()), Paint().apply { alpha = 0.5f })
            drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint(Color.Red))
            restore()
        }
    }

    @Test
    fun aRectClipRemovingNothingKeepsTheShape() {
        drawnClippedTo {
            clipRect(0f, 0f, Width.toFloat(), Height.toFloat(), ClipOp.Intersect)
            drawRect(0f, 0f, 30f, 30f, paint(Color.Red))
        }
    }

    @Test
    fun textReachingIntoACornerIsClipped() {
        val calls = drawnClippedTo { drawText(text("W", size = 20), 11f, 28f) }
        assertEquals(1, calls.clips(), "$calls")
    }

    @Test
    fun theShadowOfTextIsClipped() {
        val shadow = TextShadow(Color.Blue.toArgb(), offsetX = 0f, offsetY = 20f, blur = 0f)
        val calls = drawnClippedTo { drawText(text("Text", shadow = shadow), 22f, 34f) }
        assertEquals(1, calls.clips(), "$calls")
    }

    @Test
    fun theShadowOfTextInTheUnitsOfItsFontIsClipped() {
        // The 20 pixels of the shadow above, for a font drawn twice as large.
        val shadow = TextShadow(Color.Blue.toArgb(), offsetX = 0f, offsetY = 10f, blur = 0f)
        val calls = drawnClippedTo { drawText(text("Text", size = 6, shadow = shadow, pixelRatio = 2f), 22f, 34f) }
        assertEquals(1, calls.clips(), "$calls")
    }

    @Test
    fun theInkMeasuredOfTextCoversTheMarksStackedOnIt() {
        // Chrome stacks the marks under the letter far below the box of the font.
        val run = text("a" + Char(0x0323).toString().repeat(8))
        val x = 36f
        val y = 30f
        // Drawn within a deferred clip, the run is measured.
        drawn(clipOf(card)) { drawText(run, x, y) }
        val ink = assertNotNull(run.inkBounds)
        val pixels = drawn(clip = null) { drawText(run, x, y) }
        for (py in 0 until Height) {
            for (px in 0 until Width) {
                if (argbAt(pixels, px, py) ushr 24 == 0) continue
                // Within the bounds, grown by a pixel of antialiasing.
                assertTrue(
                    px + 1 >= x + ink[0] - 1 && px <= x + ink[2] + 1 && py + 1 >= y + ink[1] - 1 && py <= y + ink[3] + 1,
                    "ink at ($px, $py) out of ${ink.toList()} from ($x, $y)",
                )
            }
        }
    }

    @Test
    fun anEccentricCornerClipsWhatReachesIntoIt() {
        val shape = RoundRect(
            Rect(10f, 10f, 70f, 50f),
            topLeft = CornerRadius(40f, 4f),
            topRight = CornerRadius.Zero,
            bottomRight = CornerRadius.Zero,
            bottomLeft = CornerRadius.Zero,
        )
        val near = drawnClippedTo(shape) { drawRect(12f, 12f, 40f, 30f, paint(Color.Red)) }
        assertEquals(1, near.clips(), "near the corner: $near")
        val inside = drawnClippedTo(shape) { drawRect(30f, 20f, 60f, 40f, paint(Color.Red)) }
        assertEquals(0, inside.clips(), "inside: $inside")
    }

    @Test
    fun theBlurOfAShadowIsClipped() {
        fun shadowOf(left: Float, top: Float, right: Float, bottom: Float) = ShadowShape.RoundRect(
            left, top, right, bottom,
            radius = 0f,
            path = WebPath().apply { addRect(Rect(left, top, right, bottom)) }.snapshot(),
            key = null,
            spriteRequests = null,
        )
        val blurredOut = drawnClippedTo {
            drawShadow(shadowOf(25f, 20f, 55f, 40f), WebCanvas.OpaqueBlack, blurSigma = 6f, offsetX = 0f, offsetY = 0f)
        }
        assertEquals(1, blurredOut.clips(), "blurred out of the shape: $blurredOut")
        val inside = drawnInsideTheCard {
            drawShadow(shadowOf(30f, 25f, 50f, 35f), WebCanvas.OpaqueBlack, blurSigma = 1f, offsetX = 0f, offsetY = 0f)
        }
        assertEquals(0, inside.clips(), "inside: $inside")
    }

    @Test
    fun pointsReachingOutOfTheShapeAreClipped() {
        val dots = Paint().apply {
            color = Color.Red
            strokeWidth = 6f
            strokeCap = StrokeCap.Round
        }
        val calls = drawnClippedTo { drawPoints(PointMode.Points, listOf(Offset(40f, 30f), Offset(12f, 12f)), dots) }
        assertEquals(1, calls.clips(), "$calls")
    }

    @Test
    fun theSquareCapsOfADiagonalLineAreClipped() {
        // The corners of the caps reach half the width times the square root of 2 away from the
        // ends, past half the width times the miter limit.
        val square = Paint().apply {
            color = Color.Red
            style = PaintingStyle.Stroke
            strokeWidth = 20f
            strokeCap = StrokeCap.Square
            strokeMiterLimit = 1f
        }
        val calls = drawnClippedTo { drawLine(Offset(33f, 24f), Offset(46f, 37f), square) }
        assertEquals(1, calls.clips(), "$calls")
    }

    @Test
    fun theClipOpenForTheBackgroundIsKeptForAShallowerDepth() {
        val calls = drawnClippedTo {
            drawBackground()
            save()
            translate(0f, 20f)
            drawRect(0f, 10f, Width.toFloat(), 14f, paint(Color.Red))
            restore()
            drawRect(0f, 40f, Width.toFloat(), 44f, paint(Color.Red))
        }
        assertEquals(1, calls.clips(), "$calls")
    }

    @Test
    fun aClipDeferredAtTheRootIsGoneOnceTheCanvasFinishes() {
        val image = ImageBitmap(Width, Height)
        val canvas = Canvas(image) as WebCanvas
        canvas.clipPath(clipOf(card))
        canvas.finish()
        canvas.drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint(Color.Red))
        canvas.finish()
        assertEquals(Color.Red.toArgb(), argbAt(image.imageData(), 11, 11))
    }

    private companion object {
        const val Width = 80
        const val Height = 60
    }
}
