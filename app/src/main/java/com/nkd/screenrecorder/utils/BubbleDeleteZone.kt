package com.nkd.screenrecorder.utils

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import com.nkd.screenrecorder.databinding.LayoutDeleteZoneBinding

/**
 * XRecorder-style drag-to-close target (with magnet), shared by every floating bubble —
 * SSViewService's screenshot button and toolbox bubble, and DrawingService's brush bubble — so
 * they all look and behave exactly the same.
 *
 * ONE full-screen, NOT-touchable window, added BEFORE the bubbles ([ensureWindow]) so it is
 * drawn BELOW them — the shrunken circle becomes a dark ring AROUND the bubble. Hidden (GONE)
 * except while a bubble is dragged; a GONE window is not shown, so it never blocks touches to
 * other apps. Main thread only.
 */
class BubbleDeleteZone(
    private val context: Context,
    private val windowManager: WindowManager,
    private val mainHandler: Handler
) {
    private companion object {
        const val TAG = "BubbleDeleteZone"
    }

    private val resources: Resources get() = context.resources

    private var zoneBinding: LayoutDeleteZoneBinding? = null
    private var zoneParams: WindowManager.LayoutParams? = null

    /** True while the target is showing (a bubble is being dragged). */
    var isVisible = false
        private set

    /** True while the dragged bubble is magnetized onto the circle (release = delete). */
    var isOver = false

    private val deleteMagnetRadiusDp = 90f
    private val deleteCircleSizeDp = 96f
    private var deleteMagnetAnimator: ValueAnimator? = null

    /** Adds the (hidden) delete-zone window if it doesn't exist yet. Call before adding a bubble. */
    fun ensureWindow() {
        if (zoneBinding != null) return
        try {
            val zone = LayoutDeleteZoneBinding.inflate(LayoutInflater.from(context), null, false)
            zoneParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Android 12+ blocks touches that pass through another app's window whose
                // opacity is above 0.8 — keep this full-screen pass-through window at 0.8.
                alpha = 0.8f
                // Cover the nav-bar area too (gradient reaches the very bottom)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            windowManager.addView(zone.root, zoneParams)
            zoneBinding = zone
        } catch (e: Exception) {
            Log.e(TAG, "Error adding delete zone window", e)
            zoneBinding = null
            zoneParams = null
        }
    }

    /** Removes the delete-zone window (no bubble left / service stop). */
    fun removeWindow() {
        deleteMagnetAnimator?.cancel()
        zoneBinding?.let {
            try {
                windowManager.removeView(it.root)
            } catch (e: Exception) {
                Log.w(TAG, "Delete zone already removed: ${e.message}")
            }
        }
        zoneBinding = null
        zoneParams = null
        isVisible = false
        isOver = false
    }

    /** Drag started: gradient (35% of screen) fades in, circle scales in with overshoot. */
    fun show() {
        if (isVisible) return
        ensureWindow()
        val zone = zoneBinding ?: return
        try {
            val dm = resources.displayMetrics
            val root = zone.root
            val screenH = if (root.height > 0) root.height else Resources.getSystem().displayMetrics.heightPixels
            val navInset = androidx.core.view.ViewCompat.getRootWindowInsets(root)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())?.bottom ?: 0

            (zone.deleteGradient.layoutParams as FrameLayout.LayoutParams).height = (screenH * 0.35f).toInt()
            // Circle centre ~120dp above the bottom, inside the nav-bar inset
            val circleSize = (deleteCircleSizeDp * dm.density).toInt()
            (zone.deleteCircle.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                navInset + (120f * dm.density).toInt() - circleSize / 2
            zone.deleteGradient.requestLayout()
            zone.deleteCircle.requestLayout()

            root.visibility = View.VISIBLE
            isVisible = true
            isOver = false

            zone.deleteGradient.animate().cancel()
            zone.deleteGradient.alpha = 0f
            zone.deleteGradient.animate().alpha(1f).setDuration(150).start()

            zone.deleteCircle.animate().cancel()
            zone.deleteCircle.scaleX = 0f
            zone.deleteCircle.scaleY = 0f
            zone.deleteCircle.alpha = 1f
            zone.ivDelete.alpha = 1f
            zone.deleteCircle.animate().scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(OvershootInterpolator(1.5f)).start()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing delete zone", e)
        }
    }

    /** Drag ended: gradient and circle fade out, window hidden again (kept for next drag). */
    fun hide() {
        deleteMagnetAnimator?.cancel()
        val zone = zoneBinding
        if (!isVisible || zone == null) {
            isVisible = false
            return
        }
        isVisible = false
        try {
            zone.deleteGradient.animate().cancel()
            zone.deleteCircle.animate().cancel()
            zone.deleteGradient.animate().alpha(0f).setDuration(150).start()
            zone.deleteCircle.animate().alpha(0f).setDuration(150).start()
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding delete zone", e)
        }
        // Handler, not withEndAction: an end action is skipped when the animation gets
        // cancelled, which left this full-screen window VISIBLE (but transparent) over other apps.
        mainHandler.postDelayed({ if (!isVisible) zone.root.visibility = View.GONE }, 150)
    }

    /** Centre of the delete circle (at full size) in ON-SCREEN coordinates, or null. */
    private fun deleteCircleCenterOnScreen(): Pair<Float, Float>? {
        val zone = zoneBinding ?: return null
        val circle = zone.deleteCircle
        if (circle.width == 0) return null
        val rootLoc = IntArray(2).also { zone.root.getLocationOnScreen(it) }
        // Layout position (unaffected by the circle's scale animation)
        return Pair(
            rootLoc[0] + circle.left + circle.width / 2f,
            rootLoc[1] + circle.top + circle.height / 2f
        )
    }

    /**
     * One drag step for a bubble window, with the delete-zone MAGNET.
     * [desiredX]/[desiredY] = where the finger wants the window (params coordinates);
     * [offsetX]/[offsetY] = on-screen position − params position (measured at drag start);
     * [bubbleSizePx] = the visible bubble size (for the ring). Returns true while magnetized.
     */
    fun dragWithMagnet(
        rootView: View,
        params: WindowManager.LayoutParams,
        desiredX: Int, desiredY: Int,
        offsetX: Int, offsetY: Int,
        bubbleSizePx: Int,
        hapticView: View?
    ): Boolean {
        val center = deleteCircleCenterOnScreen()
        val w = params.width
        val h = params.height
        val wasMagnetized = isOver

        val magnetized = if (center == null || !isVisible) false else {
            // Distance on SCREEN between the bubble centre (at the finger position) and the circle
            val bx = desiredX + offsetX + w / 2f
            val by = desiredY + offsetY + h / 2f
            val dist = kotlin.math.hypot(bx - center.first, by - center.second)
            dist <= deleteMagnetRadiusDp * resources.displayMetrics.density
        }

        if (magnetized && center != null) {
            if (!wasMagnetized) {
                // Enter: snap the bubble centre onto the circle centre (~120ms), ring + haptic
                val targetX = (center.first - offsetX - w / 2f).toInt()
                val targetY = (center.second - offsetY - h / 2f).toInt()
                val fromX = params.x
                val fromY = params.y
                deleteMagnetAnimator?.cancel()
                deleteMagnetAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 120
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener {
                        val p = it.animatedValue as Float
                        params.x = (fromX + (targetX - fromX) * p).toInt()
                        params.y = (fromY + (targetY - fromY) * p).toInt()
                        try {
                            windowManager.updateViewLayout(rootView, params)
                        } catch (_: Exception) {
                        }
                    }
                    start()
                }
                setDeleteCircleRing(bubbleSizePx, ring = true)
                hapticView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
            // While inside the radius the bubble stays on the circle centre
        } else {
            if (wasMagnetized) {
                // Exit: follow the finger again, circle grows back with ✕
                deleteMagnetAnimator?.cancel()
                setDeleteCircleRing(bubbleSizePx, ring = false)
            }
            params.x = desiredX
            params.y = desiredY
            try {
                windowManager.updateViewLayout(rootView, params)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating layout during drag", e)
            }
        }
        isOver = magnetized
        return magnetized
    }

    /** ring=true: circle shrinks to bubble size + 12dp (dark ring), ✕ fades out. */
    private fun setDeleteCircleRing(bubbleSizePx: Int, ring: Boolean) {
        val zone = zoneBinding ?: return
        val full = deleteCircleSizeDp * resources.displayMetrics.density
        val ringSize = bubbleSizePx + 12f * resources.displayMetrics.density
        val scale = if (ring) (ringSize / full).coerceAtMost(1f) else 1f
        zone.deleteCircle.animate().cancel()
        zone.deleteCircle.animate().scaleX(scale).scaleY(scale).setDuration(120)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        zone.ivDelete.animate().cancel()
        zone.ivDelete.animate().alpha(if (ring) 0f else 1f).setDuration(120).start()
    }

    /**
     * Release while magnetized — THE close sequence of the floating (toolbox) bubble, used by
     * every bubble so they all close the same way: hide the target, shrink + fade the WHOLE
     * visible bubble [bubble] (150ms), then [onRemoved]. removeView, service stop and any other
     * cleanup belong in [onRemoved], so they run only after the animation, never during it.
     */
    fun closeBubble(bubble: View, onRemoved: () -> Unit) {
        hide()
        isOver = false
        animateBubbleRemoval(bubble, onRemoved)
    }

    /**
     * ACTION_DOWN on an edge bubble that may be collapsed / parked past the edge: back to full
     * size [sizePx] at once — no grow animation the drag would have to wait for — centred where
     * it was drawn and fully on screen, so it sits under the finger and doesn't jump. Callers
     * cancel their own animators/timers and restore alpha.
     */
    fun restoreFullSizeOnScreen(rootView: View, params: WindowManager.LayoutParams, sizePx: Int) {
        val screenW = Resources.getSystem().displayMetrics.widthPixels
        val oldW = if (params.width > 0) params.width else rootView.width
        val oldH = if (params.height > 0) params.height else rootView.height
        // Without FLAG_LAYOUT_NO_LIMITS the window is drawn clamped inside the screen
        val drawnX = params.x.coerceIn(0, maxOf(0, screenW - oldW))
        params.x = (drawnX + oldW / 2 - sizePx / 2).coerceIn(0, maxOf(0, screenW - sizePx))
        params.y -= (sizePx - oldH) / 2
        params.width = sizePx
        params.height = sizePx
        try {
            windowManager.updateViewLayout(rootView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring bubble size", e)
        }
    }

    /** Release while magnetized: bubble shrinks + fades (150ms), then [onRemoved]. */
    fun animateBubbleRemoval(bubble: View, onRemoved: () -> Unit) {
        try {
            bubble.animate().cancel()
            bubble.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(150)
                .withEndAction { onRemoved() }.start()
        } catch (e: Exception) {
            onRemoved()
        }
    }

    /** On-screen position − params position of a bubble window (for the magnet). */
    fun screenOffsetOf(root: View, params: WindowManager.LayoutParams): Pair<Int, Int> {
        val loc = IntArray(2).also { root.getLocationOnScreen(it) }
        return Pair(loc[0] - params.x, loc[1] - params.y)
    }
}
