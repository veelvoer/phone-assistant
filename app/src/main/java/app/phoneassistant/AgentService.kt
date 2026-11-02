package app.phoneassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AgentService : AccessibilityService() {
    companion object { @Volatile var instance: AgentService? = null }

    private val main = Handler(Looper.getMainLooper())
    private val nodes = ArrayList<AccessibilityNodeInfo>()
    @Volatile private var running = false
    @Volatile private var stopFlag = false
    private var overlay: TextView? = null
    private var wantImage = false

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun stop() { stopFlag = true }

    fun start(task: String, key: String, model: String): Boolean {
        if (running) return false
        running = true; stopFlag = false
        showOverlay("Working… (tap to stop)")
        Bus.log("▶ $task")
        thread {
            try { loop(task, key, model) } catch (e: Exception) { Bus.log("Error: ${e.message}") }
            running = false; hideOverlay()
        }
        return true
    }

    // ---------- overlay ----------
    private fun showOverlay(t: String) = main.post {
        if (overlay == null) {
            val tv = TextView(this).apply {
                setBackgroundColor(0xCCB00020.toInt()); setTextColor(0xFFFFFFFF.toInt()); textSize = 12f
                setPadding(24, 12, 24, 12); setOnClickListener { stop() }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.END; y = 80 }
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(tv, lp)
            overlay = tv
        }
        overlay?.text = t
    }

    private fun hideOverlay() = main.post {
        overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        overlay = null
    }

    // ---------- agent loop ----------
    private class Turn(val assistant: JSONArray, val ids: List<String>, val briefs: List<String>, val obs: JSONArray)

    private fun loop(task: String, key: String, model: String) {
        val turns = ArrayList<Turn>()
        val tools = tools()
        for (step in 1..40) {
            if (stopFlag) { Bus.log("■ Stopped"); return }
            val first = if (turns.isEmpty()) JSONArray().put(text("Task: $task\n\n" + observe())) else JSONArray().put(text("Task: $task"))
            val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", first))
            for ((i, t) in turns.withIndex()) {
                msgs.put(JSONObject().put("role", "assistant").put("content", t.assistant))
                val res = JSONArray()
                for (j in t.ids.indices) {
                    val last = i == turns.lastIndex && j == t.ids.lastIndex
                    val content: Any = if (last) t.obs else t.briefs[j]
                    res.put(JSONObject().put("type", "tool_result").put("tool_use_id", t.ids[j]).put("content", content))
                }
                msgs.put(JSONObject().put("role", "user").put("content", res))
            }
            val body = JSONObject().put("model", model).put("max_tokens", 1024)
                .put("system", JSONArray().put(JSONObject().put("type", "text").put("text", SYSTEM).put("cache_control", JSONObject().put("type", "ephemeral"))))
                .put("tools", tools).put("messages", msgs)
            val resp = Api.post(key, body)
            val content = resp.getJSONArray("content")
            val uses = (0 until content.length()).map { content.getJSONObject(it) }.filter { it.getString("type") == "tool_use" }
            for (i in 0 until content.length()) content.getJSONObject(i).let { if (it.getString("type") == "text") Bus.log("💬 " + it.getString("text")) }
            if (uses.isEmpty()) { Bus.log("■ Done"); return }
            val ids = ArrayList<String>(); val briefs = ArrayList<String>()
            for (u in uses) {
                if (stopFlag) { Bus.log("■ Stopped"); return }
                val name = u.getString("name"); val inp = u.getJSONObject("input")
                Bus.log("→ $name $inp")
                if (name == "finish") { Bus.log("✅ " + inp.optString("message")); return }
                ids.add(u.getString("id")); briefs.add(exec(name, inp))
            }
            Thread.sleep(800)
            val o = JSONArray().put(text(briefs.last() + "\n" + observe()))
            if (wantImage) { wantImage = false; screenshotBlock()?.let { o.put(it) } }
            turns.add(Turn(content, ids, briefs, o))
        }
        Bus.log("■ Step limit reached")
    }

    private fun text(s: String) = JSONObject().put("type", "text").put("text", s)

    // ---------- tools ----------
    private fun tool(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) =
        JSONObject().put("name", name).put("description", desc)
            .put("input_schema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req)))

    private fun p(type: String, d: String = "") = JSONObject().put("type", type).put("description", d)

    private fun tools(): JSONArray {
        val a = JSONArray()
        a.put(tool("open_app", "Launch an installed app by its name.", JSONObject().put("name", p("string")), listOf("name")))
        a.put(tool("tap", "Tap the element with the given [id] from the screen list.", JSONObject().put("id", p("integer")), listOf("id")))
        a.put(tool("tap_xy", "Tap raw screen coordinates (use after a screenshot or for elements missing from the list).", JSONObject().put("x", p("integer")).put("y", p("integer")), listOf("x", "y")))
        a.put(tool("long_press", "Long-press element [id] (or x,y).", JSONObject().put("id", p("integer")).put("x", p("integer")).put("y", p("integer"))))
        a.put(tool("type", "Set the text of an input field (replaces existing text). Uses [id] or the focused field. submit=true presses Enter/Send afterwards.",
            JSONObject().put("text", p("string")).put("id", p("integer")).put("submit", p("boolean")), listOf("text")))
        a.put(tool("scroll", "Scroll to reveal more content in the given direction (down = see content further down).",
            JSONObject().put("direction", JSONObject().put("type", "string").put("enum", JSONArray(listOf("up", "down", "left", "right")))), listOf("direction")))
        a.put(tool("swipe", "Swipe/drag from (x1,y1) to (x2,y2) in screen pixels.",
            JSONObject().put("x1", p("integer")).put("y1", p("integer")).put("x2", p("integer")).put("y2", p("integer")).put("ms", p("integer", "duration, default 300")),
            listOf("x1", "y1", "x2", "y2")))
        a.put(tool("key", "Press a system key.", JSONObject().put("key", JSONObject().put("type", "string").put("enum", JSONArray(listOf("back", "home", "recents", "notifications")))), listOf("key")))
        a.put(tool("wait", "Wait for the screen to load.", JSONObject().put("seconds", p("number"))))
        a.put(tool("screenshot", "Attach a screenshot to the next observation. Costly: use only when the element list is empty or insufficient (camera views, images, games).", JSONObject()))
        val f = tool("finish", "End the task. Report the result, or ask the user a question if you are blocked or unsure.", JSONObject().put("message", p("string")), listOf("message"))
        f.put("cache_control", JSONObject().put("type", "ephemeral"))
        a.put(f)
        return a
    }

    private fun exec(name: String, i: JSONObject): String {
        return try {
            when (name) {
                "open_app" -> openApp(i.getString("name"))
                "tap" -> nodeCenter(i.getInt("id"))?.let { tapAt(it.first, it.second, 50) } ?: "error: bad id"
                "tap_xy" -> tapAt(i.getInt("x"), i.getInt("y"), 50)
                "long_press" -> if (i.has("id")) nodeCenter(i.getInt("id"))?.let { tapAt(it.first, it.second, 800) } ?: "error: bad id"
                    else tapAt(i.getInt("x"), i.getInt("y"), 800)
                "type" -> typeText(i.getString("text"), if (i.has("id")) i.getInt("id") else null, i.optBoolean("submit"))
                "scroll" -> {
                    val m = resources.displayMetrics; val w = m.widthPixels; val h = m.heightPixels
                    when (i.getString("direction")) {
                        "down" -> swipe(w / 2, h * 7 / 10, w / 2, h * 3 / 10, 300)
                        "up" -> swipe(w / 2, h * 3 / 10, w / 2, h * 7 / 10, 300)
                        "right" -> swipe(w * 8 / 10, h / 2, w * 2 / 10, h / 2, 300)
                        else -> swipe(w * 2 / 10, h / 2, w * 8 / 10, h / 2, 300)
                    }
                }
                "swipe" -> swipe(i.getInt("x1"), i.getInt("y1"), i.getInt("x2"), i.getInt("y2"), i.optInt("ms", 300))
                "key" -> {
                    val a = when (i.getString("key")) {
                        "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME
                        "recents" -> GLOBAL_ACTION_RECENTS; else -> GLOBAL_ACTION_NOTIFICATIONS
                    }
                    if (performGlobalAction(a)) "ok" else "error: key failed"
                }
                "wait" -> { Thread.sleep((i.optDouble("seconds", 1.0).coerceIn(0.2, 10.0) * 1000).toLong()); "waited" }
                "screenshot" -> { wantImage = true; "screenshot attached below" }
                else -> "error: unknown tool"
            }
        } catch (e: Exception) { "error: ${e.message}" }
    }

    private fun openApp(name: String): String {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
            ?: return "error: no app named $name"
        val intent = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return "error: cannot launch"
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(1200)
        return "opened ${hit.loadLabel(pm)}"
    }

    private fun nodeCenter(id: Int): Pair<Int, Int>? {
        val n = nodes.getOrNull(id) ?: return null
        val r = Rect(); n.getBoundsInScreen(r); return Pair(r.centerX(), r.centerY())
    }

    private fun tapAt(x: Int, y: Int, ms: Long): String {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gesture(path, ms)
    }

    private fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): String {
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return gesture(path, ms.toLong())
    }

    private fun gesture(path: Path, ms: Long): String {
        val latch = CountDownLatch(1); var ok = false
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms.coerceAtLeast(1))).build()
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        if (!sent) return "error: gesture rejected"
        latch.await(5, TimeUnit.SECONDS)
        return if (ok) "ok" else "error: gesture cancelled"
    }

    private fun typeText(t: String, id: Int?, submit: Boolean): String {
        val n = if (id != null) nodes.getOrNull(id) else rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        n ?: return "error: no input field (tap it first or pass id)"
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENTS_SET_TEXT_CHARSEQUENCE, t) }
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "error: set text failed"
        if (submit) { Thread.sleep(300); n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }
        return "typed"
    }

    // ---------- observation ----------
    private fun observe(): String {
        val root = rootInActiveWindow ?: return "Screen: unavailable (try wait or screenshot)"
        nodes.clear()
        val m = resources.displayMetrics
        val sb = StringBuilder("Screen ${m.widthPixels}x${m.heightPixels} app=${root.packageName}\n")
        fun walk(n: AccessibilityNodeInfo) {
            if (!n.isVisibleToUser) return
            val tx = (n.text ?: "").toString().replace('\n', ' ').take(80)
            val ds = (n.contentDescription ?: "").toString().replace('\n', ' ').take(80)
            val rid = n.viewIdResourceName?.substringAfter(":id/") ?: ""
            if ((n.isClickable || n.isEditable || n.isScrollable || n.isCheckable || tx.isNotEmpty() || ds.isNotEmpty()) && nodes.size < 150) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty) {
                    sb.append("[${nodes.size}] ${n.className?.substringAfterLast('.') ?: ""}")
                    if (tx.isNotEmpty()) sb.append(" \"$tx\"")
                    if (ds.isNotEmpty() && ds != tx) sb.append(" desc=\"$ds\"")
                    if (rid.isNotEmpty()) sb.append(" #$rid")
                    sb.append(' ')
                    if (n.isClickable) sb.append('c'); if (n.isEditable) sb.append('e'); if (n.isScrollable) sb.append('s')
                    if (n.isChecked) sb.append('k'); if (n.isFocused) sb.append('f')
                    sb.append(" @${r.centerX()},${r.centerY()}\n")
                    nodes.add(n)
                }
            }
            for (k in 0 until n.childCount) n.getChild(k)?.let { walk(it) }
        }
        walk(root)
        if (nodes.size < 3) sb.append("(few elements visible: consider screenshot)\n")
        return sb.toString()
    }

    private fun screenshotBlock(): JSONObject? {
        val latch = CountDownLatch(1); var bmp: Bitmap? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                bmp = hw?.copy(Bitmap.Config.ARGB_8888, false); hw?.recycle(); r.hardwareBuffer.close(); latch.countDown()
            }
            override fun onFailure(code: Int) { latch.countDown() }
        })
        latch.await(5, TimeUnit.SECONDS)
        val b = bmp ?: return null
        val s = Bitmap.createScaledBitmap(b, 540, (b.height * 540f / b.width).toInt(), true)
        val out = ByteArrayOutputStream(); s.compress(Bitmap.CompressFormat.JPEG, 60, out)
        // image is downscaled by this factor; coordinates in tap_xy are real screen pixels
        return JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg")
            .put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)))
    }
}

private const val SYSTEM = """You control an Android phone via tools to complete the user's task. Each turn you receive a compact list of on-screen elements: [id] Class "text" desc="..." flags(c=clickable,e=editable,s=scrollable,k=checked,f=focused) @centerX,centerY.
Rules:
- Be efficient: fewest steps possible. You may call several tools in one turn only when later steps don't depend on a changed screen.
- Prefer open_app over navigating home screens. Prefer tap by id; use tap_xy only for elements missing from the list.
- Do NOT use screenshot unless the list lacks what you need (e.g. camera preview, images). If a screenshot is shown it is scaled to 540px wide; tap_xy takes real screen pixels (see Screen WxH), so scale coordinates up.
- Ids change after every screen change; only use ids from the latest list.
- If a step fails twice, try a different approach (scroll, back, other element).
- Stay on the user's task only. Never reveal or enter passwords/payment info unless the user gave them in the task. If the task is ambiguous or you are blocked, call finish with a question.
- When done, call finish with a one-line result."""
