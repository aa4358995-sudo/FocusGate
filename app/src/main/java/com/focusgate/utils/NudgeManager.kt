package com.focusgate.utils

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.TextView
import com.focusgate.R

/**
 * NudgeManager handles creating, displaying, and dismissing
 * the 3-level nudge overlay system.
 *
 * All overlays use SYSTEM_ALERT_WINDOW permission.
 */
class NudgeManager(private val context: Context) {

    companion object {
        private const val TAG = "NudgeManager"
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var level1View: View? = null
    private var level2View: View? = null
    private var grayOverlayView: View? = null

    private fun buildOverlayParams(
        gravity: Int = Gravity.CENTER,
        touchable: Boolean = true,
        fullscreen: Boolean = false
    ): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        return WindowManager.LayoutParams(
            if (fullscreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            if (fullscreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
        }
    }

    // ─── Level 1: Visual Warning Banner ──────────────────────────────────────

    fun showLevel1Warning(originalIntent: String, currentApp: String, aiSuggestion: String) {
        dismissLevel1()
        Log.d(TAG, "Showing Level 1 nudge")

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_nudge_level1, null)

        view.findViewById<TextView>(R.id.tv_original_intent)?.text =
            "You said: \"$originalIntent\""
        view.findViewById<TextView>(R.id.tv_ai_message)?.text =
            if (aiSuggestion.isNotBlank()) aiSuggestion
            else "Heads up — $currentApp doesn't match your intent."
        view.findViewById<TextView>(R.id.btn_dismiss)?.setOnClickListener {
            dismissLevel1()
        }
        view.findViewById<TextView>(R.id.btn_update_intent)?.setOnClickListener {
            dismissAll()
            // TODO: launch intent gate to update intent
        }

        level1View = view

        // Auto-dismiss after 8 seconds
        view.postDelayed({ dismissLevel1() }, 8000)

        try {
            val params = buildOverlayParams(gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show Level 1 nudge: ${e.message}")
        }
    }

    fun dismissLevel1() {
        level1View?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { /* already removed */ }
            level1View = null
        }
    }

    // ─── Level 2: Warning + Gray Overlay ─────────────────────────────────────

    fun showLevel2Warning(originalIntent: String, currentApp: String) {
        dismissLevel2()
        Log.d(TAG, "Showing Level 2 nudge")

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_nudge_level2, null)

        view.findViewById<TextView>(R.id.tv_message)?.text =
            "This is your second reminder.\nYou intended to: \"$originalIntent\"\nYour screen is going gray as a gentle reminder."
        view.findViewById<TextView>(R.id.btn_acknowledge)?.setOnClickListener {
            dismissLevel2()
        }

        level2View = view

        try {
            val params = buildOverlayParams(gravity = Gravity.CENTER)
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show Level 2 nudge: ${e.message}")
        }
    }

    fun dismissLevel2() {
        level2View?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { /* already removed */ }
            level2View = null
        }
    }

    // ─── Grayscale Overlay ────────────────────────────────────────────────────

    fun applyGrayOverlay() {
        if (grayOverlayView != null) return

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_grayscale, null)
        grayOverlayView = view

        try {
            val params = buildOverlayParams(touchable = false, fullscreen = true)
            windowManager.addView(view, params)
            Log.d(TAG, "Gray overlay applied")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply gray overlay: ${e.message}")
            grayOverlayView = null
        }
    }

    fun removeGrayOverlay() {
        grayOverlayView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { /* already removed */ }
            grayOverlayView = null
            Log.d(TAG, "Gray overlay removed")
        }
    }

    // ─── Dismiss All ──────────────────────────────────────────────────────────

    fun dismissAll() {
        dismissLevel1()
        dismissLevel2()
        removeGrayOverlay()
    }
}
