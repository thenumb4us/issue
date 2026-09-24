package com.issue.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

class SplashActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        val root = FrameLayout(this)

        val photo = ImageView(this).apply {
            setImageResource(R.drawable.issue_bg_splash)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(
            photo,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val shade = View(this).apply {
            setBackgroundColor(Color.argb(55, 0, 0, 0))
        }
        root.addView(
            shade,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val title = TextView(this).apply {
            text = "WELCOME BACK BBY"
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD))
            setShadowLayer(12f, 0f, 4f, Color.BLACK)
        }
        val titleParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            leftMargin = dp(24)
            rightMargin = dp(24)
            bottomMargin = dp(110)
        }
        root.addView(title, titleParams)
        setContentView(root)

        title.alpha = 0f
        title.translationY = dp(20).toFloat()
        title.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(450L)
            .start()

        handler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 2000L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
