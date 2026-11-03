package app.phoneassistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sin

/** Gemini-style assistant overlay: a glowing gradient edge around the screen plus a glass card at the bottom. */
class Overlay(private val ctx: Context, private val onStop: () -> Unit) {
    enum class State { LISTENING, WORKING, ASKING, INFO }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private fun dpi(v: Float) = (v * d).toInt()

    private val pal = Pal(ctx)
    private val colors = intArrayOf(pal.a400, pal.a200, pal.a600, pal.a300, pal.a400)

    private var glow: Glow? = null
    private var card: LinearLayout? = null
    private var wave: Wave? = null
    private var title: TextView? = null
    private var text: TextView? = null
    private var sub: TextView? = null
    private var bar: Bar? = null
    private var shownState: State? = null
    private var lastText = ""

    private var level = 0f
    private var smooth = 0f

    /** Let touches fall through the card (while the agent taps) or catch them again. */
    fun touchable(on: Boolean) {
        val c = card ?: return
        val lp = c.layoutParams as? WindowManager.LayoutParams ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(c, lp) }
    }

    fun level(rms: Float) { level = ((rms + 2f) / 12f).coerceIn(0f, 1f) }

    fun update(state: State, titleS: String, textS: String, subS: String?, progress: Float) {
        ensure()
        val c = card!!
        title!!.text = titleS
        if (textS != lastText) { text!!.alpha = 0.3f; text!!.text = textS; text!!.animate().alpha(1f).setDuration(160).start(); lastText = textS }
        sub!!.text = subS ?: ""; sub!!.visibility = if (subS.isNullOrEmpty()) View.GONE else View.VISIBLE
        bar!!.visibility = if (progress > 0f) View.VISIBLE else View.GONE; bar!!.setProgress(progress)
        wave!!.state = state
        glow!!.target = when (state) { State.LISTENING -> 1f; State.WORKING -> 0.9f; State.ASKING -> 1f; State.INFO -> 0f }
        if (shownState == null) {
            c.translationY = dp(60f); c.alpha = 0f
            c.animate().translationY(0f).alpha(1f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
        }
        shownState = state
    }

    fun hide() {
        val c = card ?: return
        val g = glow
        card = null; glow = null; shownState = null; lastText = ""
        g?.target = 0f
        c.animate().translationY(dp(60f)).alpha(0f).setDuration(200).withEndAction {
            runCatching { wm.removeView(c) }
            g?.let { runCatching { wm.removeView(it) } ; it.stop() }
        }.start()
    }

    private fun ensure() {
        if (card != null) return
        val g = Glow(ctx).also { glow = it }
        wm.addView(g, WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT))

        val w = Wave(ctx).also { wave = it }
        val t1 = TextView(ctx).apply { textSize = 11f; setTextColor(pal.muted); letterSpacing = 0.06f; isAllCaps = true }.also { title = it }
        val t2 = TextView(ctx).apply { textSize = 17f; setTextColor(pal.text); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.05f) }.also { text = it }
        val t3 = TextView(ctx).apply { textSize = 12f; setTextColor(pal.muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }.also { sub = it }
        val b = Bar(ctx).also { bar = it }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(t1); addView(t2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(2f) })
            addView(t3, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
            addView(b, LinearLayout.LayoutParams(-1, dpi(3f)).apply { topMargin = dpi(8f) })
        }
        val close = TextView(ctx).apply {
            this.text = "✕"; textSize = 15f; setTextColor(pal.text); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.chip) }
            contentDescription = "Stop"; setOnClickListener { onStop() }
        }
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(w, LinearLayout.LayoutParams(dpi(44f), dpi(44f)))
            addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpi(14f); rightMargin = dpi(10f) })
            addView(close, LinearLayout.LayoutParams(dpi(38f), dpi(38f)))
        }
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(18f), dpi(14f), dpi(14f), dpi(14f))
            background = GradientDrawable().apply { cornerRadius = dp(28f); setColor(pal.bg); setStroke(dpi(1f), pal.stroke) }
            elevation = dp(10f)
            addView(row)
        }
        card = c
        wm.addView(c, WindowManager.LayoutParams(
            ctx.resources.displayMetrics.widthPixels - dpi(28f), -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dpi(26f) })
        g.start()
    }

    // ---- glowing edge ----
    private inner class Glow(c: Context) : View(c) {
        var target = 0f
        private var a = 0f
        private var angle = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val m = Matrix()
        private var shader: SweepGradient? = null
        private val rect = RectF()
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener {
                angle = (angle + 2.2f) % 360f
                smooth += (level - smooth) * 0.25f
                a += (target - a) * 0.12f
                invalidate()
            }
        }
        fun start() { if (!anim.isStarted) anim.start() }
        fun stop() { anim.cancel() }

        override fun onDraw(cv: Canvas) {
            if (a < 0.01f) return
            val w = width.toFloat(); val h = height.toFloat()
            if (shader == null) shader = SweepGradient(w / 2, h / 2, colors, null)
            m.setRotate(angle, w / 2, h / 2); shader!!.setLocalMatrix(m); p.shader = shader
            val pulse = 0.75f + 0.25f * smooth + 0.1f * sin(angle / 28f)
            val r = dp(46f)
            val layers = floatArrayOf(34f, 22f, 12f, 5f, 2.2f)
            val alphas = floatArrayOf(0.10f, 0.18f, 0.32f, 0.6f, 1f)
            for (i in layers.indices) {
                p.strokeWidth = dp(layers[i]) * (0.85f + 0.3f * smooth)
                p.alpha = (255 * alphas[i] * a * pulse).toInt().coerceIn(0, 255)
                rect.set(0f, 0f, w, h)
                cv.drawRoundRect(rect, r, r, p)
            }
        }
    }

    // ---- animated voice bars ----
    private inner class Wave(c: Context) : View(c) {
        var state = State.INFO
        private var phase = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase += 0.16f; invalidate() }
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(colors[0], colors[1], colors[2], colors[3]), null, Shader.TileMode.CLAMP)
            val n = 4; val bw = w / (n * 2f); val gap = bw
            val amp = when (state) { State.LISTENING -> 0.25f + 0.75f * smooth; State.ASKING -> 0.3f + 0.6f * smooth; State.WORKING -> 0.4f; else -> 0.15f }
            for (i in 0 until n) {
                val k = 0.5f + 0.5f * sin(phase * (if (state == State.WORKING) 1.4f else 1f) + i * 1.3f)
                val bh = (h * (0.22f + 0.78f * amp * (0.35f + 0.65f * k))).coerceAtMost(h)
                val x = bw / 2 + i * (bw + gap)
                cv.drawRoundRect(x, (h - bh) / 2, x + bw, (h + bh) / 2, bw / 2, bw / 2, p)
            }
        }
    }

    private inner class Bar(c: Context) : View(c) {
        private var v = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun setProgress(x: Float) { v = x.coerceIn(0f, 1f); invalidate() }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.shader = null; p.color = pal.stroke; cv.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
            p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(colors[0], colors[1], colors[2]), null, Shader.TileMode.CLAMP)
            cv.drawRoundRect(0f, 0f, (w * v).coerceAtLeast(h), h, h / 2, h / 2, p)
        }
    }
}
