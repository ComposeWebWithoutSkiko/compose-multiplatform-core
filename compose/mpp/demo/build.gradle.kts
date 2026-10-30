/*
 * Copyright 2023 The Android Open Source Project
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

@file:OptIn(ExperimentalWasmDsl::class)

import java.util.*
import kotlin.collections.map
import org.gradle.internal.classpath.Instrumented.systemProperty
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import sun.jvmstat.monitor.MonitoredVmUtil.mainClass

plugins {
    id("AndroidXComposePlugin")
    id("kotlin-multiplatform")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    applyDefaultHierarchyTemplate()
    jvm("desktop")
    js {
        outputModuleName = "mpp-demo"
        browser {
            commonWebpackConfig {
                outputFileName = "demo.js"
            }
        }
        binaries.executable()
    }
    wasmJs {
        outputModuleName = "mpp-demo"
        browser {
            // https://youtrack.jetbrains.com/issue/KT-68614
            val projectDirPath = project.projectDir.resolve("src").path
            commonWebpackConfig {
                outputFileName = "demo.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    open = mapOf(
                        "app" to mapOf(
                            "name" to "google-chrome",
                        )
                    )
                    static = (static ?: mutableListOf()).apply {
                        // Serve sources to debug inside browser
                        add(projectDirPath)
                    }
                }
            }
        }
        binaries.executable()
    }
    macosArm64() {
        binaries {
            executable() {
                entryPoint = "androidx.compose.mpp.demo.main"
                freeCompilerArgs += listOf(
                    "-linker-option", "-framework", "-linker-option", "Metal"
                )
                // TODO: the current release binary surprises LLVM, so disable checks for now.
                freeCompilerArgs += "-Xdisable-phases=VerifyBitcode"
            }
        }
    }
    iosArm64("iosArm64") {
        binaries {
            executable() {
                entryPoint = "androidx.compose.mpp.demo.main"
                freeCompilerArgs += listOf(
                    "-linker-option", "-framework", "-linker-option", "Metal",
                    "-linker-option", "-framework", "-linker-option", "CoreText",
                    "-linker-option", "-framework", "-linker-option", "CoreGraphics"
                )
                // TODO: the current compose binary surprises LLVM, so disable checks for now.
                freeCompilerArgs += "-Xdisable-phases=VerifyBitcode"
            }
        }
    }
    iosSimulatorArm64("iosSimArm64") {
        binaries {
            executable() {
                entryPoint = "androidx.compose.mpp.demo.main"
                freeCompilerArgs += listOf(
                    "-linker-option", "-framework", "-linker-option", "Metal",
                    "-linker-option", "-framework", "-linker-option", "CoreText",
                    "-linker-option", "-framework", "-linker-option", "CoreGraphics"
                )
                // TODO: the current compose binary surprises LLVM, so disable checks for now.
                freeCompilerArgs += "-Xdisable-phases=VerifyBitcode"
            }
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinCoroutinesCore)
                implementation(libs.kotlinSerializationCore)

                implementation(project(":compose:foundation:foundation"))
                implementation(project(":compose:foundation:foundation-layout"))
                implementation(project(":compose:material3:material3"))
                implementation(project(":compose:material3:material3-window-size-class"))
                implementation(project(":compose:material3:adaptive:adaptive"))
                implementation(project(":compose:material3:adaptive:adaptive-layout"))
                implementation(project(":compose:material3:adaptive:adaptive-navigation"))
                implementation(project(":compose:material:material"))
                implementation(project(":compose:runtime:runtime"))
                implementation(project(":compose:ui:ui"))
                implementation(project(":compose:ui:ui-geometry"))
                implementation(project(":compose:ui:ui-graphics"))
                implementation(project(":compose:ui:ui-text"))
                implementation(project(":compose:ui:ui-backhandler"))
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-common:2.11.0")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime:2.11.0")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate:2.11.0")
                implementation(project(":navigation:navigation-common"))
                implementation(project(":navigation:navigation-compose"))
                implementation(project(":navigation:navigation-runtime"))

                implementation("org.jetbrains.compose.material:material-icons-core:1.7.3") {
                    // exclude dependencies, because they override local projects when we build 0.0.0-* version
                    // (see https://repo1.maven.org/maven2/org/jetbrains/compose/material/material-icons-core-desktop/1.6.11/material-icons-core-desktop-1.6.11.module)
                    exclude("org.jetbrains.compose.runtime")
                    exclude("org.jetbrains.compose.ui")
                }
                implementation("org.jetbrains.compose.components:components-resources:1.11.1") {
                    exclude("org.jetbrains.compose.runtime")
                    exclude("org.jetbrains.compose.ui")
                    exclude("org.jetbrains.compose.foundation")
                }
            }
        }

        // The Skia-based targets (desktop and native). The web targets draw through the
        // browser's Canvas 2D API and don't use Skia.
        val skikoMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.skiko)
                implementation(libs.skiko.skottie)
                implementation(project(":compose:ui:ui-skiko"))
            }
        }

        val desktopMain by getting {
            dependsOn(skikoMain)
            dependencies {
                implementation(libs.kotlinCoroutinesSwing)
                implementation(libs.skikoAwtRuntime)
                implementation(libs.skikoSkottieAwtRuntime)
            }
        }

        val webMain by getting {
            dependencies {
                implementation(libs.kotlinSerializationJson)
                implementation(libs.kotlinXw3c)

                // https://github.com/mrdoob/three.js/ for WebGl demo
                implementation(npm("three", "0.185.0"))
            }
        }

        val nativeMain by getting { dependsOn(skikoMain) }
        val darwinMain by creating { dependsOn(nativeMain) }
        val macosMain by getting { dependsOn(darwinMain) }
        val iosMain by getting { dependsOn(darwinMain) }
    }

}

// components-resources of upstream needs Skia on the web. The web targets take its fork instead
// (ComposeWebWithoutSkiko/compose-multiplatform), which is only published to mavenLocal so far.
repositories {
    mavenLocal {
        content {
            includeModule("org.jetbrains.compose.components", "components-resources-js")
            includeModule("org.jetbrains.compose.components", "components-resources-wasmJs")
        }
    }
}

configurations.configureEach {
    val resourcesFork = when {
        name.startsWith("js") -> "components-resources-js"
        name.startsWith("wasmJs") -> "components-resources-wasmJs"
        else -> return@configureEach
    }
    resolutionStrategy.dependencySubstitution {
        substitute(module("org.jetbrains.compose.components:components-resources"))
            .using(module("org.jetbrains.compose.components:$resourcesFork:1.13.0-beta01-webcanvas"))
    }
}

enum class Target(val simulator: Boolean, val key: String) {
    IOS_ARM64(false, "iosArm64"),
    IOS_SIM_ARM64(true, "iosSimArm64"),
}

if (System.getProperty("os.name") == "Mac OS X") {
// Create Xcode integration tasks.
    val sdkName: String? = System.getenv("SDK_NAME")

    val target = sdkName.orEmpty().let {
        when {
            it.startsWith("iphoneos") -> Target.IOS_ARM64
            it.startsWith("iphonesimulator") -> {
                if (System.getProperty("os.arch") == "aarch64") {
                    Target.IOS_SIM_ARM64
                } else {
                    error("x64 host is not supported anymore!")
                }
            }

            else -> Target.IOS_SIM_ARM64
        }
    }

    val targetBuildDir: String? = System.getenv("TARGET_BUILD_DIR")
    val executablePath: String? = System.getenv("EXECUTABLE_PATH")
    val buildType = System.getenv("CONFIGURATION")
        ?.let { NativeBuildType.valueOf(it.uppercase(Locale.getDefault())) }
        ?: NativeBuildType.DEBUG

    val currentTarget = kotlin.targets[target.key] as KotlinNativeTarget
    val kotlinBinary = currentTarget.binaries.getExecutable(buildType)
    val xcodeIntegrationGroup = "Xcode integration"

    val packForXCode = if (sdkName == null || targetBuildDir == null || executablePath == null) {
        // The build is launched not by Xcode ->
        // We cannot create a copy task and just show a meaningful error message.
        tasks.register("packForXCode") {
            doLast {
                group = xcodeIntegrationGroup
                throw IllegalStateException("Please run the task from Xcode")
            }
        }
    } else {
        // Otherwise copy the executable into the Xcode output directory.
        tasks.register("packForXCode", Copy::class.java) {
            dependsOn(kotlinBinary.linkTaskProvider)

            group = xcodeIntegrationGroup
            destinationDir = file(targetBuildDir)

            val dsymSource = kotlinBinary.outputFile.absolutePath + ".dSYM"
            val dsymDestination = File(executablePath).parentFile.name + ".dSYM"
            val oldExecName = kotlinBinary.outputFile.name
            val newExecName = File(executablePath).name

            from(dsymSource) {
                into(dsymDestination)
                rename(oldExecName, newExecName)
            }

            from(kotlinBinary.outputFile) {
                rename { executablePath }
            }
        }
    }
}

tasks.register("runDesktop", JavaExec::class.java) {
    dependsOn(":compose:desktop:desktop:jvmJar")
    mainClass.set("androidx.compose.mpp.demo.Main_desktopKt")
    args = listOfNotNull(project.findProperty("args")?.toString())
    systemProperty("skiko.fps.enabled", "true")
    val compilation = kotlin.jvm("desktop").compilations["main"]
    classpath =
        compilation.output.allOutputs +
            compilation.runtimeDependencyFiles
}


project.tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        "-Xir-dce",
        "-Xwasm-generate-wat",
        "-Xwasm-enable-array-range-checks"
    )
}

val demoBuildInfoDir = layout.buildDirectory.dir("generated/buildInfo/kotlin")

val generateDemoBuildInfo = tasks.register("generateDemoBuildInfo") {
    val outputDir = demoBuildInfoDir
    val repoDir = projectDir
    outputs.dir(outputDir)
    outputs.upToDateWhen { false }
    doLast {
        fun exec(command: List<String>): String = try {
            val process = ProcessBuilder(command)
                .directory(repoDir)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output
        } catch (e: Exception) {
            ""
        }

        fun git(vararg args: String) = exec(listOf("git") + args)

        fun esc(value: String) = value
            .replace("\\", "\\\\")
            .replace("$", "\\\$")
            .replace("\"", "\\\"")
            .replace("\r", "")
            .replace("\n", "\\n")
            .replace("\t", "\\t")

        val branch = git("rev-parse", "--abbrev-ref", "HEAD")
        val hash = git("rev-parse", "--short", "HEAD")
        val author = git("log", "-1", "--format=%an")
        val message = git("log", "-1", "--format=%B")
        val buildTime = "%1\$tF %1\$tT %1\$tZ (%1\$tz)".format(Date())

        val outFile = outputDir.get()
            .file("androidx/compose/mpp/demo/BuildInfo.kt").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            """
            |package androidx.compose.mpp.demo
            |
            |internal object BuildInfo {
            |    const val branch: String = "${esc(branch)}"
            |    const val commitHash: String = "${esc(hash)}"
            |    const val author: String = "${esc(author)}"
            |    const val buildTime: String = "${esc(buildTime)}"
            |    const val commitMessage: String = "${esc(message)}"
            |}
            |""".trimMargin()
        )
    }
}

kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generateDemoBuildInfo)
kotlin.sourceSets.getByName("commonMain").resources.srcDir("src/commonMain/composeResources")
