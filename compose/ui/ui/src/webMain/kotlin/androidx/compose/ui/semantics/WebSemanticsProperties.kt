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

package androidx.compose.ui.semantics

/** Semantics properties that only the web accessibility tree consumes. */
object WebSemanticsProperties {
    /** The URL a node navigates to. */
    val LinkUrl = SemanticsPropertyKey<String>("LinkUrl")
}

/**
 * The URL this node navigates to. The web accessibility tree represents such a node as an
 * `<a href>` element, so assistive technology and crawlers see a real link instead of a generic
 * clickable element.
 */
var SemanticsPropertyReceiver.linkUrl: String by WebSemanticsProperties.LinkUrl
