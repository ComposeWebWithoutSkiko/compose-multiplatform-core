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

package androidx.compose.ui.webcanvas.graphics

import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.webcanvas.dom.logWarning
import androidx.compose.ui.webcanvas.dom.toUint8Array
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

/**
 * An image decoded from encoded bytes (PNG, JPEG, WebP, GIF, BMP, AVIF...).
 *
 * Browsers only decode images asynchronously, while Compose decodes synchronously. So the image
 * size is read from the file header right away, and the pixels arrive a moment later: until then
 * the image draws nothing, and once they arrive [ImageDecoding] asks the windows to draw a new
 * frame.
 */
internal class DecodedImageBitmap internal constructor(
    width: Int,
    height: Int,
    hasAlpha: Boolean,
) : WebImageBitmap(width, height, ImageBitmapConfig.Argb8888, hasAlpha, ColorSpaces.Srgb) {
    private var decoded: JsAny? = null

    var isFailed: Boolean = false
        private set

    override val isMutable: Boolean get() = false

    override fun drawableSource(): JsAny? = decoded

    internal fun onDecoded(image: JsAny) {
        decoded = image
        version++
    }

    internal fun onFailed() {
        isFailed = true
    }
}

/** Tracks the asynchronous image decoding and tells the windows when new pixels arrive. */
internal object ImageDecoding {
    private val listeners = mutableListOf<() -> Unit>()
    private var pendingCount = 0

    /** Whether some images are still being decoded. */
    val hasPendingDecodes: Boolean get() = pendingCount > 0

    /** Registers [listener] to be called whenever an image finishes decoding. */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun decode(bytes: ByteArray): DecodedImageBitmap {
        val header = ImageHeader.parse(bytes)
            ?: throw IllegalArgumentException("Failed to decode image: unsupported format")
        val image = DecodedImageBitmap(header.width, header.height, header.hasAlpha)
        pendingCount++
        startDecoding(
            bytes.toUint8Array(),
            header.mimeType,
            onSuccess = { decoded ->
                pendingCount--
                image.onDecoded(decoded)
                notifyListeners()
            },
            onError = { message ->
                pendingCount--
                image.onFailed()
                logWarning("Failed to decode image: $message")
            },
        )
        return image
    }

    private fun notifyListeners() {
        // Copy: a listener may unregister itself.
        listeners.toList().forEach { it() }
    }
}

// language=js
private fun startDecoding(
    bytes: JsAny,
    mimeType: String,
    onSuccess: (JsAny) -> Unit,
    onError: (String) -> Unit,
): Unit = js(
    """{
        const blob = new Blob([bytes], mimeType ? { type: mimeType } : {});
        createImageBitmap(blob, { premultiplyAlpha: 'default', colorSpaceConversion: 'default' })
            .then((image) => onSuccess(image))
            .catch((e) => onError(String(e)));
    }"""
)

/** The information read from the header of an encoded image. */
internal class ImageHeader(
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean,
    val mimeType: String,
) {
    companion object {
        fun parse(bytes: ByteArray): ImageHeader? =
            parsePng(bytes) ?: parseJpeg(bytes) ?: parseGif(bytes) ?: parseWebp(bytes)
                ?: parseBmp(bytes) ?: parseIsoBmff(bytes) ?: parseIco(bytes)

        private fun parsePng(b: ByteArray): ImageHeader? {
            if (b.size < 26 || b.u8(0) != 0x89 || b.u8(1) != 'P'.code || b.u8(2) != 'N'.code ||
                b.u8(3) != 'G'.code
            ) return null
            val width = b.u32be(16)
            val height = b.u32be(20)
            val colorType = b.u8(25)
            // Color types 4 and 6 carry alpha; a tRNS chunk could add it to the others.
            val hasAlpha = colorType == 4 || colorType == 6 || b.indexOfAscii("tRNS", 65536) >= 0
            return valid(width, height, hasAlpha, "image/png")
        }

        private fun parseJpeg(b: ByteArray): ImageHeader? {
            if (b.size < 4 || b.u8(0) != 0xFF || b.u8(1) != 0xD8) return null
            var orientation = 1
            var i = 2
            while (i + 9 < b.size) {
                if (b.u8(i) != 0xFF) {
                    i++
                    continue
                }
                val marker = b.u8(i + 1)
                if (marker == 0xFF) {
                    i++
                    continue
                }
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                    i += 2
                    continue
                }
                val length = b.u16be(i + 2)
                if (marker == 0xE1) {
                    orientation = exifOrientation(b, i + 4, length - 2) ?: orientation
                }
                // Start of frame markers, except DHT (C4), JPG (C8) and DAC (CC).
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val height = b.u16be(i + 5)
                    val width = b.u16be(i + 7)
                    // Browsers apply the EXIF orientation, which swaps the sides for 5..8.
                    return if (orientation in 5..8) {
                        valid(height, width, false, "image/jpeg")
                    } else {
                        valid(width, height, false, "image/jpeg")
                    }
                }
                i += 2 + length
            }
            return null
        }

        private fun exifOrientation(b: ByteArray, start: Int, length: Int): Int? {
            if (length < 14 || start + 14 > b.size) return null
            if (!(b.u8(start) == 'E'.code && b.u8(start + 1) == 'x'.code &&
                    b.u8(start + 2) == 'i'.code && b.u8(start + 3) == 'f'.code)
            ) return null
            val tiff = start + 6
            val littleEndian = b.u8(tiff) == 'I'.code
            fun u16(at: Int) = if (littleEndian) b.u16le(at) else b.u16be(at)
            fun u32(at: Int) = if (littleEndian) b.u32le(at) else b.u32be(at)
            val ifd = tiff + u32(tiff + 4)
            if (ifd + 2 > b.size) return null
            val entries = u16(ifd)
            for (e in 0 until entries) {
                val entry = ifd + 2 + e * 12
                if (entry + 12 > b.size) return null
                if (u16(entry) == 0x0112) return u16(entry + 8)
            }
            return null
        }

        private fun parseGif(b: ByteArray): ImageHeader? {
            if (b.size < 10 || b.u8(0) != 'G'.code || b.u8(1) != 'I'.code || b.u8(2) != 'F'.code) {
                return null
            }
            return valid(b.u16le(6), b.u16le(8), true, "image/gif")
        }

        private fun parseWebp(b: ByteArray): ImageHeader? {
            if (b.size < 30 || !b.startsWithAscii(0, "RIFF") || !b.startsWithAscii(8, "WEBP")) {
                return null
            }
            return when {
                b.startsWithAscii(12, "VP8 ") -> {
                    // Lossy: the frame header follows a 3-byte tag and the 3-byte start code.
                    val width = b.u16le(26) and 0x3FFF
                    val height = b.u16le(28) and 0x3FFF
                    valid(width, height, false, "image/webp")
                }
                b.startsWithAscii(12, "VP8L") -> {
                    val bits = b.u32le(21)
                    val width = (bits and 0x3FFF) + 1
                    val height = ((bits ushr 14) and 0x3FFF) + 1
                    valid(width, height, true, "image/webp")
                }
                b.startsWithAscii(12, "VP8X") -> {
                    val flags = b.u8(20)
                    val width = (b.u24le(24)) + 1
                    val height = (b.u24le(27)) + 1
                    valid(width, height, (flags and 0x10) != 0, "image/webp")
                }
                else -> null
            }
        }

        private fun parseBmp(b: ByteArray): ImageHeader? {
            if (b.size < 26 || b.u8(0) != 'B'.code || b.u8(1) != 'M'.code) return null
            val width = b.u32le(18)
            val height = abs(b.u32le(22))
            return valid(width, height, true, "image/bmp")
        }

        /** AVIF and HEIF: an ISO base media file; the size is in the `ispe` property box. */
        private fun parseIsoBmff(b: ByteArray): ImageHeader? {
            if (b.size < 12 || !b.startsWithAscii(4, "ftyp")) return null
            val brand = b.asciiAt(8, 4)
            val mimeType = when (brand) {
                "avif", "avis" -> "image/avif"
                "heic", "heix", "mif1", "msf1" -> "image/heic"
                else -> return null
            }
            val ispe = b.indexOfAscii("ispe")
            if (ispe < 0 || ispe + 16 > b.size) return null
            // ispe: 4-byte version/flags, then the 4-byte width and height.
            val width = b.u32be(ispe + 8)
            val height = b.u32be(ispe + 12)
            return valid(width, height, true, mimeType)
        }

        private fun parseIco(b: ByteArray): ImageHeader? {
            if (b.size < 22 || b.u16le(0) != 0 || b.u16le(2) != 1 || b.u16le(4) == 0) return null
            val width = b.u8(6).let { if (it == 0) 256 else it }
            val height = b.u8(7).let { if (it == 0) 256 else it }
            return valid(width, height, true, "image/x-icon")
        }

        private fun valid(width: Int, height: Int, hasAlpha: Boolean, mimeType: String): ImageHeader? =
            if (width > 0 && height > 0) ImageHeader(width, height, hasAlpha, mimeType) else null

        private fun abs(value: Int) = if (value < 0) -value else value
    }
}

private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

private fun ByteArray.u16be(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

private fun ByteArray.u16le(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

private fun ByteArray.u24le(index: Int): Int =
    u8(index) or (u8(index + 1) shl 8) or (u8(index + 2) shl 16)

private fun ByteArray.u32be(index: Int): Int =
    (u8(index) shl 24) or (u8(index + 1) shl 16) or (u8(index + 2) shl 8) or u8(index + 3)

private fun ByteArray.u32le(index: Int): Int =
    u8(index) or (u8(index + 1) shl 8) or (u8(index + 2) shl 16) or (u8(index + 3) shl 24)

private fun ByteArray.startsWithAscii(index: Int, text: String): Boolean {
    if (index + text.length > size) return false
    for (i in text.indices) {
        if (u8(index + i) != text[i].code) return false
    }
    return true
}

private fun ByteArray.asciiAt(index: Int, length: Int): String =
    CharArray(length) { (u8(index + it)).toChar() }.concatToString()

private fun ByteArray.indexOfAscii(text: String, limit: Int = 4096): Int {
    val end = minOf(size, limit) - text.length
    for (i in 0..end) {
        if (startsWithAscii(i, text)) return i
    }
    return -1
}

