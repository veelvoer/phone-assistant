package app.phoneassistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.sin

/** Everything the user can change about the overlay (Settings → Appearance). Stored in the "p" preferences. */
class OverlayStyle(
    val glow: Boolean = true, val glowWidth: Int = 2, val glowIntensity: Int = 80, val glowSpeed: Int = 2, val glowMode: Int = 0, val glowRadius: Int = 46, val glowSoft: Int = 50,
    val accent: Int = 0, val accent2: Int = 0,
    val card: Boolean = true, val top: Boolean = false, val size: Int = 1, val alpha: Int = 94, val radius: Int = 28, val textSize: Int = 17, val offset: Int = 26, val cardStyle: Int = 0, val showTitle: Boolean = true,
    val bars: Boolean = true, val waveStyle: Int = 0, val waveCount: Int = 4, val progress: Boolean = true, val anim: Int = 0, val holdSec: Float = 4.5f,
) {
    companion object {
        fun load(c: Context): OverlayStyle {
            val p = c.getSharedPreferences("p", Context.MODE_PRIVATE)
            return OverlayStyle(
                p.getBoolean("ov_glow", true), p.getInt("ov_gw", 2), p.getInt("ov_gi", 80), p.getInt("ov_gs", 2), p.getInt("ov_gm", 0), p.getInt("ov_gr", 46), p.getInt("ov_gb", 50),
                p.getInt("ov_accent", 0), p.getInt("ov_accent2", 0),
                p.getBoolean("ov_card", true), p.getString("ov_pos", "bottom") == "top", p.getInt("ov_size", 1), p.getInt("ov_alpha", 94), p.getInt("ov_radius", 28), p.getInt("ov_text", 17), p.getInt("ov_off", 26), p.getInt("ov_cs", 0), p.getBoolean("ov_title", true),
                p.getBoolean("ov_bars", true), p.getInt("ov_ws", 0), p.getInt("ov_wc", 4), p.getBoolean("ov_prog", true), p.getInt("ov_anim", 0), p.getFloat("ov_hold", 4.5f))
        }

        /** One-tap looks. */
        fun applyPreset(c: Context, name: String) {
            val e = c.getSharedPreferences("p", Context.MODE_PRIVATE).edit()
            e.putInt("ov_gm", 0).putInt("ov_gr", 46).putInt("ov_gb", 50).putInt("ov_accent2", 0).putBoolean("ov_card", true).putInt("ov_off", 26).putInt("ov_text", 17).putInt("ov_ws", 0).putInt("ov_wc", 4).putInt("ov_anim", 0).putBoolean("ov_title", true)
            when (name) {
                "classic" -> e.putBoolean("ov_glow", true).putInt("ov_gw", 2).putInt("ov_gi", 80).putInt("ov_gs", 2).putInt("ov_accent", 0).putInt("ov_cs", 0).putInt("ov_alpha", 94).putInt("ov_radius", 28)
                "minimal" -> e.putBoolean("ov_glow", false).putInt("ov_cs", 1).putInt("ov_alpha", 70).putInt("ov_radius", 20).putInt("ov_size", 0).putBoolean("ov_bars", false).putBoolean("ov_prog", false).putInt("ov_anim", 1)
                "neon" -> e.putBoolean("ov_glow", true).putInt("ov_gw", 3).putInt("ov_gi", 130).putInt("ov_gs", 3).putInt("ov_gb", 30).putInt("ov_accent", 0xFF00E5FF.toInt()).putInt("ov_accent2", 0xFFFF2BD6.toInt()).putInt("ov_cs", 2).putInt("ov_alpha", 88).putInt("ov_radius", 22).putInt("ov_ws", 2)
                "calm" -> e.putBoolean("ov_glow", true).putInt("ov_gw", 1).putInt("ov_gi", 50).putInt("ov_gs", 1).putInt("ov_gb", 90).putInt("ov_accent", 0xFF8FB8DE.toInt()).putInt("ov_cs", 0).putInt("ov_alpha", 85).putInt("ov_radius", 34).putInt("ov_anim", 1).putInt("ov_ws", 1)
                "glass" -> e.putBoolean("ov_glow", true).putInt("ov_gw", 1).putInt("ov_gi", 60).putInt("ov_cs", 0).putInt("ov_alpha", 55).putInt("ov_radius", 36).putInt("ov_accent", 0)
                "contrast" -> e.putBoolean("ov_glow", true).putInt("ov_gw", 3).putInt("ov_gi", 110).putInt("ov_accent", 0xFFFFD600.toInt()).putInt("ov_cs", 0).putInt("ov_alpha", 100).putInt("ov_radius", 14).putInt("ov_text", 20)
                "bottom" -> e.putBoolean("ov_glow", true).putInt("ov_gm", 1).putInt("ov_gw", 2).putInt("ov_gi", 90).putInt("ov_accent", 0).putInt("ov_cs", 0).putInt("ov_alpha", 94).putInt("ov_radius", 28)
            }
            e.apply()
        }

        fun reset(c: Context) {
            val e = c.getSharedPreferences("p", Context.MODE_PRIVATE).edit()
            listOf("ov_glow", "ov_gw", "ov_gi", "ov_gs", "ov_gm", "ov_gr", "ov_gb", "ov_accent", "ov_accent2", "ov_card", "ov_pos", "ov_size", "ov_alpha", "ov_radius", "ov_text", "ov_off", "ov_cs", "ov_title", "ov_bars", "ov_ws", "ov_wc", "ov_prog", "ov_anim", "ov_hold", "ov_gw_i", "ov_gs_i", "ov_pos_i").forEach { e.remove(it) }
            e.apply()
        }
    }
}

/** Gemini-style assistant overlay: a glowing gradient edge around the whole screen plus a card. */
class Overlay(private val ctx: Context, private val onStop: () -> Unit) {
    enum class State { LISTENING, WORKING, ASKING, INFO }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private fun dpi(v: Float) = (v * d).toInt()

    private val pal = Pal(ctx)
    private var st = OverlayStyle.load(ctx)
    private var colors = palette()

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

    /** Gradient for the glow: system colours, one accent (three shades) or two chosen colours. */
    private fun palette(): IntArray {
        if (st.accent == 0) return intArrayOf(pal.a400, pal.a200, pal.a600, pal.a300, pal.a400)
        if (st.accent2 != 0) return intArrayOf(st.accent, st.accent2, st.accent, st.accent2, st.accent)
        val hsv = FloatArray(3); Color.colorToHSV(st.accent, hsv)
        fun shade(dh: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf((hsv[0] + dh + 360f) % 360f, (hsv[1] * s).coerceIn(0f, 1f), (hsv[2] * v).coerceIn(0f, 1f)))
        val a = st.accent
        return intArrayOf(a, shade(18f, 0.85f, 1f), shade(-18f, 0.9f, 0.9f), shade(0f, 0.55f, 1f), a)
    }

    private fun accentMain() = if (st.accent != 0) st.accent else pal.accent

    /** Real full display size (including the status bar, navigation bar and camera cut-out). */
    private fun fullLp(flags: Int) = WindowManager.LayoutParams(
        wm.maximumWindowMetrics.bounds.width(), wm.maximumWindowMetrics.bounds.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0; layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }

    private fun statusBarHeight(): Int { val r = ctx.resources.getIdentifier("status_bar_height", "dimen", "android"); return if (r > 0) ctx.resources.getDimensionPixelSize(r) else dpi(28f) }

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
        val c = card
        glow!!.target = if (!st.glow) 0f else when (state) { State.LISTENING -> 1f; State.WORKING -> 0.9f; State.ASKING -> 1f; State.INFO -> 0f }
        wave?.state = state
        if (c != null) {
            title!!.text = titleS
            if (textS != lastText) { text!!.alpha = 0.3f; text!!.text = textS; text!!.animate().alpha(1f).setDuration(160).start(); lastText = textS }
            sub!!.text = subS ?: ""; sub!!.visibility = if (subS.isNullOrEmpty()) View.GONE else View.VISIBLE
            bar!!.visibility = if (progress > 0f && st.progress) View.VISIBLE else View.GONE; bar!!.setProgress(progress)
            if (shownState == null) enter(c)
        }
        shownState = state
    }

    private fun enter(c: View) {
        when (st.anim) {
            1 -> { c.alpha = 0f; c.animate().alpha(1f).setDuration(260).start() }
            2 -> { c.alpha = 0f; c.scaleX = 0.82f; c.scaleY = 0.82f; c.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(1.4f)).start() }
            else -> { c.translationY = (if (st.top) -dp(60f) else dp(60f)); c.alpha = 0f; c.animate().translationY(0f).alpha(1f).setDuration(280).setInterpolator(DecelerateInterpolator()).start() }
        }
    }

    /** Remove the views at once (used when a setting changed) so the next update() builds them again with the new style. */
    fun rebuild() {
        val c = card; val g = glow; card = null; glow = null; shownState = null; lastText = ""
        c?.let { runCatching { wm.removeView(it) } }; g?.let { runCatching { wm.removeView(it) }; it.stop() }
        st = OverlayStyle.load(ctx); colors = palette()
    }

    fun hide() {
        val c = card; val g = glow
        card = null; glow = null; shownState = null; lastText = ""
        g?.target = 0f
        val done = { c?.let { runCatching { wm.removeView(it) } }; g?.let { runCatching { wm.removeView(it) }; it.stop() }; Unit }
        if (c == null) { done(); return }
        c.animate().alpha(0f).translationY(if (st.top) -dp(40f) else dp(40f)).setDuration(200).withEndAction { done() }.start()
    }

    private fun ensure() {
        if (glow != null) return
        st = OverlayStyle.load(ctx); colors = palette()
        val g = Glow(ctx).also { glow = it }
        wm.addView(g, fullLp(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS))
        g.start()
        if (!st.card) return

        val w = Wave(ctx).also { wave = it; it.visibility = if (st.bars) View.VISIBLE else View.GONE }
        val t1 = TextView(ctx).apply { textSize = 11f; setTextColor(pal.muted); letterSpacing = 0.06f; isAllCaps = true; visibility = if (st.showTitle) View.VISIBLE else View.GONE }.also { title = it }
        val t2 = TextView(ctx).apply { textSize = st.textSize.toFloat(); setTextColor(pal.text); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.05f) }.also { text = it }
        val t3 = TextView(ctx).apply { textSize = (st.textSize - 5).coerceAtLeast(11).toFloat(); setTextColor(pal.muted); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }.also { sub = it }
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
            if (st.bars) addView(w, LinearLayout.LayoutParams(dpi(44f), dpi(44f)))
            addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = if (st.bars) dpi(14f) else 0; rightMargin = dpi(10f) })
            addView(close, LinearLayout.LayoutParams(dpi(38f), dpi(38f)))
        }
        val baseAlpha = st.alpha.coerceIn(30, 100)
        val bgColor = when (st.cardStyle) {
            1 -> (pal.bg and 0x00FFFFFF) or ((baseAlpha.coerceAtLeast(45) * 255 / 100) shl 24)
            2 -> blend(pal.bg, accentMain(), 0.2f).let { (it and 0x00FFFFFF) or ((baseAlpha * 255 / 100) shl 24) }
            else -> (pal.bg and 0x00FFFFFF) or ((baseAlpha * 255 / 100) shl 24)
        }
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(18f), dpi(14f), dpi(14f), dpi(14f))
            background = GradientDrawable().apply { cornerRadius = dp(st.radius.toFloat()); setColor(bgColor); if (st.cardStyle == 1) setStroke(dpi(2f), accentMain()) else setStroke(dpi(1f), pal.stroke) }
            elevation = dp(10f)
            addView(row)
        }
        card = c
        val sw = wm.maximumWindowMetrics.bounds.width()
        val width = when (st.size) { 0 -> (sw * 0.78f).toInt(); 2 -> sw - dpi(12f); else -> sw - dpi(28f) }
        wm.addView(c, WindowManager.LayoutParams(width, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT).apply {
            gravity = (if (st.top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            y = if (st.top) statusBarHeight() + dpi(st.offset.toFloat()) else dpi(st.offset.toFloat())
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        })
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun m(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(m(Color.red(a), Color.red(b)), m(Color.green(a), Color.green(b)), m(Color.blue(a), Color.blue(b)))
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
            when (st.glowMode) {
                1 -> { cv.save(); cv.clipRect(0f, h * 0.55f, w, h); layers(cv, w, h); cv.restore() }
                2 -> { cv.save(); cv.clipRect(0f, 0f, w, h * 0.45f); layers(cv, w, h); cv.restore() }
                3 -> { cv.save(); cv.clipRect(0f, 0f, w * 0.16f, h); layers(cv, w, h); cv.restore(); cv.save(); cv.clipRect(w * 0.84f, 0f, w, h); layers(cv, w, h); cv.restore() }
                else -> layers(cv, w, h)
            }
        }

        private fun layers(cv: Canvas, w: Float, h: Float) {
            val pulse = 0.75f + 0.25f * smooth + 0.1f * sin(angle / 28f)
            val r = dp(st.glowRadius.toFloat())
            val wm2 = when (st.glowWidth) { 1 -> 0.6f; 3 -> 1.5f; else -> 1f }; val inten = st.glowIntensity / 80f
            val soft = 0.5f + st.glowSoft / 100f   // 0.5 = crisp, 1.5 = very soft
            val layers = floatArrayOf(34f * wm2 * soft, 22f * wm2 * soft, 12f * wm2, 5f * wm2, 2.2f * wm2)
            val alphas = floatArrayOf(0.10f * inten, 0.18f * inten, 0.32f * inten, 0.6f * inten, 1f * inten)
            for (i in layers.indices) {
                p.strokeWidth = dp(layers[i]) * (0.85f + 0.3f * smooth)
                p.alpha = (255 * alphas[i] * a * pulse).toInt().coerceIn(0, 255)
                rect.set(0f, 0f, w, h)
                cv.drawRoundRect(rect, r, r, p)
            }
        }
    }

    // ---- animated voice indicator ----
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
            val amp = when (state) { State.LISTENING -> 0.25f + 0.75f * smooth; State.ASKING -> 0.3f + 0.6f * smooth; State.WORKING -> 0.4f; else -> 0.15f }
            val n = st.waveCount.coerceIn(3, 7)
            val speed = if (state == State.WORKING) 1.4f else 1f
            when (st.waveStyle) {
                1 -> {   // dots
                    val slot = w / n; val rad = slot * 0.28f
                    for (i in 0 until n) { val k = 0.5f + 0.5f * sin(phase * speed + i * 1.1f); cv.drawCircle(slot * (i + 0.5f), h / 2 - amp * k * h * 0.28f, rad * (0.7f + 0.6f * amp * k), p) }
                }
                2 -> {   // pulsing ring
                    val k = 0.5f + 0.5f * sin(phase * speed)
                    p.style = Paint.Style.STROKE; p.strokeWidth = dp(3f); cv.drawCircle(w / 2, h / 2, w * (0.22f + 0.2f * amp * k), p)
                    p.style = Paint.Style.FILL; cv.drawCircle(w / 2, h / 2, w * (0.09f + 0.07f * amp), p)
                }
                else -> {   // bars
                    val bw = w / (n * 2f); val gap = bw
                    for (i in 0 until n) {
                        val k = 0.5f + 0.5f * sin(phase * speed + i * 1.3f)
                        val bh = (h * (0.22f + 0.78f * amp * (0.35f + 0.65f * k))).coerceAtMost(h)
                        val x = bw / 2 + i * (bw + gap)
                        cv.drawRoundRect(x, (h - bh) / 2, x + bw, (h + bh) / 2, bw / 2, bw / 2, p)
                    }
                }
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
