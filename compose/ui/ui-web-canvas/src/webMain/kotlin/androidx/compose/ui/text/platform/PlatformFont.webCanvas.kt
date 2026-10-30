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

package androidx.compose.ui.text.platform

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

// The same font factories as the Skia-based targets provide (see ui-skiko), so that code creating
// fonts from bytes (e.g. Compose resources) works unchanged on the web.

/**
 * Creates a Font using byte array with loaded font data.
 *
 * @param identity Unique identity for a font. Used internally to distinguish fonts.
 * @param getData should return Byte array with loaded font data.
 * @param weight The weight of the font. The system uses this to match a font to a font request
 * that is given in a [androidx.compose.ui.text.SpanStyle].
 * @param style The style of the font, normal or italic. The system uses this to match a font to a
 * font request that is given in a [androidx.compose.ui.text.SpanStyle].
 *
 * @see androidx.compose.ui.text.font.FontFamily
 */
public fun Font(
    identity: String,
    getData: () -> ByteArray,
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal
): Font = LoadedFont(identity, getData, weight, style, FontVariation.Settings())

/**
 * Creates a Font using byte array with loaded font data.
 *
 * @param identity Unique identity for a font. Used internally to distinguish fonts.
 * @param getData should return Byte array with loaded font data.
 * @param weight The weight of the font. The system uses this to match a font to a font request
 * that is given in a [androidx.compose.ui.text.SpanStyle]. For non-variable fonts, this directly
 * affects the font style.
 * @param style The style of the font, normal or italic. The system uses this to match a font to a
 * font request that is given in a [androidx.compose.ui.text.SpanStyle]. For non-variable fonts,
 * this directly affects the font style.
 * @param variationSettings Specifies the variation settings for the font. On the web only the
 * weight axis (`wght`) is applied.
 *
 * @see androidx.compose.ui.text.font.FontFamily
 */
public fun Font(
    identity: String,
    getData: () -> ByteArray,
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal,
    variationSettings: FontVariation.Settings = FontVariation.Settings(weight, style)
): Font = LoadedFont(identity, getData, weight, style, variationSettings)

/**
 * Creates a Font using byte array with loaded font data.
 *
 * @param identity Unique identity for a font. Used internally to distinguish fonts.
 * @param data Byte array with loaded font data.
 * @param weight The weight of the font. The system uses this to match a font to a font request
 * that is given in a [androidx.compose.ui.text.SpanStyle].
 * @param style The style of the font, normal or italic. The system uses this to match a font to a
 * font request that is given in a [androidx.compose.ui.text.SpanStyle].
 *
 * @see androidx.compose.ui.text.font.FontFamily
 */
public fun Font(
    identity: String,
    data: ByteArray,
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal
): Font = Font(
    identity = identity,
    getData = { data },
    weight = weight,
    style = style,
    variationSettings = FontVariation.Settings(),
)

/**
 * Creates a Font using byte array with loaded font data.
 *
 * @param identity Unique identity for a font. Used internally to distinguish fonts.
 * @param data Byte array with loaded font data.
 * @param weight The weight of the font. The system uses this to match a font to a font request
 * that is given in a [androidx.compose.ui.text.SpanStyle]. For non-variable fonts, this directly
 * affects the font style.
 * @param style The style of the font, normal or italic. The system uses this to match a font to a
 * font request that is given in a [androidx.compose.ui.text.SpanStyle]. For non-variable fonts,
 * this directly affects the font style.
 * @param variationSettings Specifies the variation settings for the font. On the web only the
 * weight axis (`wght`) is applied.
 *
 * @see androidx.compose.ui.text.font.FontFamily
 */
public fun Font(
    identity: String,
    data: ByteArray,
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal,
    variationSettings: FontVariation.Settings = FontVariation.Settings(weight, style)
): Font = Font(
    identity = identity,
    getData = { data },
    weight = weight,
    style = style,
    variationSettings = variationSettings,
)
