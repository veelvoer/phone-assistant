package app.phoneassistant

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Whiteboard: the assistant's drawings (sanitised SVG slides with captions) in a card over the screen. */
class Board(private val ctx: Context) {
    class Frame(val svg: String, val caption: String)

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Ui(ctx)
    private var root: LinearLayout? = null
    private var web: WebView? = null
    private var frames: List<Frame> = emptyList()
    private var idx = 0
    private lateinit var cap: TextView
    private lateinit var count: TextView
    private lateinit var prev: View
    private lateinit var next: View

    fun show(title: String, f: List<Frame>) {
        hide()
        frames = f; idx = 0
        val c = ui.c
        val w = WebView(ctx).apply {
            settings.javaScriptEnabled = false; settings.allowFileAccess = false; settings.allowContentAccess = false; settings.blockNetworkLoads = true
            setBackgroundColor(0xFFFBFBFD.toInt()); isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(10); bottomMargin = ui.dp(6) }
        }
        web = w
        cap = ui.tv("", 16f, c.onSurface).apply { gravity = Gravity.CENTER; minLines = 2; layoutParams = ui.lp(top = 2) }
        count = ui.tv("", 13f, c.onSurfaceV).apply { gravity = Gravity.CENTER; minWidth = ui.dp(56) }
        prev = ui.iconButton(R.drawable.ic_back, c.primaryC, c.onPrimaryC, 44) { go(idx - 1) }
        next = ui.iconButton(R.drawable.ic_back, c.primaryC, c.onPrimaryC, 44) { go(idx + 1) }.apply { rotation = 180f }
        val nav = LinearLayout(ctx).apply { gravity = Gravity.CENTER; visibility = if (f.size > 1) View.VISIBLE else View.GONE; layoutParams = ui.lp(top = 4); addView(prev); addView(count); addView(next) }
        val head = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.tv(title, 19f, c.onSurface, true).apply { layoutParams = ui.lp(0, -2, weight = 1f); maxLines = 2 })
            addView(ui.iconButton(R.drawable.ic_close, c.surface2, c.onSurface, 40) { hide() })
        }
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(14))
            background = ui.shape(c.bg, 28, c.outline); elevation = ui.dpf(12f)
            addView(head); addView(w); addView(cap); addView(nav)
        }
        root = r
        val dm = ctx.resources.displayMetrics
        wm.addView(r, WindowManager.LayoutParams((dm.widthPixels * 0.94f).toInt(), (dm.heightPixels * 0.62f).toInt(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER })
        r.alpha = 0f; r.scaleX = 0.96f; r.scaleY = 0.96f; r.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).start()
        go(0)
    }

    private fun go(n: Int) {
        if (frames.isEmpty()) return
        idx = n.coerceIn(0, frames.size - 1)
        val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>html,body{margin:0;height:100%;background:#fbfbfd}svg{width:100%;height:100%;display:block}</style></head><body>${frames[idx].svg}</body></html>"
        web?.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        cap.text = frames[idx].caption; count.text = "${idx + 1} / ${frames.size}"
        prev.alpha = if (idx == 0) 0.3f else 1f; next.alpha = if (idx == frames.size - 1) 0.3f else 1f
    }

    fun hide() {
        val r = root ?: return
        root = null
        r.animate().alpha(0f).setDuration(160).withEndAction { runCatching { web?.destroy() }; runCatching { wm.removeView(r) } }.start()
    }
}
