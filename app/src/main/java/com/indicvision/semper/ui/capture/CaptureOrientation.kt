package com.indicvision.semper.ui.capture

/**
 * Where "upright" is for a frame that arrives in the sensor's own orientation.
 *
 * Camera2 hands back buffers laid out the way the sensor is physically
 * mounted, which on most phones is a quarter turn away from how the device is
 * held. A JPEG carries that difference in an EXIF tag and every viewer applies
 * it; the raw YUV the locked session captures carries nothing, so the frames
 * were being written sideways. Nothing downstream can recover the intent from
 * the pixels — the rotation has to be applied where the buffer is read.
 *
 * The same number orients the on-screen preview, and that matters more than it
 * looks: the preview is what the user frames against, so it has to agree with
 * the file pixel for pixel. See [previewFitScale].
 *
 * Pure arithmetic — no Android types — so the awkward cases are unit-testable.
 */
internal object CaptureOrientation {

    const val ROTATE_90 = 90
    const val ROTATE_180 = 180
    const val ROTATE_270 = 270

    private const val QUARTER = 90
    private const val FULL_TURN = 360
    private const val DEGREES_PER_SURFACE_ROTATION = 90

    /**
     * Clockwise rotation that makes a sensor-oriented buffer upright for
     * someone looking at the screen.
     *
     * [displayRotationDegrees] is the display's own rotation away from the
     * device's natural orientation, so it has to be subtracted rather than
     * assumed zero: the capture screen is locked to portrait, and on a
     * naturally-landscape tablet that lock *is* a display rotation.
     */
    fun uprightRotation(
        sensorOrientation: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean = false,
    ): Int {
        val sensor = quarterTurn(sensorOrientation)
        val display = quarterTurn(displayRotationDegrees)
        // A front sensor faces the other way, so the display's rotation adds
        // to the sensor's instead of cancelling it.
        val degrees = if (frontFacing) sensor + display else sensor - display
        return wrap(degrees)
    }

    /** Degrees for a [android.view.Surface] ROTATION_* constant. */
    fun degreesForSurfaceRotation(surfaceRotation: Int): Int =
        wrap(surfaceRotation * DEGREES_PER_SURFACE_ROTATION)

    /**
     * Nearest quarter turn in 0..270. Sensor orientation is specified to be
     * one already, but rounding costs nothing and keeps a malformed value from
     * reaching the pixel loop, where it would mean a wrongly-sized output
     * buffer rather than a merely tilted image.
     */
    fun quarterTurn(degrees: Int): Int = wrap(wrap(degrees) + QUARTER / 2) / QUARTER * QUARTER % FULL_TURN

    /** True when [degrees] swaps width and height. */
    fun swapsAxes(degrees: Int): Boolean {
        val turn = quarterTurn(degrees)
        return turn == ROTATE_90 || turn == ROTATE_270
    }

    /**
     * Turn a normalised point clockwise by [degrees] inside the unit square.
     *
     * The one place the sensor's frame and the user's frame have to be
     * reconciled for a *point* rather than a whole buffer. Metering rectangles
     * are addressed in the sensor's active array, which is never rotated, while
     * everything the user picked — a focus tap on the preview, the speckle
     * check's strongest sample — is expressed against the upright picture. A
     * point handed across that boundary unturned lands a quarter turn away on
     * real pixels, so nothing fails and nothing is logged: the camera simply
     * focuses somewhere the user did not choose.
     *
     * [uprightRotation]'s answer takes a sensor point to an upright one; pass
     * its negation to go the other way. Anything that is not a quarter turn is
     * returned untouched, since there is no sensible partial answer.
     */
    fun rotatePoint(normX: Float, normY: Float, degrees: Int): Pair<Float, Float> =
        when (quarterTurn(degrees)) {
            ROTATE_90 -> (1f - normY) to normX
            ROTATE_180 -> (1f - normX) to (1f - normY)
            ROTATE_270 -> normY to (1f - normX)
            else -> normX to normY
        }

    /**
     * Largest scale at which the rotated buffer fits *entirely* inside a view
     * of [viewW] × [viewH].
     *
     * Fit, not fill. A preview that crops to fill the screen is the right
     * choice for a camera app and the wrong one here: the user is framing a
     * specimen against the edges of the frame, and anything the preview hides
     * is something they will only discover is missing after the run. Letterbox
     * bars are the honest answer.
     */
    fun previewFitScale(viewW: Int, viewH: Int, bufW: Int, bufH: Int, rotationDegrees: Int): Float {
        if (minOf(viewW, viewH, bufW, bufH) <= 0) return 1f
        val contentW = if (swapsAxes(rotationDegrees)) bufH else bufW
        val contentH = if (swapsAxes(rotationDegrees)) bufW else bufH
        return minOf(viewW.toFloat() / contentW, viewH.toFloat() / contentH)
    }

    private fun wrap(degrees: Int): Int = ((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN
}
