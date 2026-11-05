package app.phoneassistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * The assistant draws directly on your screen: the screen dims a little, the strokes appear one by one with a cursor
 * following the pen, then the caption fades in. A small bar lets you step through frames or close it.
 */
class DrawOverlay(private val ctx: Context) {
    class Frame(val svg: String, val caption: String)

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Ui(ctx)
    private val pal = Pal(ctx)
    private var canvasView: Canvas2? = null
    private var controls: LinearLayout? = null
    private var counter: TextView? = null
    private var frames: List<Frame> = emptyList()
    private var idx = 0
    private var auto = true
    private var title = ""
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val closeRun = Runnable { hide() }
    private val nextRun = Runnable { if (auto && idx < frames.size - 1) go(idx + 1) else scheduleClose() }

    private fun accent(): Int { val a = ctx.getSharedPreferences("p", Context.MODE_PRIVATE).getInt("ov_accent", 0); return if (a != 0) a else pal.a300 }
    private fun hold(): Long = (ctx.getSharedPreferences("p", Context.MODE_PRIVATE).getFloat("ov_hold", 4.5f) * 1000).toLong().coerceAtLeast(2500L)

    fun show(t: String, f: List<Frame>) {
        hide(); frames = f; idx = 0; auto = true; title = t
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val b = wm.maximumWindowMetrics.bounds
        val v = Canvas2(ctx).also { canvasView = it }
        wm.addView(v, WindowManager.LayoutParams(b.width(), b.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS })
        val c = ui.c
        val cnt = ui.tv("", 13f, c.onSurface, true).apply { gravity = Gravity.CENTER; minWidth = ui.dp(54) }; counter = cnt
        val bar = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6)); background = ui.shape((pal.bg and 0x00FFFFFF) or 0xE6000000.toInt(), 28, pal.stroke); elevation = ui.dpf(8f)
            if (f.size > 1) { addView(ui.iconButton(R.drawable.ic_back, c.surface2, c.onSurface, 38) { auto = false; go(idx - 1) }); addView(cnt); addView(ui.iconButton(R.drawable.ic_back, c.surface2, c.onSurface, 38) { auto = false; go(idx + 1) }.apply { rotation = 180f }) }
            addView(ui.iconButton(R.drawable.ic_close, c.primaryC, c.onPrimaryC, 38) { hide() }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = ui.dp(if (f.size > 1) 6 else 0) })
        }
        controls = bar
        wm.addView(bar, WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = ui.dp(34); layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS })
        bar.alpha = 0f; bar.animate().alpha(1f).setDuration(250).start()
        v.fadeIn()
        go(0)
    }

    private fun go(n: Int) {
        if (frames.isEmpty()) return
        handler.removeCallbacks(nextRun); handler.removeCallbacks(closeRun)
        idx = n.coerceIn(0, frames.size - 1)
        counter?.text = "${idx + 1} / ${frames.size}"
        val f = frames[idx]
        canvasView?.play(SvgScene(f.svg), if (idx == 0 && title.isNotBlank()) title else "", f.caption, accent()) { total ->
            // drawing finished: hold a moment, then continue
            handler.postDelayed(nextRun, hold() + f.caption.length * 45L)
        }
    }

    private fun scheduleClose() { handler.removeCallbacks(closeRun); handler.postDelayed(closeRun, hold() * 2) }

    fun hide() {
        handler.removeCallbacks(nextRun); handler.removeCallbacks(closeRun)
        val v = canvasView; val c = controls; canvasView = null; controls = null
        v?.let { it.stop(); it.animate().alpha(0f).setDuration(220).withEndAction { runCatching { wm.removeView(it) } }.start() }
        c?.let { it.animate().alpha(0f).setDuration(160).withEndAction { runCatching { wm.removeView(it) } }.start() }
    }

    // ------------------------------------------------------------------ the drawing surface
    private inner class Canvas2(c: Context) : View(c) {
        private var scene: SvgScene? = null
        private var heading = ""; private var caption = ""; private var acc = Color.WHITE
        private var t = 0L; private var onDone: ((Long) -> Unit)? = null; private var doneFired = false
        private var captionFrom = 0L
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); addUpdateListener { advance() } }
        private var last = 0L
        private val d = c.resources.displayMetrics.density
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textP = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val view = Matrix(); private val tmp = Path(); private val pm = PathMeasure(); private val pos = FloatArray(2); private val tan = FloatArray(2)
        private val scrim = Paint().apply { color = 0x66000000 }
        private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
        private val cursorLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d; strokeJoin = Paint.Join.ROUND }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private var scrimAlpha = 0f

        fun fadeIn() { scrimAlpha = 0f }

        fun play(s: SvgScene, head: String, cap: String, accent: Int, done: (Long) -> Unit) {
            scene = s; heading = head; caption = cap; acc = accent; t = 0; doneFired = false; onDone = done; captionFrom = s.totalMs
            cursorLine.color = accent
            last = System.nanoTime(); if (!anim.isStarted) anim.start()
        }

        fun stop() { anim.cancel() }

        private fun advance() {
            val now = System.nanoTime(); val dt = (now - last) / 1_000_000L; last = now
            t += dt; scrimAlpha = min(1f, scrimAlpha + dt / 300f)
            val s = scene
            if (s != null && !doneFired && t >= s.totalMs + 350) { doneFired = true; onDone?.invoke(s.totalMs) }
            invalidate()
        }

        override fun onDraw(cv: Canvas) { try { render(cv) } catch (e: Exception) { Bus.log("draw error: ${e.message}") } }

        private fun render(cv: Canvas) {
            val s = scene ?: return
            val w = width.toFloat(); val h = height.toFloat()
            scrim.alpha = (0x66 * scrimAlpha).toInt(); cv.drawRect(0f, 0f, w, h, scrim)
            // fit the viewBox into the screen: 92% width, centred a little above the middle
            val sc = min(w * 0.92f / s.vbW, h * 0.62f / s.vbH)
            view.reset(); view.postTranslate(-s.vbX, -s.vbY); view.postScale(sc, sc); view.postTranslate((w - s.vbW * sc) / 2f, h * 0.46f - s.vbH * sc / 2f)
            var headX = -1f; var headY = -1f
            cv.save(); cv.concat(view)
            for (item in s.shapes) {
                if (item is SvgScene.Shape) {
                    if (t < item.start) continue
                    val p = ((t - item.start).toFloat() / item.dur).coerceIn(0f, 1f)
                    val col = item.stroke ?: item.fill ?: Color.WHITE
                    val sw = max(item.strokeWidth, 2.2f / sc) // never thinner than ~2px on screen
                    // halo so the line stays readable on any background
                    stroke.strokeWidth = sw + 3.2f / sc; stroke.color = 0x99000000.toInt()
                    pm.setPath(item.path, false)
                    fun drawPart(paint: Paint, frac: Float) { var remaining = item.length * frac; val pm2 = PathMeasure(item.path, false)
                        do { val l = pm2.length; val take = min(l, remaining); if (take > 0f) { tmp.reset(); pm2.getSegment(0f, take, tmp, true); cv.drawPath(tmp, paint) }; remaining -= l; if (remaining <= 0f) break } while (pm2.nextContour()) }
                    if (item.fill != null && p >= 1f) { fillP.color = item.fill; fillP.alpha = (255 * min(1f, (t - item.start - item.dur) / 250f).coerceAtLeast(0f) * 0.9f).toInt(); cv.drawPath(item.path, fillP) }
                    drawPart(stroke, p)
                    stroke.strokeWidth = sw; stroke.color = col; drawPart(stroke, p)
                    if (item.arrow && p >= 1f) arrowHead(cv, item, col, sw)
                    if (p < 1f) { val tot = pm.length; pm.getPosTan(tot * p, pos, tan); headX = pos[0]; headY = pos[1] }
                    else if (headX < 0 && t < item.start + item.dur + 400) { pm.getPosTan(pm.length, pos, tan); headX = pos[0]; headY = pos[1] }
                } else if (item is SvgScene.Label) {
                    if (t < item.start) continue
                    val p = ((t - item.start).toFloat() / item.dur).coerceIn(0f, 1f)
                    textP.textSize = item.size; textP.color = item.color; textP.textAlign = item.align; textP.setShadowLayer(3f / sc, 0f, 1f / sc, 0xCC000000.toInt())
                    val n = (item.text.length * p).toInt().coerceAtLeast(1)
                    cv.drawText(item.text, 0, n, item.x, item.y, textP)
                    if (p < 1f) { val wd = textP.measureText(item.text, 0, n); headX = when (item.align) { Paint.Align.CENTER -> item.x - textP.measureText(item.text) / 2f + wd; Paint.Align.RIGHT -> item.x - textP.measureText(item.text) + wd; else -> item.x + wd }; headY = item.y - item.size * 0.3f }
                }
            }
            cv.restore()
            // heading + caption in screen space
            if (heading.isNotBlank() && t > 100) { textP.textSize = 22f * d; textP.color = Color.WHITE; textP.textAlign = Paint.Align.CENTER; textP.setShadowLayer(6f, 0f, 2f, 0xCC000000.toInt()); cv.drawText(heading, w / 2f, h * 0.46f - s.vbH * sc / 2f - 18f * d, textP) }
            if (caption.isNotBlank() && t > s.totalMs - 200) {
                val a = ((t - (s.totalMs - 200)) / 400f).coerceIn(0f, 1f)
                textP.textSize = 18f * d; textP.color = Color.WHITE; textP.alpha = (255 * a).toInt(); textP.textAlign = Paint.Align.LEFT; textP.setShadowLayer(6f, 0f, 2f, 0xCC000000.toInt())
                val lay = StaticLayout.Builder.obtain(caption, 0, caption.length, textP, (w * 0.84f).toInt()).setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(4).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
                cv.save(); cv.translate(w * 0.08f, h * 0.46f + s.vbH * sc / 2f + 22f * d); lay.draw(cv); cv.restore()
            }
            if (headX >= 0f) { val pt = floatArrayOf(headX, headY); view.mapPoints(pt); cursor(cv, pt[0], pt[1]) }
        }

        private fun arrowHead(cv: Canvas, item: SvgScene.Shape, col: Int, sw: Float) {
            val count = run { val q = PathMeasure(item.path, false); var n = 1; while (q.nextContour()) n++; n }
            val pmL = PathMeasure(item.path, false); repeat(count - 1) { pmL.nextContour() }
            val len = pmL.length; if (len < 4f) return
            pmL.getPosTan(len, pos, tan); val ex = pos[0]; val ey = pos[1]; val tx = tan[0]; val ty = tan[1]
            val sz = max(sw * 4.5f, 12f); val nx = -ty; val ny = tx
            tmp.reset(); tmp.moveTo(ex, ey); tmp.lineTo(ex - tx * sz + nx * sz * 0.5f, ey - ty * sz + ny * sz * 0.5f); tmp.lineTo(ex - tx * sz - nx * sz * 0.5f, ey - ty * sz - ny * sz * 0.5f); tmp.close()
            fillP.color = col; fillP.alpha = 255; cv.drawPath(tmp, fillP)
        }

        /** Mouse-pointer shaped pen with a soft glow; its tip sits on the head of the stroke. */
        private fun cursor(cv: Canvas, x: Float, y: Float) {
            glow.shader = RadialGradient(x, y, 16f * d, intArrayOf((acc and 0x00FFFFFF) or 0x88000000.toInt(), (acc and 0x00FFFFFF)), null, Shader.TileMode.CLAMP); cv.drawCircle(x, y, 16f * d, glow)
            val u = d * 1.15f
            tmp.reset(); tmp.moveTo(x, y); tmp.lineTo(x, y + 17f * u); tmp.lineTo(x + 4.4f * u, y + 13.2f * u); tmp.lineTo(x + 7.6f * u, y + 20.5f * u); tmp.lineTo(x + 10.2f * u, y + 19.2f * u); tmp.lineTo(x + 7.2f * u, y + 12.4f * u); tmp.lineTo(x + 12.4f * u, y + 12.2f * u); tmp.close()
            cursorFill.alpha = 255; cv.drawPath(tmp, cursorFill); cv.drawPath(tmp, cursorLine)
        }
    }
}
