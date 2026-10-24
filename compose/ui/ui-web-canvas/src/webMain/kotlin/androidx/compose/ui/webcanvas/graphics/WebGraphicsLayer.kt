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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.DefaultCameraDistance
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.platform.PlatformGraphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.webcanvas.path.PathData
import androidx.compose.ui.webcanvas.path.WebPath
import kotlin.math.abs

/** The light that casts the elevation shadows, in the device space of the window. */
internal class LightingInfo {
    var centerX = 0f
    var centerY = 0f
    var centerZ = 600f
    var radius = 800f
    var ambientShadowAlpha = 0.039f
    var spotShadowAlpha = 0.19f
}

/**
 * The [PlatformGraphicsLayer] of the web backend, the equivalent of a render node: it records the
 * drawing of the layer into a [DisplayList] and replays it, transformed, clipped and composited
 * according to the layer properties, whenever the layer is drawn.
 */
internal class WebGraphicsLayer(private val lighting: LightingInfo) : PlatformGraphicsLayer {
    private val recorder = RecordingCanvas()
    private val drawScope = CanvasDrawScope()
    private var displayList: DisplayList? = null
    private var isDiscarded = false

    private var topLeft = IntOffset.Zero
    private var size = IntSize.Zero

    private var outsetLeft = 0
    private var outsetTop = 0
    private var outsetRight = 0
    private var outsetBottom = 0

    private var outline: Outline? = null
    private var clipToOutline = false
    private var outlinePath: PathData? = null

    private val transform = Matrix()
    private val transformAffine = Affine()
    private var transformDirty = true
    private var transformIsIdentity = true
    private var transformHasPerspective = false
    private val shadowProjective = Projective()

    private var layerPaint: WebPaint? = null
    private var layerPaintDirty = true

    /** [layerPaint] without its blend mode, which applies when compositing a projected image. */
    private var projectedLayerPaint: WebPaint? = null

    override var compositingStrategy: CompositingStrategy = CompositingStrategy.Auto
        set(value) {
            field = value
            layerPaintDirty = true
        }

    override var pivotOffset: Offset = Offset.Unspecified
        set(value) {
            field = value
            transformDirty = true
        }

    override var alpha: Float = 1f
        set(value) {
            field = value
            layerPaintDirty = true
        }

    override var scaleX: Float = 1f
        set(value) {
            field = value
            transformDirty = true
        }

    override var scaleY: Float = 1f
        set(value) {
            field = value
            transformDirty = true
        }

    override var translationX: Float = 0f
        set(value) {
            field = value
            transformDirty = true
        }

    override var translationY: Float = 0f
        set(value) {
            field = value
            transformDirty = true
        }

    override var shadowElevation: Float = 0f

    override var ambientShadowColor: Color = Color.Black

    override var spotShadowColor: Color = Color.Black

    override var blendMode: BlendMode = BlendMode.SrcOver
        set(value) {
            field = value
            layerPaintDirty = true
        }

    override var colorFilter: ColorFilter? = null
        set(value) {
            field = value
            layerPaintDirty = true
        }

    override var rotationX: Float = 0f
        set(value) {
            field = value
            transformDirty = true
        }

    override var rotationY: Float = 0f
        set(value) {
            field = value
            transformDirty = true
        }

    override var rotationZ: Float = 0f
        set(value) {
            field = value
            transformDirty = true
        }

    override var cameraDistance: Float = DefaultCameraDistance
        set(value) {
            field = value
            transformDirty = true
        }

    override var renderEffect: RenderEffect? = null
        set(value) {
            field = value
            layerPaintDirty = true
        }

    override fun setBounds(topLeft: IntOffset, size: IntSize) {
        this.topLeft = topLeft
        if (this.size != size) {
            this.size = size
            transformDirty = true
        }
    }

    override fun setOutline(outline: Outline?, clip: Boolean) {
        this.outline = outline
        this.clipToOutline = clip
        this.outlinePath = null
    }

    override fun setOutsets(left: Int, top: Int, right: Int, bottom: Int) {
        require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0) {
            "Outsets cannot be negative! Left: $left, Top: $top, Right: $right, Bottom: $bottom"
        }
        if (left != outsetLeft || top != outsetTop || right != outsetRight || bottom != outsetBottom) {
            outsetLeft = left
            outsetTop = top
            outsetRight = right
            outsetBottom = bottom
            transformDirty = true
            outlinePath = null
        }
    }

    override fun record(
        density: Density,
        layoutDirection: LayoutDirection,
        layer: GraphicsLayer,
        block: DrawScope.() -> Unit,
    ) {
        if (isDiscarded) return
        val canvas = recorder
        canvas.beginRecording()
        try {
            val hasOutsets = outsetLeft > 0 || outsetTop > 0
            if (hasOutsets) {
                canvas.save()
                canvas.translate(outsetLeft.toFloat(), outsetTop.toFloat())
            }
            drawScope.draw(
                density = density,
                layoutDirection = layoutDirection,
                canvas = canvas,
                size = layer.size.toSize(),
                graphicsLayer = layer,
                block = block,
            )
            if (hasOutsets) {
                canvas.restore()
            }
        } finally {
            displayList = canvas.endRecording()
        }
    }

    override fun draw(canvas: Canvas) {
        when (canvas) {
            is RecordingCanvas -> canvas.drawLayer(this)
            is WebCanvas -> drawInto(canvas)
            else -> Unit // A foreign canvas can't replay web drawing commands.
        }
    }

    override fun discardDisplayList() {
        displayList = null
        isDiscarded = true
    }

    /** Draws the layer into [canvas]: the equivalent of drawing a render node. */
    internal fun drawInto(canvas: WebCanvas) {
        val displayList = displayList ?: return
        updateTransform()
        canvas.save()
        canvas.translate((topLeft.x - outsetLeft).toFloat(), (topLeft.y - outsetTop).toFloat())
        if (transformHasPerspective && drawInPerspective(canvas, displayList)) {
            canvas.restore()
            return
        }
        if (!transformIsIdentity) {
            canvas.concat(transformAffine)
        }
        val outline = outline
        if (shadowElevation > 0f && outline != null) {
            ElevationShadows.draw(
                canvas = canvas,
                shape = outlinePath(outline),
                elevation = shadowElevation,
                ambientColor = ambientShadowColor,
                spotColor = spotShadowColor,
                alpha = alpha,
                lighting = lighting,
            )
        }
        if (clipToOutline && outline != null) {
            clipTo(canvas, outline)
        }
        val width = (size.width + outsetLeft + outsetRight).toFloat()
        val height = (size.height + outsetTop + outsetBottom).toFloat()
        val paint = layerPaint()
        if (paint != null) {
            canvas.saveLayer(0f, 0f, width, height, paint)
        } else if (alpha < 1f) {
            canvas.modulateAlpha(alpha)
        }
        if (!displayList.isEmpty) {
            displayList.replay(canvas)
        }
        if (paint != null) {
            canvas.restore()
        }
        canvas.restore()
    }

    /**
     * Draws the layer with a perspective transformation (rotations around the x or y axis), which
     * needs WebGL. Returns `false` if it isn't available: the layer is then drawn with the affine
     * part of its transformation.
     */
    private fun drawInPerspective(canvas: WebCanvas, displayList: DisplayList): Boolean {
        if (!WebGLRenderer.isAvailable()) return false
        val outline = outline
        if (shadowElevation > 0f && outline != null) {
            // The shadow of the projected outline, cast in the device space.
            val device = shadowProjective.setFrom(transform).postConcat(canvas.currentMatrix)
            val projected = device.mapPath(outlinePath(outline))
            if (projected != null) {
                val scale = canvas.currentMatrix.averageScale
                canvas.saveInDeviceSpace()
                ElevationShadows.draw(
                    canvas = canvas,
                    shape = projected,
                    elevation = shadowElevation * scale,
                    ambientColor = ambientShadowColor,
                    spotColor = spotShadowColor,
                    alpha = alpha,
                    lighting = lighting,
                )
                canvas.restore()
            }
        }
        val width = (size.width + outsetLeft + outsetRight).toFloat()
        val height = (size.height + outsetTop + outsetBottom).toFloat()
        val paint = layerPaint()
        val contentPaint = projectedLayerPaint
        return canvas.drawInPerspective(
            transform = transform,
            left = 0f,
            top = 0f,
            right = width,
            bottom = height,
            blendMode = paint?.blendMode ?: BlendMode.SrcOver,
        ) { content ->
            if (clipToOutline && outline != null) {
                clipTo(content, outline)
            }
            if (contentPaint != null) {
                content.saveLayer(0f, 0f, width, height, contentPaint)
            } else if (alpha < 1f) {
                content.modulateAlpha(alpha)
            }
            if (!displayList.isEmpty) {
                displayList.replay(content)
            }
            if (contentPaint != null) {
                content.restore()
            }
        }
    }

    private fun clipTo(canvas: WebCanvas, outline: Outline) {
        val dx = outsetLeft.toFloat()
        val dy = outsetTop.toFloat()
        when (outline) {
            is Outline.Rectangle -> canvas.clipRect(
                outline.rect.left + dx,
                outline.rect.top + dy,
                outline.rect.right + dx,
                outline.rect.bottom + dy,
                ClipOp.Intersect,
            )
            else -> canvas.clipPathData(outlinePath(outline), ClipOp.Intersect)
        }
    }

    /** The outline as a path in the coordinates of the display list (shifted by the outsets). */
    private fun outlinePath(outline: Outline): PathData {
        outlinePath?.let { return it }
        val path = WebPath()
        when (outline) {
            is Outline.Rectangle -> path.addRect(outline.rect)
            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is Outline.Generic -> path.addPath(outline.path)
        }
        if (outsetLeft != 0 || outsetTop != 0) {
            path.translate(Offset(outsetLeft.toFloat(), outsetTop.toFloat()))
        }
        return path.snapshot().also { outlinePath = it }
    }

    private fun updateTransform() {
        if (!transformDirty) return
        transformDirty = false
        val pivotX: Float
        val pivotY: Float
        if (pivotOffset.isUnspecified) {
            pivotX = size.width / 2f + outsetLeft
            pivotY = size.height / 2f + outsetTop
        } else {
            pivotX = pivotOffset.x + outsetLeft
            pivotY = pivotOffset.y + outsetTop
        }
        prepareLayerTransform(
            matrix = transform,
            pivotX = pivotX,
            pivotY = pivotY,
            translationX = translationX,
            translationY = translationY,
            rotationX = rotationX,
            rotationY = rotationY,
            rotationZ = rotationZ,
            scaleX = scaleX,
            scaleY = scaleY,
            cameraDistance = cameraDistance,
        )
        transform.toAffine(transformAffine)
        transformHasPerspective = transform.hasPerspective()
        transformIsIdentity = !transformHasPerspective && transformAffine.isIdentity
    }

    /** The paint to composite the layer with, or `null` if it can be drawn directly. */
    private fun layerPaint(): WebPaint? {
        if (!layerPaintDirty) return layerPaint
        layerPaintDirty = false
        val alphaNeedsLayer = alpha < 1f && compositingStrategy != CompositingStrategy.ModulateAlpha
        val needsLayer = alphaNeedsLayer || colorFilter != null || blendMode != BlendMode.SrcOver ||
            renderEffect != null || compositingStrategy == CompositingStrategy.Offscreen
        layerPaint = if (needsLayer) {
            WebPaint().also {
                it.alpha = alpha
                it.colorFilter = colorFilter
                it.blendMode = blendMode
                it.renderEffect = renderEffect?.webRenderEffect
            }
        } else {
            null
        }
        val contentNeedsLayer = alphaNeedsLayer || colorFilter != null || renderEffect != null ||
            compositingStrategy == CompositingStrategy.Offscreen
        projectedLayerPaint = if (contentNeedsLayer) {
            WebPaint().also {
                it.alpha = alpha
                it.colorFilter = colorFilter
                it.renderEffect = renderEffect?.webRenderEffect
            }
        } else {
            null
        }
        return layerPaint
    }
}

/**
 * Builds the transformation of a layer around its pivot, like a render node: scale and rotation
 * around the pivot, then translation. Rotations around the x and y axes use a perspective
 * projection with the camera at [cameraDistance] (in inches, like on Android).
 */
internal fun prepareLayerTransform(
    matrix: Matrix,
    pivotX: Float,
    pivotY: Float,
    translationX: Float,
    translationY: Float,
    rotationX: Float,
    rotationY: Float,
    rotationZ: Float,
    scaleX: Float,
    scaleY: Float,
    cameraDistance: Float,
) {
    matrix.reset()
    matrix.translate(x = -pivotX, y = -pivotY)
    matrix *= Matrix().apply {
        rotateZ(rotationZ)
        rotateY(rotationY)
        rotateX(rotationX)
        scale(scaleX, scaleY)
    }
    if (abs(rotationX) > NonZeroEpsilon || abs(rotationY) > NonZeroEpsilon) {
        matrix *= Matrix().apply {
            // The camera location is passed in inches, set in pt.
            val depth = cameraDistance * 72f
            this[2, 3] = -1f / depth
        }
    }
    matrix *= Matrix().apply {
        translate(x = pivotX + translationX, y = pivotY + translationY)
    }
    // The z row and column are irrelevant in 2D.
    matrix[2, 0] = 0f
    matrix[2, 1] = 0f
    matrix[2, 3] = 0f
    matrix[0, 2] = 0f
    matrix[1, 2] = 0f
    matrix[3, 2] = 0f
}

private const val NonZeroEpsilon = 0.001f
