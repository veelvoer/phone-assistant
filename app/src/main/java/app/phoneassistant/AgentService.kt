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
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.Manifest
import android.content.pm.PackageManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    @Volatile var running = false; private set
    @Volatile private var stopFlag = false
    private var overlay: TextView? = null
    private var wantImage = false

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun stop() { stopFlag = true; main.post { rec?.cancel(); rec = null; if (!running) hideOverlay() } }

    // ---------- Essential Key: double-press and hold to talk ----------
    private var rec: SpeechRecognizer? = null
    private var lastUp = 0L
    private var second = false
    private var held: Runnable? = null

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.scanCode != ESSENTIAL_SCAN) return false
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount > 0) return true
            second = lastUp > 0 && e.eventTime - lastUp < 500
            if (second) { held = Runnable { held = null; lastUp = 0; listen() }; main.postDelayed(held!!, 450) }
        } else if (e.action == KeyEvent.ACTION_UP) {
            held?.let { main.removeCallbacks(it); held = null }
            lastUp = if (second) 0 else e.eventTime
            second = false
        }
        return true   // swallow every Essential Key event so Nothing's own capture doesn't fire
    }

    private fun listen() {
        if (running) { stop(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { showOverlay("Open Phone Assistant once and allow the microphone"); main.postDelayed({ hideOverlay() }, 3000); return }
        val c = cfgFromPrefs()
        if (c == null) { showOverlay("Add your API key in the app first"); main.postDelayed({ hideOverlay() }, 3000); return }
        (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        showOverlay("🎤 Listening… (tap to cancel)")
        rec?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this); rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { showOverlay("🎤 $it") } }
            override fun onResults(b: Bundle?) {
                rec = null
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) { showOverlay("Didn't catch that"); main.postDelayed({ hideOverlay() }, 1500) } else start(t, c)
            }
            override fun onError(error: Int) { rec = null; showOverlay("Didn't catch that"); main.postDelayed({ hideOverlay() }, 1500) }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
    }

    private fun cfgFromPrefs(): Cfg? {
        val p = getSharedPreferences("p", MODE_PRIVATE); val h = p.getBoolean("hearth", false)
        val k = p.getString(if (h) "key_h" else "key", "")?.trim().orEmpty(); val u = p.getString("url", "")?.trim().orEmpty()
        if (k.isEmpty() || (h && u.isEmpty())) return null
        return Cfg(h, if (h) u else "", k, p.getString(if (h) "model_h" else "model", if (h) "haiku" else "claude-haiku-5-5")!!)
    }

    fun start(task: String, cfg: Cfg): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { start(task, cfg) }; return true }
        if (running) return false
        running = true; stopFlag = false
        showOverlay("Working… (tap to stop)"); Bus.listener?.invoke()
        Bus.log("▶ $task")
        thread {
            try { loop(task, cfg) } catch (e: Exception) { Bus.log("Error: ${e.message}") }
            running = false; hideOverlay(); Bus.listener?.invoke()
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
    class Cfg(val hearth: Boolean, val url: String, val key: String, val model: String)
    private class Turn(val assistant: JSONArray, val ids: List<String>, val briefs: List<String>, val obs: JSONArray, val raw: String, val observation: String)

    private fun loop(task: String, cfg: Cfg) {
        val turns = ArrayList<Turn>()
        val tools = tools()
        val hearth = cfg.hearth
        for (step in 1..40) {
            if (stopFlag) { Bus.log("■ Stopped"); return }
            val firstText = if (turns.isEmpty()) "Task: $task\n\n" + observe() else "Task: $task"
            val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", if (hearth) firstText else JSONArray().put(text(firstText))))
            for ((i, t) in turns.withIndex()) {
                val lastTurn = i == turns.lastIndex
                if (hearth) {
                    msgs.put(JSONObject().put("role", "assistant").put("content", t.raw))
                    msgs.put(JSONObject().put("role", "user").put("content", t.briefs.joinToString("\n") + if (lastTurn) "\n" + t.observation else ""))
                    continue
                }
                msgs.put(JSONObject().put("role", "assistant").put("content", t.assistant))
                val res = JSONArray()
                for (j in t.ids.indices) {
                    val last = lastTurn && j == t.ids.lastIndex
                    val content: Any = if (last) t.obs else t.briefs[j]
                    res.put(JSONObject().put("type", "tool_result").put("tool_use_id", t.ids[j]).put("content", content))
                }
                msgs.put(JSONObject().put("role", "user").put("content", res))
            }
            val body = JSONObject().put("model", cfg.model).put("max_tokens", 1024).put("messages", msgs)
            if (hearth) body.put("system", SYSTEM_TEXT).put("stream", false)
            else body.put("system", JSONArray().put(JSONObject().put("type", "text").put("text", SYSTEM).put("cache_control", JSONObject().put("type", "ephemeral")))).put("tools", tools)
            val resp = Api.post(cfg.url, cfg.key, body)
            val content = resp.getJSONArray("content")
            val raw = (0 until content.length()).map { content.getJSONObject(it) }.filter { it.getString("type") == "text" }.joinToString("\n") { it.getString("text") }
            val calls = ArrayList<Triple<String, String, JSONObject>>()   // id, name, input
            if (hearth) {
                val acts = parseActions(raw)
                Bus.log("💬 " + raw.substringBefore('[').trim().take(300))
                acts.forEachIndexed { k, a -> calls.add(Triple("h$step-$k", a.optString("tool"), a)) }
            } else {
                for (i in 0 until content.length()) content.getJSONObject(i).let {
                    if (it.getString("type") == "text") Bus.log("💬 " + it.getString("text"))
                    if (it.getString("type") == "tool_use") calls.add(Triple(it.getString("id"), it.getString("name"), it.getJSONObject("input")))
                }
            }
            if (calls.isEmpty()) { Bus.log("■ Done"); return }
            val ids = ArrayList<String>(); val briefs = ArrayList<String>()
            for ((id, name, inp) in calls) {
                if (stopFlag) { Bus.log("■ Stopped"); return }
                Bus.log("→ $name $inp")
                if (name == "finish") { Bus.log("✅ " + inp.optString("message")); return }
                ids.add(id); briefs.add(if (hearth && name == "screenshot") "error: screenshots unavailable" else exec(name, inp))
            }
            Thread.sleep(800)
            val observation = briefs.last() + "\n" + observe()
            val o = JSONArray().put(text(observation))
            if (wantImage) { wantImage = false; if (!hearth) screenshotBlock()?.let { o.put(it) } }
            turns.add(Turn(content, ids, briefs, o, raw, observation))
        }
        Bus.log("■ Step limit reached")
    }

    /** Hearth has no tool calling: the model answers with a JSON array of actions like [{"tool":"tap","id":3}]. */
    private fun parseActions(s: String): List<JSONObject> {
        val a = s.indexOf('['); val b = s.lastIndexOf(']')
        return try {
            if (a >= 0 && b > a) JSONArray(s.substring(a, b + 1)).let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
            else { val x = s.indexOf('{'); val y = s.lastIndexOf('}'); if (x >= 0 && y > x) listOf(JSONObject(s.substring(x, y + 1))) else emptyList() }
        } catch (e: Exception) { emptyList() }
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
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t) }
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
                    sb.append("[${nodes.size}] ${n.className?.toString()?.substringAfterLast('.') ?: ""}")
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

private const val ESSENTIAL_SCAN = 250

private const val SYSTEM = """You control an Android phone via tools to complete the user's task. Each turn you receive a compact list of on-screen elements: [id] Class "text" desc="..." flags(c=clickable,e=editable,s=scrollable,k=checked,f=focused) @centerX,centerY.
Rules:
- Be efficient: fewest steps possible. You may call several tools in one turn only when later steps don't depend on a changed screen.
- Prefer open_app over navigating home screens. Prefer tap by id; use tap_xy only for elements missing from the list.
- Do NOT use screenshot unless the list lacks what you need (e.g. camera preview, images). If a screenshot is shown it is scaled to 540px wide; tap_xy takes real screen pixels (see Screen WxH), so scale coordinates up.
- Ids change after every screen change; only use ids from the latest list.
- If a step fails twice, try a different approach (scroll, back, other element).
- Stay on the user's task only. Never reveal or enter passwords/payment info unless the user gave them in the task. If the task is ambiguous or you are blocked, call finish with a question.
- When done, call finish with a one-line result."""

private const val SYSTEM_TEXT = """You control an Android phone to complete the user's task. Each turn you get a compact list of on-screen elements: [id] Class "text" desc="..." flags(c=clickable,e=editable,s=scrollable,k=checked,f=focused) @centerX,centerY.
Reply with a one-line thought, then a JSON array of actions (nothing after it), e.g.
Open the app. [{"tool":"open_app","name":"Snapchat"}]
Tools:
open_app{name} | tap{id} | tap_xy{x,y} (real screen pixels) | long_press{id or x,y} | type{text,id?,submit?} (replaces field text; submit presses Enter/Send) | scroll{direction: up|down|left|right} (down = see content further down) | swipe{x1,y1,x2,y2,ms?} | key{key: back|home|recents|notifications} | wait{seconds} | finish{message}
Rules:
- Be efficient: fewest steps. Put several actions in the array only when later ones don't depend on a changed screen.
- Prefer open_app over navigating. Prefer tap by id. Ids change after every screen change; use only the latest list. No screenshots are available.
- If a step fails twice, try another approach (scroll, back, other element).
- Stay on the user's task. Never enter passwords/payment info unless given in the task. If ambiguous or blocked, finish with a question.
- When done, reply with [{"tool":"finish","message":"one-line result"}]."""
