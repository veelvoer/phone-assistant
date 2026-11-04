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

/** The user's overlay choices (Settings → Appearance). */
class OverlayStyle(
    val glow: Boolean = true, val glowWidth: Int = 2, val glowIntensity: Int = 80, val glowSpeed: Int = 2,
    val top: Boolean = false, val size: Int = 1, val alpha: Int = 94, val radius: Int = 28,
    val bars: Boolean = true, val progress: Boolean = true, val accent: Int = 0, val holdSec: Float = 4.5f,
) {
    companion object {
        fun load(c: Context): OverlayStyle {
            val p = c.getSharedPreferences("p", Context.MODE_PRIVATE)
            return OverlayStyle(p.getBoolean("ov_glow", true), p.getInt("ov_gw", 2), p.getInt("ov_gi", 80), p.getInt("ov_gs", 2),
                p.getString("ov_pos", "bottom") == "top", p.getInt("ov_size", 1), p.getInt("ov_alpha", 94), p.getInt("ov_radius", 28),
                p.getBoolean("ov_bars", true), p.getBoolean("ov_prog", true), p.getInt("ov_accent", 0), p.getFloat("ov_hold", 4.5f))
        }
    }
}

/** Gemini-style assistant overlay: a glowing gradient edge around the screen plus a glass card at the bottom. */
class Overlay(private val ctx: Context, private val onStop: () -> Unit) {
    enum class State { LISTENING, WORKING, ASKING, INFO }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private fun dpi(v: Float) = (v * d).toInt()

    private val pal = Pal(ctx)
    private var st = OverlayStyle.load(ctx)
    private var colors = palette()

    /** Gradient for the glow: the user's accent (three shades) or the system colours. */
    private fun palette(): IntArray {
        if (st.accent == 0) return intArrayOf(pal.a400, pal.a200, pal.a600, pal.a300, pal.a400)
        val hsv = FloatArray(3); Color.colorToHSV(st.accent, hsv)
        fun shade(dh: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf((hsv[0] + dh + 360f) % 360f, (hsv[1] * s).coerceIn(0f, 1f), (hsv[2] * v).coerceIn(0f, 1f)))
        val a = st.accent; val b = shade(18f, 0.85f, 1f); val c = shade(-18f, 0.9f, 0.9f); val d2 = shade(0f, 0.55f, 1f)
        return intArrayOf(a, b, c, d2, a)
    }

    /** Remove the views at once (used when a setting changed) so the next update() builds them again with the new style. */
    fun rebuild() {
        val c = card; val g = glow; card = null; glow = null; shownState = null; lastText = ""
        c?.let { runCatching { wm.removeView(it) } }; g?.let { runCatching { wm.removeView(it) }; it.stop() }
        st = OverlayStyle.load(ctx); colors = palette()
    }

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
        bar!!.visibility = if (progress > 0f && st.progress) View.VISIBLE else View.GONE; bar!!.setProgress(progress)
        wave!!.state = state
        glow!!.target = if (!st.glow) 0f else when (state) { State.LISTENING -> 1f; State.WORKING -> 0.9f; State.ASKING -> 1f; State.INFO -> 0f }
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
        st = OverlayStyle.load(ctx); colors = palette()
        val g = Glow(ctx).also { glow = it }
        wm.addView(g, WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT))

        val w = Wave(ctx).also { wave = it; it.visibility = if (st.bars) View.VISIBLE else View.GONE }
        val t1 = TextView(ctx).apply { textSize = 11f; setTextColor(pal.muted); letterSpacing = 0.06f; isAllCaps = true }.also { title = it }
        val t2 = TextView(ctx).apply { textSize = if (st.size == 2) 19f else 17f; setTextColor(pal.text); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.05f) }.also { text = it }
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
            background = GradientDrawable().apply { cornerRadius = dp(st.radius.toFloat()); setColor((pal.bg and 0x00FFFFFF) or ((st.alpha.coerceIn(30, 100) * 255 / 100) shl 24)); setStroke(dpi(1f), pal.stroke) }
            elevation = dp(10f)
            addView(row)
        }
        card = c
        wm.addView(c, WindowManager.LayoutParams(
            when (st.size) { 0 -> (ctx.resources.displayMetrics.widthPixels * 0.78f).toInt(); 2 -> ctx.resources.displayMetrics.widthPixels - dpi(12f); else -> ctx.resources.displayMetrics.widthPixels - dpi(28f) }, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT).apply { gravity = (if (st.top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL; y = if (st.top) dpi(52f) else dpi(26f) })
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
                angle = (angle + 2.2f * (when (st.glowSpeed) { 1 -> 0.5f; 3 -> 1.8f; else -> 1f })) % 360f
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
            val wm2 = when (st.glowWidth) { 1 -> 0.6f; 3 -> 1.5f; else -> 1f }; val inten = st.glowIntensity / 80f
            val layers = floatArrayOf(34f * wm2, 22f * wm2, 12f * wm2, 5f * wm2, 2.2f * wm2)
            val alphas = floatArrayOf(0.10f * inten, 0.18f * inten, 0.32f * inten, 0.6f * inten, 1f * inten)
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
