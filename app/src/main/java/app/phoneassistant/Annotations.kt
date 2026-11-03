package app.phoneassistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/** Spotlight: dims the screen, outlines elements and shows a label, to point things out to the user. Touches pass through. */
class Annotations(private val ctx: Context) {
    class Item(val rect: RectF, val label: String)

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val pal = Pal(ctx)
    private val d = ctx.resources.displayMetrics.density
    private val h = Handler(Looper.getMainLooper())
    private var view: V? = null
    private val hideRun = Runnable { hide() }

    fun show(items: List<Item>, seconds: Int) {
        if (view == null) {
            val v = V(ctx)
            wm.addView(v, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT))
            view = v
        }
        view!!.items = items; view!!.alpha = 0f; view!!.animate().alpha(1f).setDuration(220).start(); view!!.invalidate()
        h.removeCallbacks(hideRun); h.postDelayed(hideRun, seconds.coerceIn(3, 60) * 1000L)
    }

    fun hide() {
        h.removeCallbacks(hideRun)
        val v = view ?: return
        view = null
        v.animate().alpha(0f).setDuration(180).withEndAction { v.stop(); runCatching { wm.removeView(v) } }.start()
    }

    private inner class V(c: Context) : View(c) {
        var items: List<Item> = emptyList()
        private var t = 0f
        private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f * d; color = pal.onContainer }
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { t += 0.09f; invalidate() }
            start()
        }
        fun stop() { anim.cancel() }

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val hh = height.toFloat()
            val sc = cv.saveLayer(0f, 0f, w, hh, null)
            cv.drawColor(0x80000000.toInt())
            val pad = 6 * d
            for (it in items) cv.drawRoundRect(RectF(it.rect).apply { inset(-pad, -pad) }, 16 * d, 16 * d, clear)
            cv.restoreToCount(sc)
            val pulse = 0.5f + 0.5f * sin(t)
            for (it in items) {
                val r = RectF(it.rect).apply { inset(-pad, -pad) }
                ring.color = pal.a300; ring.alpha = (60 + 60 * pulse).toInt(); ring.strokeWidth = 9 * d
                cv.drawRoundRect(r, 16 * d, 16 * d, ring)
                ring.color = pal.a200; ring.alpha = 255; ring.strokeWidth = 3 * d
                cv.drawRoundRect(r, 16 * d, 16 * d, ring)
                if (it.label.isNotBlank()) label(cv, it, r, w, hh)
            }
        }

        private fun label(cv: Canvas, it: Item, r: RectF, w: Float, hh: Float) {
            val maxW = (w * 0.72f).toInt()
            val lay = StaticLayout.Builder.obtain(it.label, 0, it.label.length, tp, maxW).setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(4).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            val bp = 12 * d; val bw = lay.width + 2 * bp; val bh = lay.height + 2 * bp
            val below = r.bottom + 12 * d + bh < hh - 150 * d
            val top = if (below) r.bottom + 12 * d else (r.top - 12 * d - bh).coerceAtLeast(40 * d)
            val left = (r.centerX() - bw / 2).coerceIn(12 * d, w - bw - 12 * d)
            fill.color = pal.container
            cv.drawRoundRect(left, top, left + bw, top + bh, 18 * d, 18 * d, fill)
            cv.save(); cv.translate(left + bp, top + bp); lay.draw(cv); cv.restore()
        }
    }
}
