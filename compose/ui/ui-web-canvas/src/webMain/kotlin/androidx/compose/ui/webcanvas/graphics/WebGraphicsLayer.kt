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
import androidx.compose.ui.geometry.isSimple
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.geometry.translate
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
import androidx.compose.ui.graphics.prepareTransformationMatrix
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.webcanvas.path.PathData
import androidx.compose.ui.webcanvas.path.WebPath
import androidx.compose.ui.webcanvas.path.asWebPath

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

    /**
     * The outsets, which enlarge the layer around its size. Like the content of a render node, the
     * content is recorded shifted by them (see [record]), so that its space, where its offscreen
     * buffer and its render effects work, starts at the corner of the enlarged layer; the outline
     * and the transformation are in the space of the layer itself.
     */
    private var outsetLeft = 0
    private var outsetTop = 0
    private var outsetRight = 0
    private var outsetBottom = 0

    private var outline: Outline? = null
    private var clipToOutline = false
    private var outlinePath: PathData? = null

    /** The outline as a shape casting shadows, see [shadowShape]. */
    private var cachedShadowShape: ShadowShape? = null

    /**
     * The shadows the layer was asked for last, which outlast its shapes: the outline of a layer
     * animated through its layer block is set anew in every frame, a path one as a new object.
     */
    private var shadowRequests: ShadowSprites.Requests? = null

    private val transform = Matrix()
    private val transformAffine = Affine()
    private var transformDirty = true
    private var transformIsIdentity = true
    private var transformHasPerspective = false
    private val shadowProjective = Projective()

    /** How the content is composited, see [updateComposition]. */
    private var composition = Composition.Direct

    /** How the content is composited into the image that is projected in perspective. */
    private var projectedComposition = Composition.Direct
    private var compositionDirty = true

    /** The paint compositing the content when it's [Composition.Offscreen]. */
    private val layerPaint = WebPaint()

    /** [layerPaint] without its blend mode, which applies when compositing a projected image. */
    private val projectedLayerPaint = WebPaint()

    override var compositingStrategy: CompositingStrategy = CompositingStrategy.Auto
        set(value) {
            field = value
            compositionDirty = true
        }

    override var pivotOffset: Offset = Offset.Unspecified
        set(value) {
            field = value
            transformDirty = true
        }

    override var alpha: Float = 1f
        set(value) {
            field = value
            compositionDirty = true
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
        set(value) {
            field = value
            ambientShadowArgb = value.toArgb()
        }

    override var spotShadowColor: Color = Color.Black
        set(value) {
            field = value
            spotShadowArgb = value.toArgb()
        }

    // The shadow colors as ARGB, converted once.
    private var ambientShadowArgb = Color.Black.toArgb()
    private var spotShadowArgb = Color.Black.toArgb()

    override var blendMode: BlendMode = BlendMode.SrcOver
        set(value) {
            field = value
            compositionDirty = true
        }

    override var colorFilter: ColorFilter? = null
        set(value) {
            field = value
            compositionDirty = true
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
            compositionDirty = true
        }

    override fun setBounds(topLeft: IntOffset, size: IntSize) {
        this.topLeft = topLeft
        if (this.size != size) {
            this.size = size
            transformDirty = true
        }
    }

    override fun setOutline(outline: Outline?, clip: Boolean) {
        // The same outline is set again whenever the elevation or the clip changes. A path set
        // again makes a new Outline.Generic, which is equal only to itself.
        if (outline != this.outline) invalidateOutlineShapes()
        this.outline = outline
        this.clipToOutline = clip
    }

    override fun setOutsets(left: Int, top: Int, right: Int, bottom: Int) {
        require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0) {
            "Outsets cannot be negative! Left: $left, Top: $top, Right: $right, Bottom: $bottom"
        }
        outsetLeft = left
        outsetTop = top
        outsetRight = right
        outsetBottom = bottom
    }

    /** Forgets the shapes made from the outline. */
    private fun invalidateOutlineShapes() {
        outlinePath = null
        cachedShadowShape = null
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
            val recorded = canvas.endRecording()
            if (recorded.canModulateAlpha != displayList?.canModulateAlpha) compositionDirty = true
            displayList = recorded
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
        canvas.translate(topLeft.x.toFloat(), topLeft.y.toFloat())
        if (transformHasPerspective && drawInPerspective(canvas)) {
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
                shape = shadowShape(outline),
                elevation = shadowElevation,
                ambientArgb = ambientShadowArgb,
                spotArgb = spotShadowArgb,
                alpha = alpha,
                lighting = lighting,
            )
        }
        updateComposition()
        drawContent(canvas, displayList, composition, layerPaint)
        canvas.restore()
    }

    /**
     * Draws the display list into [canvas], which is in the space of the layer: clipped to the
     * outline if the layer clips to it, with [composition], through [paint] if offscreen. Leaves
     * [canvas] clipped and in the space of the content (see [outsetLeft]): the caller restores it.
     */
    private fun drawContent(
        canvas: WebCanvas,
        displayList: DisplayList,
        composition: Composition,
        paint: WebPaint,
    ) {
        val outline = outline
        if (clipToOutline && outline != null) {
            clipTo(canvas, outline)
        }
        canvas.translate(-outsetLeft.toFloat(), -outsetTop.toFloat())
        val width = (size.width + outsetLeft + outsetRight).toFloat()
        val height = (size.height + outsetTop + outsetBottom).toFloat()
        val drawsContent = when (composition) {
            Composition.Direct -> true
            Composition.ModulateAlpha -> {
                // Restored with the layer.
                canvas.modulateAlpha(alpha)
                true
            }
            Composition.ClippedAlpha -> {
                canvas.saveAlphaLayer(0f, 0f, width, height, alpha)
                true
            }
            // The content may be drawn ahead of the frame (see FramePlan).
            Composition.Offscreen ->
                canvas.saveLayer(0f, 0f, width, height, paint, displayList, DisplayList.WholeList)
        }
        if (drawsContent && !displayList.isEmpty) {
            displayList.replay(canvas)
        }
        if (composition == Composition.ClippedAlpha || composition == Composition.Offscreen) {
            canvas.restore()
        }
    }

    /**
     * Draws the layer with a perspective transformation (rotations around the x or y axis), which
     * needs WebGL. Returns `false` if it isn't available: the layer is then drawn with the affine
     * part of its transformation.
     */
    private fun drawInPerspective(canvas: WebCanvas): Boolean {
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
                    // A new shape in every frame: no image is worth making.
                    shape = ShadowShape.Path(projected, key = null, spriteRequests = null),
                    elevation = shadowElevation * scale,
                    ambientArgb = ambientShadowArgb,
                    spotArgb = spotShadowArgb,
                    alpha = alpha,
                    lighting = lighting,
                )
                canvas.restore()
            }
        }
        updateComposition()
        return canvas.drawInPerspective(
            transform = transform,
            left = -outsetLeft.toFloat(),
            top = -outsetTop.toFloat(),
            right = (size.width + outsetRight).toFloat(),
            bottom = (size.height + outsetBottom).toFloat(),
            blendMode = blendMode,
            content = projectedContent,
        )
    }

    /**
     * The content of the layer drawn in perspective, from the space of the layer (see
     * [drawInPerspective]).
     */
    private val projectedContent = object : JobContent {
        override fun replayContent(canvas: WebCanvas, contentOp: Int) {
            val displayList = displayList ?: return
            drawContent(canvas, displayList, projectedComposition, projectedLayerPaint)
        }
    }

    private fun clipTo(canvas: WebCanvas, outline: Outline) {
        when (outline) {
            is Outline.Rectangle -> canvas.clipRect(
                outline.rect.left,
                outline.rect.top,
                outline.rect.right,
                outline.rect.bottom,
                ClipOp.Intersect,
            )
            else -> canvas.clipPathData(outlinePath(outline), ClipOp.Intersect)
        }
    }

    /**
     * The outline as a shape casting shadows, in the space of the layer: a rectangle or a rounded
     * rectangle with the same circular corners, whose shadows are drawn without blurring, or else
     * its path, identified up to a translation for the images of its shadows (see
     * [ShadowSprites]).
     */
    private fun shadowShape(outline: Outline): ShadowShape {
        cachedShadowShape?.let { return it }
        val path = outlinePath(outline)
        val requests = shadowRequests ?: ShadowSprites.Requests().also { shadowRequests = it }
        val shape = when (outline) {
            is Outline.Rectangle -> outline.rect.let {
                ShadowShape.RoundRect(
                    it.left, it.top, it.right, it.bottom,
                    radius = 0f,
                    path,
                    key = it.size,
                    requests,
                )
            }
            is Outline.Rounded -> outline.roundRect.let {
                val key = it.translate(Offset(-it.left, -it.top))
                if (it.isSimple) {
                    ShadowShape.RoundRect(
                        it.left, it.top, it.right, it.bottom,
                        radius = it.topLeftCornerRadius.x,
                        path,
                        key,
                        requests,
                    )
                } else {
                    ShadowShape.Path(path, key, requests)
                }
            }
            is Outline.Generic -> ShadowShape.Path(path, key = PathShapeKey(path), requests)
        }
        cachedShadowShape = shape
        return shape
    }

    /** The outline as a path, in the space of the layer: the path of a generic outline itself. */
    private fun outlinePath(outline: Outline): PathData {
        outlinePath?.let { return it }
        val data = when (outline) {
            is Outline.Rectangle -> WebPath().apply { addRect(outline.rect) }.snapshot()
            is Outline.Rounded -> WebPath().apply { addRoundRect(outline.roundRect) }.snapshot()
            is Outline.Generic -> outline.path.asWebPath().snapshot()
        }
        return data.also { outlinePath = it }
    }

    private fun updateTransform() {
        if (!transformDirty) return
        transformDirty = false
        val pivotX: Float
        val pivotY: Float
        if (pivotOffset.isUnspecified) {
            pivotX = size.width / 2f
            pivotY = size.height / 2f
        } else {
            pivotX = pivotOffset.x
            pivotY = pivotOffset.y
        }
        prepareTransformationMatrix(
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

    /** Updates how the content is composited, and the paints doing it, after properties changed. */
    private fun updateComposition() {
        if (!compositionDirty) return
        compositionDirty = false
        composition = compositionWith(blendMode)
        // The blend mode applies when compositing the projected image.
        projectedComposition = compositionWith(BlendMode.SrcOver)
        val effect = renderEffect?.webRenderEffect
        layerPaint.alpha = alpha
        layerPaint.colorFilter = colorFilter
        layerPaint.blendMode = blendMode
        layerPaint.renderEffect = effect
        projectedLayerPaint.alpha = alpha
        projectedLayerPaint.colorFilter = colorFilter
        projectedLayerPaint.renderEffect = effect
    }

    private fun compositionWith(blendMode: BlendMode): Composition =
        when {
            colorFilter != null || renderEffect != null || blendMode != BlendMode.SrcOver ||
                compositingStrategy == CompositingStrategy.Offscreen -> Composition.Offscreen
            alpha >= 1f -> Composition.Direct
            compositingStrategy == CompositingStrategy.ModulateAlpha -> Composition.ModulateAlpha
            displayList?.canModulateAlpha == true -> Composition.ClippedAlpha
            else -> Composition.Offscreen
        }

    /** How the content of a layer is composited. */
    private enum class Composition {
        /** Drawn as it is. */
        Direct,

        /** Drawn with the alpha of the layer (`CompositingStrategy.ModulateAlpha`). */
        ModulateAlpha,

        /**
         * Drawn with the alpha of the layer and clipped to it, like through an offscreen buffer,
         * which gives the same pixels for content that covers nothing of itself (see
         * [WebCanvas.saveAlphaLayer]).
         */
        ClippedAlpha,

        /** Drawn into an offscreen buffer, composited with the layer paint. */
        Offscreen,
    }
}
