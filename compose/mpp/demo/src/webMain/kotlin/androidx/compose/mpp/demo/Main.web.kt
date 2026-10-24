/*
 * Copyright 2025 The Android Open Source Project
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

package androidx.compose.mpp.demo

import androidx.compose.mpp.demo.bugs.BugsScreen
import androidx.compose.mpp.demo.interops.HtmlInteropDemos
import androidx.compose.mpp.demo.webgl.PlainWebGlScreen
import androidx.compose.mpp.demo.webgl.ThreeJsTextureAdoptionScreen
import androidx.compose.mpp.demo.webgl.VideoWebGlScreen
import androidx.compose.mpp.demo.webgl.HtmlInCanvasWebGlScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.mpp.demo.embedded.embeddedScrollDemo
import androidx.compose.mpp.demo.webgl.WebGLDemoScreen
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToBrowserNavigation
import androidx.navigation.compose.rememberNavController
import kotlinx.browser.window

private fun queryParams(): Map<String, String> {
    return window.location.search
        .removePrefix("?")
        .split("&")
        .filter { it.contains("=") }
        .associate { val (k, v) = it.split("=", limit = 2); k to v }
}

fun main() {
    val demo = queryParams()["demo"] ?: "default"
    when (demo) {
        "default" -> defaultComposeDemo()
        "embedded" -> embeddedScrollDemo(composeScroll = false)
        "embeddedWithScroll" -> embeddedScrollDemo(composeScroll = true)
    }
}

@OptIn(
    ExperimentalComposeUiApi::class,
    ExperimentalBrowserHistoryApi::class
)
fun defaultComposeDemo() {
    ComposeViewport {
        val navController = rememberNavController()
        val app = remember { App(
            extraScreens = listOf(
                WebGLDemoScreen,
                BugsScreen,
                Screen.Example("Web Clipboard API example") {
                    WebClipboardDemo()
                },
                HtmlInteropDemos,
                HapticFeedbackExample,
            )
        ) }

        // The browser renders text with the system fonts, emoji and CJK included: unlike with Skia,
        // no fallback fonts need to be downloaded before showing the content.
        app.Content(navController)

        // TODO: possibly suboptimal workaround for https://youtrack.jetbrains.com/issue/CMP-7136/web-Its-non-trivial-to-bind-to-navigation-if-NavHost-is-called-asynchronously
        LaunchedEffect(Unit) {
            navController.bindToBrowserNavigation()
        }

        LaunchedEffect(Unit) {
            setupBackingTextAreaDebugHints()
        }
    }
}
