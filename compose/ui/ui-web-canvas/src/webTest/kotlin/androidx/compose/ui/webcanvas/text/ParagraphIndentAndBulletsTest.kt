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

package androidx.compose.ui.webcanvas.text

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Bullet
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The indentation of paragraphs, the bullets drawn in it, and the default locale of the text. */
class ParagraphIndentAndBulletsTest {
    private val density = Density(1f)
    private val style = TextStyle(fontSize = 20.sp)
    // After the implementation is registered.
    private val fontFamilyResolver by lazy { createFontFamilyResolver() }

    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    private fun intrinsics(
        text: String,
        style: TextStyle = this.style,
        annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>> = emptyList(),
    ) = ParagraphIntrinsics(text, style, annotations, density, fontFamilyResolver)

    @Test
    fun theFirstLineIndentIsPartOfTheIntrinsicWidths() {
        val plain = intrinsics("abc abc")
        val indented = intrinsics("abc abc", style.copy(textIndent = TextIndent(firstLine = 30.sp)))
        assertTrue(
            abs(indented.maxIntrinsicWidth - plain.maxIntrinsicWidth - 30f) <= 1f,
            "max: ${indented.maxIntrinsicWidth} vs ${plain.maxIntrinsicWidth}",
        )
        assertTrue(
            abs(indented.minIntrinsicWidth - plain.minIntrinsicWidth - 30f) <= 1f,
            "min: ${indented.minIntrinsicWidth} vs ${plain.minIntrinsicWidth}",
        )
        // Laid out at its intrinsic width, the indented text fits a single line.
        val paragraph = Paragraph(
            indented,
            Constraints(maxWidth = ceil(indented.maxIntrinsicWidth).toInt()),
            overflow = TextOverflow.Clip,
        )
        assertEquals(1, paragraph.lineCount)
    }

    @Test
    fun bulletsAreDrawnInTheIndentation() {
        val text = buildAnnotatedString {
            withBulletList {
                withBulletListItem { append("one") }
                withBulletListItem { append("two") }
            }
        }
        val paragraph = MultiParagraph(
            annotatedString = text,
            style = style,
            constraints = Constraints(maxWidth = 400),
            density = density,
            fontFamilyResolver = fontFamilyResolver,
            defaultLocaleList = LocaleList("en"),
        )
        assertEquals(2, paragraph.lineCount)
        val pixels = draw(400, ceil(paragraph.height).toInt()) { paragraph.paint(it, Color.Black) }
        // An indentation of 1em (20px) holds a bullet of 0.25em (5px), 0.25em before the text.
        for (line in 0 until 2) {
            val y = ((paragraph.getLineTop(line) + paragraph.getLineBottom(line)) / 2f).roundToInt()
            assertTrue(pixels(12, y).alpha > 0.5f, "no bullet on line $line")
            assertEquals(0f, pixels(3, y).alpha, "a bullet too far left on line $line")
            assertEquals(0f, pixels(17, y).alpha, "a bullet too close to the text on line $line")
        }
    }

    @Test
    fun theBulletOfAnItemThatIsCutIsNotDrawn() {
        val text = "one\ntwo"
        val red = Bullet.Default.copy(brush = SolidColor(Color.Red))
        val intrinsics = intrinsics(
            text,
            style.copy(textIndent = TextIndent(firstLine = 20.sp, restLine = 20.sp)),
            listOf(
                AnnotatedString.Range(Bullet.Default, 0, 3),
                AnnotatedString.Range(red, 4, 7),
            ),
        )
        val paragraph = Paragraph(intrinsics, Constraints(maxWidth = 400), maxLines = 1)
        assertEquals(1, paragraph.lineCount)
        val pixels = draw(400, ceil(paragraph.height).toInt()) { paragraph.paint(it, Color.Black) }
        val y = ((paragraph.getLineTop(0) + paragraph.getLineBottom(0)) / 2f).roundToInt()
        val bullet = pixels(12, y)
        assertTrue(bullet.alpha > 0.5f, "no bullet")
        assertTrue(bullet.red < 0.5f, "the bullet of the cut line was drawn on the first one: $bullet")
    }

    @Test
    fun theDefaultLocalesDecideTheDirectionOfNeutralText() {
        fun direction(defaultLocales: LocaleList, style: TextStyle = this.style) =
            (
                WebCanvasText.createParagraphIntrinsics(
                    "123",
                    style,
                    emptyList(),
                    emptyList(),
                    density,
                    fontFamilyResolver,
                    defaultLocales,
                ) as WebParagraphIntrinsics
            ).textDirection
        assertEquals(ResolvedTextDirection.Rtl, direction(LocaleList("ar")))
        assertEquals(ResolvedTextDirection.Ltr, direction(LocaleList("en")))
        // The locales of the style come first.
        assertEquals(
            ResolvedTextDirection.Ltr,
            direction(LocaleList("ar"), style.copy(localeList = LocaleList("en"))),
        )
    }

    private fun draw(
        width: Int,
        height: Int,
        paint: (Canvas) -> Unit,
    ): (Int, Int) -> Color {
        val image = ImageBitmap(width, height)
        paint(Canvas(image))
        val pixels = image.toPixelMap()
        return { x, y -> pixels[x, y] }
    }
}
