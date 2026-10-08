package androidx.dynamicanimation.animation

import android.os.SystemClock

/**
 * The main thread's spring frame loop, for tests (TD-200). It lives in this
 * package because [AnimationHandler] and a spring's target are package-private.
 *
 * A running spring sits in the thread's [AnimationHandler] until a frame ends
 * it. In a JVM test frames run only while the test idles the main looper, so
 * a spring still moving when a test ends keeps its target (a progress bar's
 * drawable, and through it the Activity) for the rest of the run. Its handler
 * then waits on a frame the next test's looper reset dropped and never asks
 * for another, so every later spring on the thread stays too.
 *
 * [step] runs frames directly, without idling anything else; [endAll] is for
 * the `@After` of a test that moves a bar it does not idle after.
 */
object SpringFrames {

    private const val FRAME_MS = 16L

    /** Whether a spring animating [target] is registered with this thread's handler. */
    fun running(target: Any): Boolean =
        AnimationHandler.getInstance().mAnimationCallbacks.any { (it as? DynamicAnimation<*>)?.mTarget === target }

    /** Whether this thread's handler holds no spring, not even an ended one's slot. */
    val idle: Boolean get() = AnimationHandler.getInstance().mAnimationCallbacks.isEmpty()

    /**
     * Runs [frames] animation frames now, one frame time apart. A spring takes
     * its first frame to start its clock, so one asked to end needs two.
     */
    fun step(frames: Int = 2) {
        val start = SystemClock.uptimeMillis()
        repeat(frames) { AnimationHandler.getInstance().doAnimationFrame(start + it * FRAME_MS) }
    }

    /**
     * Cancels every spring on this thread's handler, then runs one frame so
     * the handler drops their slots and the next spring to start asks for a
     * frame again.
     */
    fun endAll() {
        val handler = AnimationHandler.getInstance()
        handler.mAnimationCallbacks.filterIsInstance<DynamicAnimation<*>>().forEach { it.cancel() }
        handler.doAnimationFrame(SystemClock.uptimeMillis())
    }
}
