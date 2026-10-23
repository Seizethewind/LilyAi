package com.seizethewind.lilyai.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button

/**
 * A small "Stop LilyAi" button drawn over other apps while LilyAi works. It needs the
 * "display over other apps" permission, and simply doesn't appear without it.
 */
class OverlayManager(
    private val context: Context,
    private val onStop: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun show() {
        handler.post {
            if (view != null || !canShow()) return@post
            try {
                val button = Button(context).apply {
                    text = "Stop LilyAi"
                    setOnClickListener { onStop() }
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = 24
                    y = 240
                }
                windowManager.addView(button, params)
                view = button
            } catch (e: Exception) {
                view = null
            }
        }
    }

    fun hide() {
        handler.post {
            view?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    // Already gone.
                }
            }
            view = null
        }
    }
}
