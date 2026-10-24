/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.compose.foundation.copyPasteAndroidTests.text

import androidx.compose.foundation.assertContainsColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class TextOverflowTest {

    private val BoxTag = "wrapping box"

    @Suppress("DEPRECATION")
    @Test
    fun paint_singleParagraph_withVisibleOverflow() = runSkikoComposeUiTest {
        val text = "Hello\nHello\nHello\nHello"

        val lineHeight = 20
        val boxHeight = 100
        val boxWidth = 200

        setContent {
            CompositionLocalProvider(
                LocalDensity provides density
            ) {
                Box(Modifier.testTag(BoxTag).size(boxWidth.dp, boxHeight.dp)) {
                    BasicText(
                        text = text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = (2 * lineHeight).dp)
                            .padding(bottom = lineHeight.dp),
                        style = TextStyle(
                            fontSize = 20.sp,
                            color = Color.Red,
                            lineHeight = 20.sp
                        ),
                        overflow = TextOverflow.Visible
                    )
                }
            }
        }

        val boxBitmap = onNodeWithTag(BoxTag).captureToImage()
        boxBitmap.crop(0, 2 * lineHeight, boxWidth, boxHeight - 2 * lineHeight)
            .assertContainsColor(Color.Red)
    }

    @Suppress("DEPRECATION")
    @Test
    fun paint_multiParagraph_withVisibleOverflow() = runSkikoComposeUiTest {
        val text = buildAnnotatedString {
            append("Hello\nHello")
            withStyle(ParagraphStyle(textAlign = TextAlign.Center)) {
                append("Hello\nHello")
            }
        }
        val lineHeight = 20
        val boxHeight = 100
        val boxWidth = 200

        setContent {
            CompositionLocalProvider(
                LocalDensity provides density
            ) {
                Box(Modifier.testTag(BoxTag).size(boxWidth.dp, boxHeight.dp)) {
                    BasicText(
                        text = text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = (2 * lineHeight).dp)
                            .padding(bottom = lineHeight.dp),
                        style = TextStyle(
                            fontSize = 20.sp,
                            color = Color.Red,
                            lineHeight = 20.sp
                        ),
                        overflow = TextOverflow.Visible
                    )
                }
            }
        }

        val boxBitmap = onNodeWithTag(BoxTag).captureToImage()
        boxBitmap.crop(0, 2 * lineHeight, boxWidth, boxHeight - 2 * lineHeight)
            .assertContainsColor(Color.Red)
    }
}

/** Copies a part of the image, with the platform's graphics implementation. */
private fun ImageBitmap.crop(x: Int, y: Int, width: Int, height: Int): ImageBitmap =
    ImageBitmap(width, height).also {
        Canvas(it).drawImageRect(
            this,
            srcOffset = IntOffset(x, y),
            srcSize = IntSize(width, height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, height),
            paint = Paint(),
        )
    }
