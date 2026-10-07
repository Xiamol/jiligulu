package com.jiligulu.app.ui.capture

/** Coordinates and filtering guards shared by the shader uniforms and independent JVM checks. */
internal object GlassLensSamplingGeometry {
    const val IMAGE_BORDER = 1f
    const val FAR_IMAGE_BORDER = 2f
    const val EXCLUSION_GUARD = 1f
    const val RECONSTRUCTION_MARGIN = 2f

    // scaleX/Y are captured pixels per screen pixel. The ROI stays in capture coordinates.
    fun sourceX(region: GlassSampleRegion, localX: Float) =
        (region.bubble.left + localX) * region.scaleX - region.roi.left
    fun sourceY(region: GlassSampleRegion, localY: Float) =
        (region.bubble.top + localY) * region.scaleY - region.roi.top

    fun excludedLeft(region: GlassSampleRegion) = (region.excluded.left - region.roi.left).toFloat()
    fun excludedTop(region: GlassSampleRegion) = (region.excluded.top - region.roi.top).toFloat()
    fun excludedRight(region: GlassSampleRegion) = (region.excluded.right - region.roi.left).toFloat()
    fun excludedBottom(region: GlassSampleRegion) = (region.excluded.bottom - region.roi.top).toFloat()

    /** Includes the additional bilinear footprint guard, beyond the worker's overlay guard. */
    fun isSafeSource(region: GlassSampleRegion, x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || x < IMAGE_BORDER || y < IMAGE_BORDER ||
            x > region.roi.width - FAR_IMAGE_BORDER || y > region.roi.height - FAR_IMAGE_BORDER) return false
        return x < excludedLeft(region) - EXCLUSION_GUARD || x > excludedRight(region) + EXCLUSION_GUARD ||
            y < excludedTop(region) - EXCLUSION_GUARD || y > excludedBottom(region) + EXCLUSION_GUARD
    }
}
