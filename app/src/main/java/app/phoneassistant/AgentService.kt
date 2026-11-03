package app.phoneassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AgentService : AccessibilityService() {
    companion object {
        @Volatile var instance: AgentService? = null
        @Volatile var todos: List<Todo> = emptyList()
    }
    class Todo(val text: String, val status: String)

    private val main = Handler(Looper.getMainLooper())
    private val plainClasses = setOf("TextView", "View", "ViewGroup", "FrameLayout", "LinearLayout", "RelativeLayout", "ConstraintLayout", "LinearLayoutCompat")
    @Volatile private var scrollStreak = 0
    private val nodes = ArrayList<AccessibilityNodeInfo>()
    private val rects = ArrayList<Rect>()
    @Volatile var running = false; private set
    @Volatile private var stopFlag = false
    private var ov: Overlay? = null
    @Volatile private var statusLine = ""
    @Volatile private var goal = ""
    private val wantImageLock = false
    @Volatile private var doneWhen = ""
    private var ann: Annotations? = null
    private fun clearAnn() = main.post { ann?.hide(); ann = null }
    @Volatile private var resultText = ""
    @Volatile private var resultUntil = 0L
    private var wantImage = false

    private val audio by lazy { getSystemService(AUDIO_SERVICE) as android.media.AudioManager }
    private var focusReq: android.media.AudioFocusRequest? = null

    /** A phone/VoIP call is ringing or active: the assistant must be silent and gone. */
    private fun inCall(): Boolean = audio.mode.let { it == android.media.AudioManager.MODE_IN_CALL || it == android.media.AudioManager.MODE_IN_COMMUNICATION || it == android.media.AudioManager.MODE_RINGTONE || it == android.media.AudioManager.MODE_CALL_SCREENING }

    private val callWatch = object : Runnable {
        override fun run() {
            if ((voice || speaking || running || ov != null) && inCall()) {
                Bus.log("📞 call detected: assistant stopped")
                tts?.stop(); speaking = false; abandonFocus()
                stopFlag = true; endSession(true); hideOverlay()
            }
            main.postDelayed(this, 800)
        }
    }

    private fun takeFocus() {
        val r = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { c -> if (c == android.media.AudioManager.AUDIOFOCUS_LOSS || c == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) main.post { tts?.stop(); speaking = false; if (voice) startRec() } }.build()
        focusReq = r; audio.requestAudioFocus(r)
    }

    private fun abandonFocus() { focusReq?.let { audio.abandonAudioFocusRequest(it) }; focusReq = null }

    override fun onServiceConnected() {
        instance = this
        main.removeCallbacks(callWatch); main.postDelayed(callWatch, 800)
        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { speaking = false; abandonFocus(); if (voice) startRec() } }
            @Deprecated("") override fun onError(id: String?) { main.post { speaking = false; abandonFocus(); if (voice) startRec() } }
        })
    }
    @Volatile private var lastEvent = 0L
    override fun onAccessibilityEvent(e: AccessibilityEvent?) { lastEvent = android.os.SystemClock.uptimeMillis() }
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun interrupt(t: String) { if (t.isNotBlank() && running) { extras.add(t.trim()); Bus.log("➕ ${t.trim()}") } }

    /** Wake the screen and, if possible, get past the lock screen; ask the user to unlock when a PIN/pattern is needed. */
    private fun ensureAwake(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!pm.isInteractive) {
            @Suppress("DEPRECATION") val wl = pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "phoneassistant:wake")
            wl.acquire(8000); Thread.sleep(900)
        }
        if (!km.isKeyguardLocked) return true
        val m = resources.displayMetrics
        swipe(m.widthPixels / 2, m.heightPixels * 9 / 10, m.widthPixels / 2, m.heightPixels * 3 / 10, 250); Thread.sleep(900)
        if (!km.isKeyguardLocked) return true
        statusLine = "Unlock your phone"; refreshOverlay(); speak("Ontgrendel je telefoon"); Bus.log("🔒 waiting for you to unlock the phone")
        var waited = 0
        while (km.isKeyguardLocked && waited < 45_000 && !stopFlag) { Thread.sleep(500); waited += 500 }
        Thread.sleep(500)
        return !km.isKeyguardLocked
    }

    private fun planText(): String = (if (goal.isEmpty()) "" else "\n🎯 GOAL: $goal" + (if (doneWhen.isEmpty()) "" else "\n✔ DONE WHEN: $doneWhen")) + if (todos.isEmpty()) "" else "\nTo-do list:\n" + todos.mapIndexed { i, t -> "${i + 1}. [${t.status}] ${t.text}" }.joinToString("\n")

    private fun openTodos() = todos.filter { it.status == "pending" || it.status == "doing" }

    private fun setPlan(arr: JSONArray): String {
        val l = ArrayList<Todo>()
        for (k in 0 until arr.length()) arr.optJSONObject(k)?.let { l.add(Todo(it.optString("text").take(120), it.optString("status", "pending").ifEmpty { "pending" })) }
        todos = l
        Bus.log("📋 " + l.joinToString(" | ") { (if (it.status == "done") "✓ " else if (it.status == "doing") "▶ " else if (it.status == "skipped") "– " else "○ ") + it.text })
        main.post { refreshOverlay() }; Bus.listener?.invoke()
        return "plan updated (${l.count { it.status == "done" }}/${l.size} done)"
    }

    fun startFromPrefs(task: String) { main.post { cfgFromPrefs()?.let { start(task, it) } ?: Bus.log("No API key saved in the app") } }

    fun stop() { clearAnn(); stopFlag = true; main.post { endSession(false) } }

    // ---------- Essential Key: press and hold to talk ----------
    private var rec: SpeechRecognizer? = null
    private var held: Runnable? = null

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.scanCode != ESSENTIAL_SCAN) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            held?.let { main.removeCallbacks(it) }
            held = Runnable { held = null; listen() }; main.postDelayed(held!!, 450)
        } else if (e.action == KeyEvent.ACTION_UP) {
            held?.let { main.removeCallbacks(it); held = null }
        }
        return false
    }

    // Voice session: hold the key once, keep talking; every sentence becomes a command until you say stop or tap the overlay.
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var speaking = false
    private var voice = false
    private var tempVoice = false
    @Volatile private var asking = false
    private var question = ""
    private val answers = java.util.concurrent.LinkedBlockingQueue<String>()
    private val notes = ArrayList<String>()   // earlier commands + results this session
    private var partial = ""
    private var voiceCfg: Cfg? = null
    private var errStreak = 0
    private val extras = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val stopWords = setOf("stop", "stoppen", "stop maar", "stop met luisteren", "klaar", "dat was het", "annuleer", "annuleren", "cancel", "that's all", "exit", "stop listening")

    private fun buzz(ms: Long = 40) = (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))

    private fun refreshOverlay() = main.post {
        val cur = todos.firstOrNull { it.status == "doing" } ?: todos.firstOrNull { it.status == "pending" }
        val planSub = cur?.let { "${todos.count { t -> t.status == "done" } + 1}/${todos.size} · ${it.text}" }
        val prog = if (todos.isEmpty()) 0f else (todos.count { it.status == "done" } + 0.3f) / todos.size
        val fresh = System.currentTimeMillis() < resultUntil
        val o = ov()
        when {
            asking -> o.update(Overlay.State.ASKING, "Question", question, if (voice) partial.ifEmpty { "Say your answer…" } else null, 0f)
            running -> o.update(Overlay.State.WORKING, if (voice) "Working · listening" else "Working", statusLine.ifEmpty { "Thinking…" },
                if (voice && partial.isNotEmpty()) "🎤 $partial" else planSub ?: if (voice) "Say something to add or change" else null, prog)
            voice -> o.update(Overlay.State.LISTENING, if (fresh) "Done" else "Listening", if (partial.isNotEmpty()) partial else if (fresh) resultText else "Say what I should do…", if (fresh) "Listening for your next command" else null, 0f)
            fresh -> o.update(Overlay.State.INFO, "Done", resultText, null, 0f)
            else -> { ov?.hide(); ov = null }
        }
    }

    private fun showResult(m: String) { resultText = m; resultUntil = System.currentTimeMillis() + 4500; refreshOverlay(); main.postDelayed({ refreshOverlay() }, 4600) }

    private fun listen() {
        if (voice) { endSession(true); return }
        if (inCall()) return   // never wake up during a call
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { showOverlay("Open Phone Assistant once and allow the microphone"); main.postDelayed({ refreshOverlay() }, 3000); return }
        val c = cfgFromPrefs()
        if (c == null) { showOverlay("Add your API key in the app first"); main.postDelayed({ refreshOverlay() }, 3000); return }
        voiceCfg = c; beginVoice()
    }

    private fun beginVoice() { voice = true; errStreak = 0; partial = ""; buzz(); refreshOverlay(); startRec() }

    private fun endSession(announce: Boolean) {
        if (announce) stopFlag = true
        val was = voice
        voice = false; tempVoice = false; asking = false; partial = ""
        rec?.cancel(); rec?.destroy(); rec = null
        if (was) { buzz(80); sessionTurns = ArrayList(); sessionTask = ""; notes.clear() }
        refreshOverlay()
    }

    private fun startRec() {
        if (!voice || speaking) return
        rec?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this); rec = r
        fun again(ms: Long) = main.postDelayed({ startRec() }, ms)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it; refreshOverlay() } }
            override fun onResults(b: Bundle?) {
                errStreak = 0; partial = ""
                val t = bestHeard(b)
                if (t.isNotEmpty()) utterance(t)
                refreshOverlay(); again(200)
            }
            override fun onError(error: Int) {
                partial = ""
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { refreshOverlay(); again(150); return }
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || ++errStreak >= 6) { Bus.log("Voice stopped: recognizer error $error"); endSession(false); return }
                again(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 800 else 400)
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) { ov?.level(v) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        val lang = getSharedPreferences("p", MODE_PRIVATE).getString("lang", "nl-BE").orEmpty()
        names().takeIf { it.isNotEmpty() }?.let { i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(it)) }
        if (lang.isNotEmpty()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang).putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
        r.startListening(i)
    }

    private fun facts(): List<String> = getSharedPreferences("p", MODE_PRIVATE).getString("facts", "").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun saveFacts(l: List<String>) {
        var x = l.map { it.take(110) }.toMutableList()
        while (x.joinToString("\n").length > 1800 && x.isNotEmpty()) x.removeAt(0)   // keep it short: it is sent with every task
        getSharedPreferences("p", MODE_PRIVATE).edit().putString("facts", x.joinToString("\n")).apply()
    }

    private fun remember(fact: String): String {
        val f = fact.trim().trimEnd('.').take(110); if (f.length < 3) return "error: fact too short"
        val key = f.substringBefore('=').substringBefore(':').trim().lowercase().takeIf { f.contains('=') || f.contains(':') }
        val l = facts().filterNot { it.equals(f, true) || (key != null && (it.substringBefore('=').substringBefore(':').trim().lowercase() == key && (it.contains('=') || it.contains(':')))) }.toMutableList()
        l.add(f); saveFacts(l); Bus.listener?.invoke(); return "remembered: $f"
    }

    private fun forget(q: String): String {
        val n = norm(q); if (n.length < 2) return "error: say what to forget"
        val l = facts(); val keep = l.filterNot { norm(it).contains(n) }
        saveFacts(keep); Bus.listener?.invoke(); return "forgot ${l.size - keep.size} fact(s)"
    }

    private fun names(): List<String> = (namesRaw() + facts().flatMap { f -> Regex("\\p{Lu}[\\p{L}'-]{2,}").findAll(f).map { it.value }.toList() }).distinct().take(60)

    private fun namesRaw(): List<String> = getSharedPreferences("p", MODE_PRIVATE).getString("names", "").orEmpty().split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun aliases(): List<String> = getSharedPreferences("p", MODE_PRIVATE).getString("aliases", "").orEmpty().lines().filter { it.isNotBlank() }

    private fun langTag() = getSharedPreferences("p", MODE_PRIVATE).getString("lang", "nl-BE").orEmpty()

    private fun bestVoice(e: TextToSpeech, loc: Locale): android.speech.tts.Voice? {
        val online = (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).activeNetwork != null
        return try {
            e.voices.orEmpty().filter { it.locale.language == loc.language && !it.features.orEmpty().contains("notInstalled") && (online || !it.isNetworkConnectionRequired) }
                .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == loc.country || loc.country.isEmpty() }.thenByDescending { it.quality }.thenByDescending { it.latency * -1 }).firstOrNull()
        } catch (x: Exception) { null }
    }

    private fun speak(t: String) = main.post {
        val e = tts ?: return@post
        if (!ttsReady || t.isBlank() || inCall() || !getSharedPreferences("p", MODE_PRIVATE).getBoolean("speak", true)) return@post
        speaking = true; rec?.cancel(); rec?.destroy(); rec = null
        val tag = langTag(); val loc = if (tag.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(tag)
        e.language = loc
        bestVoice(e, loc)?.let { e.voice = it }
        e.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        e.setSpeechRate(1.05f); e.setPitch(1.0f)
        takeFocus()
        val txt = t.take(600)
        if (e.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "u") != TextToSpeech.SUCCESS) { speaking = false; abandonFocus(); if (voice) startRec(); return@post }
        // never stay deaf if the "done" callback gets lost
        main.postDelayed({ if (speaking) { speaking = false; abandonFocus(); if (voice) startRec() } }, 2000L + txt.length * 90L)
    }

    /** Blocks the agent thread until the user answers by voice. */
    private fun askUser(q: String): String {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "error: cannot ask, no microphone permission. Make your best guess or finish."
        answers.clear(); question = q; asking = true
        Bus.log("❓ $q")
        main.post { speak(q); if (!voice) { tempVoice = true; beginVoice() } else refreshOverlay() }
        var waited = 0
        while (!stopFlag && waited < 180_000) {
            val a = answers.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (a != null) { main.post { asking = false; if (tempVoice) endSession(false) else refreshOverlay() }; return "User answered: $a" }
            waited += 500
        }
        main.post { asking = false; if (tempVoice) endSession(false) else refreshOverlay() }
        return if (stopFlag) "error: stopped" else "error: user did not answer. Make your best guess or finish."
    }

    /** Of the recogniser's alternatives pick the one that contains the most names/words the user is known to use. */
    private fun bestHeard(b: Bundle?): String {
        val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return ""
        val conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        val known = names().map { norm(it) }.filter { it.length >= 3 }
        var best = ""; var bs = -1f
        list.forEachIndexed { i, c ->
            val n = norm(c)
            val sc = known.count { n.contains(it) } * 2f + (conf?.getOrNull(i) ?: (1f - i * 0.05f))
            if (sc > bs) { bs = sc; best = c }
        }
        return best.trim()
    }

    private fun utterance(t: String) {
        val n = t.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), "").trim()
        if (n in stopWords) { Bus.log("🎤 \"$t\" → stop"); endSession(true); return }
        Bus.log("🎤 $t")
        if (asking) { answers.put(t); return }
        if (running || voiceCfg?.let { start(t, it) } != true) { extras.add(t); Bus.log("➕ queued: $t") }
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
        clearAnn(); Usage.beginTask()
        if (shortcut(task)) return true
        val cont = false
        running = true; stopFlag = false; if (!cont) todos = emptyList()
        refreshOverlay(); Bus.listener?.invoke()
        Bus.log("▶ $task")
        thread {
            runDid = ArrayList(); noted = false
            if (!ensureAwake()) { Bus.log("■ Phone is locked"); running = false; Bus.listener?.invoke(); main.post { refreshOverlay() }; return@thread }
            try { loop(task, cfg, cont) } catch (e: Throwable) { Bus.log("Error: ${e.message ?: e.javaClass.simpleName}") }
            if (!noted) addNote(task, "(did not finish: stopped or failed)")
            Bus.log("💰 " + Usage.taskLine(cfg.hearth))
            running = false; Bus.listener?.invoke()
            main.post {
                refreshOverlay()
                // instructions spoken while the run was ending must not get lost
                val rest = ArrayList<String>(); while (true) rest.add(extras.poll() ?: break)
                if (rest.isNotEmpty() && voice && !stopFlag) start(rest.joinToString(". "), cfg)
            }
        }
        return true
    }

    // ---------- overlay ----------
    private fun showOverlay(t: String) = main.post { ov().update(Overlay.State.INFO, "Phone Assistant", t, null, 0f) }

    private fun hideOverlay() = main.post { ov?.hide(); ov = null }

    private fun ov(): Overlay = ov ?: Overlay(this) { stop() }.also { ov = it }

    // ---------- agent loop ----------
    class Cfg(val hearth: Boolean, val url: String, val key: String, val model: String)
    private class Turn(var assistant: JSONArray, val ids: List<String>, val briefs: List<String>, var obs: JSONArray, val raw: String, var observation: String)
    private var runDid = ArrayList<String>()
    private var noted = false

    private fun addNote(task: String, result: String) {
        noted = true
        notes.add("User said: \"$task\"\n  You did: " + runDid.takeLast(20).joinToString("; ").take(700) + "\n  Result: $result")
        while (notes.size > 4) notes.removeAt(0)
    }

    /** Trivial commands run locally: zero tokens, instant. */
    private fun shortcut(task: String): Boolean {
        val t = task.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()
        Regex("^(onthoud|onthou|remember)( dat| that)?\\s+(.{3,150})$", RegexOption.IGNORE_CASE).find(task.trim())?.let { m ->
            val r = remember(m.groupValues[3]); Bus.log("🧠 $r (no AI needed)"); showResult("Onthouden: " + m.groupValues[3]); speak("Onthouden"); return true
        }
        Regex("^(vergeet|forget)( dat| that)?\\s+(.{2,100})$", RegexOption.IGNORE_CASE).find(task.trim())?.let { m ->
            val r = forget(m.groupValues[3]); Bus.log("🧠 $r (no AI needed)"); showResult(r); speak("Vergeten"); return true
        }
        val act: (() -> String)? = when {
            t in setOf("home", "ga naar home", "startscherm", "ga naar het startscherm", "go home") -> { { performGlobalAction(GLOBAL_ACTION_HOME); "Home" } }
            t in setOf("terug", "ga terug", "back", "go back") -> { { performGlobalAction(GLOBAL_ACTION_BACK); "Back" } }
            t in setOf("recente apps", "recents", "recent apps", "toon recente apps") -> { { performGlobalAction(GLOBAL_ACTION_RECENTS); "Recents" } }
            Regex("^(open|start|ga naar|launch|open de app|open app)\\s+(.{2,40})$").matches(t) -> { {
                val name = Regex("^(open de app|open app|open|start|ga naar|launch)\\s+(.+)$").find(t)!!.groupValues[2]
                val pm = packageManager
                val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                val hit = apps.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
                if (hit == null) "" else { pm.getLaunchIntentForPackage(hit.activityInfo.packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; "Opened ${hit.loadLabel(pm)}" }
            } }
            else -> null
        }
        if (act == null) return false
        val r = try { act() } catch (e: Exception) { "" }
        if (r.isEmpty()) return false
        Bus.log("⚡ $r (no AI needed)"); showResult(r); return true
    }

    private fun friendly(name: String, i: JSONObject, dsc: String): String = when (name) {
        "open_app" -> "Opening ${i.optString("name")}"
        "tap", "tap_xy" -> if (dsc.contains('"')) "Tapping " + dsc.substringAfter(' ') else "Tapping"
        "long_press" -> "Long-pressing"
        "type" -> "Typing “${i.optString("text").take(40)}”"
        "find_tap" -> "Looking for “${i.optString("text").take(40)}”"
        "scroll" -> "Scrolling ${i.optString("direction")}"
        "swipe" -> "Swiping"
        "key" -> when (i.optString("key")) { "back" -> "Going back"; "home" -> "Going home"; else -> "Pressing ${i.optString("key")}" }
        "wait" -> "Waiting…"
        "read_screen" -> "Reading the screen"
        "open_url" -> "Opening link"
        "volume" -> "Setting volume"
        "ask_user" -> "Asking you"
        "plan" -> statusLine.ifEmpty { "Planning" }
        else -> "Working…"
    }

    private fun describe(name: String, i: JSONObject): String = when (name) {
        "tap", "long_press" -> "$name " + ((if (i.has("id")) nodes.getOrNull(i.getInt("id"))?.let { n -> ((n.text ?: n.contentDescription) ?: n.className)?.toString()?.take(40)?.let { "\"$it\"" } } else null) ?: (if (i.has("id")) "#${i.optInt("id")}" else "${i.optInt("x")},${i.optInt("y")}"))
        "type" -> "type \"${i.optString("text").take(60)}\""
        "open_app" -> "open ${i.optString("name")}"
        "plan", "wait", "read_screen" -> ""
        else -> "$name ${i.toString().take(60)}"
    }

    private var sessionTurns = ArrayList<Turn>()
    private var sessionTask = ""

    private fun loop(task: String, cfg: Cfg, cont: Boolean) {
        extras.clear(); statusLine = ""; goal = ""; doneWhen = ""
        // In a voice session the conversation carries on: a follow-up/correction after "finish" sees everything done so far.
        val turns = if (cont) sessionTurns else ArrayList<Turn>().also { sessionTurns = it }
        var taskText = if (cont) sessionTask + "\nFOLLOW-UP from the user (after you finished; it may correct or extend what you did: use the earlier context, e.g. fix, undo or redo it): $task" else task
        if (cont && turns.isNotEmpty()) { val ob = "The user sent a follow-up (see Task).\n" + observe() + planText(); turns.last().obs = JSONArray().put(text(ob)); turns.last().observation = ob }
        var nudges = 0; var lastHash = 0; var same = 0; var finishWarned = false; var evWarned = false
        var model = cfg.model; var noThinking = false; var stall = 0; var lastDone = 0
        val escalate = { why: String -> if (!cfg.hearth && model.contains("haiku")) { model = "claude-sonnet-5-5"; noThinking = true; turns.forEach { it.assistant = stripThinking(it.assistant) }; Bus.log("⬆ $why: switching to a stronger model for this task") } }
        val smartPlan = getSharedPreferences("p", MODE_PRIVATE).getBoolean("smartplan", true)
        val tools = tools()
        val hearth = cfg.hearth
        for (step in 1..60) {
            while (true) { val x = extras.poll() ?: break; taskText += "\nUser then added (INTERRUPT: update your to-do list with the plan tool to reflect this, then continue): $x"; Bus.log("➕ $x") }
            sessionTask = taskText
            while (turns.size > 40) turns.removeAt(0)
            if (stopFlag) { Bus.log("■ Stopped"); return }
            val firstText = (if (facts().isEmpty()) "" else "About the user (saved memory; use it to resolve \"my mom\", \"me\", etc.):\n" + facts().joinToString("\n") + "\n\n") + (if (names().isEmpty()) "" else "Known names/words of the user (speech recognition often mishears names: correct what they said to the closest of these): " + names().joinToString(", ") + (aliases().let { if (it.isEmpty()) "" else "\nLearned corrections (heard=actual): " + it.joinToString("; ") }) + "\n\n") + (if (notes.isEmpty()) "" else "Earlier commands this session (the new message may correct, undo or continue them; check the screen and fix what is wrong):\n" + notes.joinToString("\n") + "\n\n") + "Task: $taskText" + (if (turns.isEmpty()) "\n\n" + observe() + "\n(First call the plan tool.)" else "")
            val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", if (hearth) firstText else JSONArray().put(text(firstText))))
            for ((i, t) in turns.withIndex()) {
                val lastTurn = i == turns.lastIndex
                if (hearth) {
                    msgs.put(JSONObject().put("role", "assistant").put("content", t.raw))
                    msgs.put(JSONObject().put("role", "user").put("content", (if (t.briefs.isEmpty()) "Continue." else t.briefs.joinToString("\n")) + if (lastTurn) "\n" + t.observation else ""))
                    continue
                }
                msgs.put(JSONObject().put("role", "assistant").put("content", t.assistant))
                if (t.ids.isEmpty()) { msgs.put(JSONObject().put("role", "user").put("content", if (lastTurn) t.obs else JSONArray().put(text("Continue.")))); continue }
                val res = JSONArray()
                for (j in t.ids.indices) {
                    val last = lastTurn && j == t.ids.lastIndex
                    val content: Any = if (last) t.obs else t.briefs[j]
                    res.put(JSONObject().put("type", "tool_result").put("tool_use_id", t.ids[j]).put("content", content).also { if (i == turns.lastIndex - 1 && j == t.ids.lastIndex) it.put("cache_control", JSONObject().put("type", "ephemeral")) })
                }
                msgs.put(JSONObject().put("role", "user").put("content", res))
            }
            val planCall = step == 1 && turns.isEmpty()
            val callModel = if (!hearth && planCall && smartPlan && model.contains("haiku")) "claude-sonnet-5-5" else model   // a stronger model writes the plan; the cheap one executes it
            val body = JSONObject().put("model", callModel).put("max_tokens", 800).put("messages", msgs)
            // forcing a tool is only supported on the Haiku models; Sonnet/Opus 5.5 reject it (they follow the instructions instead)
            if (!hearth && callModel.contains("haiku")) body.put("tool_choice", if (planCall) JSONObject().put("type", "tool").put("name", "plan") else JSONObject().put("type", "any"))
            if (!hearth && callModel.contains("sonnet")) body.put("output_config", JSONObject().put("effort", "medium"))
            if (hearth) body.put("system", SYSTEM_TEXT).put("stream", false)
            else body.put("system", JSONArray().put(JSONObject().put("type", "text").put("text", SYSTEM).put("cache_control", JSONObject().put("type", "ephemeral")))).put("tools", tools)
            statusLine = "Thinking…"; refreshOverlay()
            if (noThinking && !hearth) body.put("thinking", JSONObject().put("type", if (callModel.contains("haiku")) "disabled" else "between_tools"))
            val resp = try { Api.post(cfg.url, cfg.key, body) } catch (e: RuntimeException) {
                if (!hearth && !noThinking && (e.message ?: "").contains("thinking")) {   // thinking-block replay problem: drop all of them and run without thinking
                    noThinking = true; turns.forEach { it.assistant = stripThinking(it.assistant) }; Bus.log("↻ retrying without thinking blocks"); continue
                }
                throw e
            }
            Usage.record(this, callModel, resp, hearth)
            val content = resp.getJSONArray("content").let { if (callModel != model) stripThinking(it) else it }
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
            if (calls.isEmpty()) {
                if (++nudges > 3) { Bus.log("■ Done"); return }
                val nudge = "You must call a tool now: continue towards the goal, ask_user if unsure, or finish if the goal is verified done."
                val ob = nudge + "\n" + observe() + planText()
                turns.add(Turn(content, emptyList(), emptyList(), JSONArray().put(text(ob)), raw, ob)); continue
            }
            val ids = ArrayList<String>(); val briefs = ArrayList<String>()
            for ((id, name, inp) in calls) {
                if (stopFlag) { Bus.log("■ Stopped"); return }
                inp.optString("why").takeIf { it.isNotBlank() }?.let { Bus.log("💬 $it") }
                Bus.log("→ $name ${JSONObject(inp.toString()).apply { remove("why") }}")
                if (name == "finish" && extras.isNotEmpty()) {
                    ids.add(id); briefs.add("NOT finished: the user just gave new instructions (see Task below). Do them first, then finish."); continue
                }
                if (name == "finish" && inp.optString("evidence").isBlank() && !evWarned) {
                    evWarned = true
                    ids.add(id); briefs.add("Finish rejected: give evidence = what on the CURRENT screen proves done_when ($doneWhen). Check the screen first; if the goal is not reached yet, keep working."); continue
                }
                if (name == "finish" && !finishWarned && openTodos().isNotEmpty()) {
                    finishWarned = true
                    ids.add(id); briefs.add("Not finished: to-do items still open (" + openTodos().joinToString("; ") { it.text } + "). Complete them, or mark them done/skipped with the plan tool if they are no longer needed. Then finish."); continue
                }
                if (name == "finish") {
                    val m = inp.optString("message"); Bus.log("✅ $m"); addNote(task, m); speak(spoken(m)); showResult(m)
                    for ((id2, _, _) in calls) if (id2 !in ids) { ids.add(id2); briefs.add(if (id2 == id) "Finished." else "skipped") }
                    turns.add(Turn(content, ids, briefs, JSONArray().put(text("Finished. Waiting for the user's next message.")), raw, "Finished."))
                    return
                }
                if (name != "scroll") scrollStreak = 0
                val dsc = describe(name, inp)
                statusLine = friendly(name, inp, dsc); refreshOverlay()
                ids.add(id); briefs.add(if (hearth && name == "screenshot") "error: screenshots unavailable" else exec(name, inp)); if (dsc.isNotBlank()) runDid.add(dsc)
            }
            settle()
            stall += 2 * briefs.count { it.startsWith("error") }   // failed actions (bad ids, missing labels) count as lost steps
            val doneNow = todos.count { it.status == "done" }
            if (doneNow > lastDone) { lastDone = doneNow; stall = 0 } else stall++
            if (stall >= 8) escalate("no progress for $stall steps")
            if (!hearth && Usage.taskCost > 0.30) { Bus.log("■ Cost limit (\$0.30) reached for one task"); speak("Gestopt: deze taak werd te duur"); showResult("Stopped: cost limit reached"); return }
            val scr = observe()
            val h = scr.hashCode()
            var warn = ""
            if (h == lastHash) { same++; warn = "\n⚠ The screen did NOT change after your last action. Do not repeat it: pick a different element or approach." + if (same >= 2) " You are stuck: call ask_user now." else ""; if (!hearth) wantImage = true
                if (same >= 2) escalate("stuck")
            } else same = 0
            lastHash = h
            if (!hearth && nodes.size < 3) wantImage = true
            val observation = briefs.last() + warn + "\n" + scr + planText()
            android.util.Log.i("PAobs", observation.take(3500))
            val o = JSONArray().put(text(observation))
            if (wantImage) { wantImage = false; if (!hearth) screenshotBlock()?.let { o.put(it) } }
            turns.add(Turn(content, ids, briefs, o, raw, observation))
        }
        Bus.log("■ Step limit reached")
    }

    /** Wait until the screen stops changing (pages load, animations end), at most ~2 s. */
    private fun settle() {
        Thread.sleep(400)
        var waited = 0
        while (android.os.SystemClock.uptimeMillis() - lastEvent < 350 && waited < 2200) { Thread.sleep(100); waited += 100 }
    }

    /** Hearth has no tool calling: the model answers with a JSON array of actions like [{"tool":"tap","id":3}]. */
    private fun parseActions(s: String): List<JSONObject> {
        val a = s.indexOf('['); val b = s.lastIndexOf(']')
        return try {
            if (a >= 0 && b > a) JSONArray(s.substring(a, b + 1)).let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
            else { val x = s.indexOf('{'); val y = s.lastIndexOf('}'); if (x >= 0 && y > x) listOf(JSONObject(s.substring(x, y + 1))) else emptyList() }
        } catch (e: Exception) { emptyList() }
    }

    /** Thinking blocks are bound to the model + conversation that wrote them; replaying them elsewhere is a 400. We never need them back. */
    private fun stripThinking(a: JSONArray): JSONArray {
        val out = JSONArray()
        for (k in 0 until a.length()) { val b = a.optJSONObject(k) ?: continue; val t = b.optString("type"); if (t != "thinking" && t != "redacted_thinking") out.put(b) }
        return out
    }

    private fun text(s: String) = JSONObject().put("type", "text").put("text", s)

    // ---------- tools ----------
    private fun tool(name: String, desc: String, props: JSONObject = JSONObject(), req: List<String> = emptyList()) =
        JSONObject().put("name", name).put("description", desc)
            .put("input_schema", JSONObject().put("type", "object").put("properties", props.also { if (!it.has("why")) it.put("why", p("string", "one short sentence: how this serves the goal")) }).put("required", JSONArray(req)))

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
        a.put(tool("key", "Press a system key.", JSONObject().put("key", JSONObject().put("type", "string").put("enum", JSONArray(listOf("back", "home", "recents", "notifications", "quick_settings", "lock", "screenshot")))), listOf("key")))
        a.put(tool("wait", "Wait for the screen to load.", JSONObject().put("seconds", p("number"))))
        a.put(tool("screenshot", "Attach a screenshot to the next observation. Costly: use only when the element list is empty or insufficient (camera views, images, games).", JSONObject()))
        val item = JSONObject().put("type", "object").put("properties", JSONObject().put("text", p("string")).put("status", JSONObject().put("type", "string").put("enum", JSONArray(listOf("pending", "doing", "done", "skipped"))))).put("required", JSONArray(listOf("text", "status")))
        a.put(tool("plan", "Set your GOAL and to-do list. Call it FIRST in every task (it is forced on your first turn), and again whenever a step is done/started or the user changes the task. Always send everything.", JSONObject().put("screen_now", p("string", "FIRST look at the screen list: what app and screen is this, and what on it matters for the task? one or two sentences")).put("goal", p("string", "the concrete end result the user wants, one sentence")).put("done_when", p("string", "what exactly must be visible/true on screen to prove the goal is reached")).put("items", JSONObject().put("type", "array").put("items", item)), listOf("screen_now", "goal", "done_when", "items")))
        val hi = JSONObject().put("type", "object").put("properties", JSONObject().put("text", p("string", "visible text of the element, located live")).put("substring", p("string", "exact piece of the text to box only that part")).put("id", p("integer", "element id from the latest list")).put("x", p("integer")).put("y", p("integer")).put("w", p("integer")).put("h", p("integer")).put("label", p("string", "short note shown next to it")))
        a.put(tool("highlight", "Show the user something on their screen: dims the screen and outlines exactly the thing, with an optional label. Use it for 'show me / where is / which one / point out / find' requests or to point at an answer (price, button, message). Each item: text = the visible text of the element (best: located live, always accurate), plus substring = an exact piece of a long text to box only that part (e.g. a price or a name inside a paragraph); or id from the latest list; or raw pixels x,y,w,h. A screenshot is attached afterwards: check the outline, redo if off. Then finish with a short spoken explanation.", JSONObject().put("items", JSONObject().put("type", "array").put("items", hi)).put("seconds", p("integer", "how long to keep it, default 10")), listOf("items")))
        a.put(tool("tap_labels", "Tap several elements in order by their exact visible label/description, resolved live. Use for ANY sequence of buttons: calculator digits and operators (e.g. [\"1\",\"2\",\"multiply\",\"5\",\"equals\"]), keypads, dialers, PIN pads. Far more reliable than tapping ids one by one.", JSONObject().put("labels", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))), listOf("labels")))
        a.put(tool("find_tap", "Find an element by its visible text/description (fuzzy, ignores case/accents), scrolling the page if needed, and tap it. Much faster than scrolling and tapping by hand: use for known buttons, contacts, list items, settings.", JSONObject().put("text", p("string")), listOf("text")))
        a.put(tool("remember", "Save a lasting fact about the user, very short (<= 80 chars), e.g. 'mom = Anna Peeters', 'brother = Lars (Snapchat: lars_v)', 'I am Warre, 17'. Use when the user states a relationship, name, preference or address that will matter later, or says 'remember'. Never save temporary things.", JSONObject().put("fact", p("string")), listOf("fact")))
        a.put(tool("forget", "Delete saved facts containing this text.", JSONObject().put("text", p("string")), listOf("text")))
        a.put(tool("open_url", "Open a link or intent directly: https://..., tel:+32..., sms:+32...?body=..., mailto:, geo:0,0?q=place. Opens the dialer (not an auto-call) for tel:.", JSONObject().put("url", p("string")), listOf("url")))
        a.put(tool("volume", "Set volume 0-100 for stream 'media' (default) or 'ring'.", JSONObject().put("level", p("integer")).put("stream", p("string")), listOf("level")))
        a.put(tool("read_screen", "Return ALL visible text on screen untruncated (use to read long messages/articles or answer questions about the screen).", JSONObject()))
        a.put(tool("remember_name", "Save a person's/place's exact name for future requests. Call it after you successfully picked a recipient/contact whose real name differs from how the user said it.", JSONObject().put("spoken", p("string", "how the user said it")).put("actual", p("string", "exact name as shown in the app")), listOf("actual")))
        a.put(tool("ask_user", "Ask the user a short question out loud and wait for their spoken answer. Use when the request is unclear or doesn't match what's on screen, when several options fit (e.g. two contacts), when something unexpected blocks you (login, captcha, permission), or after two different approaches failed.", JSONObject().put("question", p("string")), listOf("question")))
        val f = tool("finish", "End the task ONLY when the goal is verified done on screen (or truly impossible). message = one short spoken sentence with the result.", JSONObject().put("message", p("string", "short sentence spoken to the user: result or answer")).put("evidence", p("string", "what you saw on screen that proves done_when")), listOf("message", "evidence"))
        f.put("cache_control", JSONObject().put("type", "ephemeral"))
        a.put(f)
        return a
    }

    private fun exec(name: String, i: JSONObject): String {
        return try {
            when (name) {
                "open_app" -> openApp(i.getString("name"))
                "tap" -> nodes.getOrNull(i.getInt("id"))?.let { tapNode(it, rects.getOrNull(i.getInt("id"))) } ?: "error: bad id (ids change after every screen change; use the latest list)"
                "tap_xy" -> tapAt(i.getInt("x"), i.getInt("y"), 50)
                "long_press" -> if (i.has("id")) nodeCenter(i.getInt("id"))?.let { tapAt(it.first, it.second, 800) } ?: "error: bad id"
                    else tapAt(i.getInt("x"), i.getInt("y"), 800)
                "type" -> typeText(i.getString("text"), if (i.has("id")) i.getInt("id") else null, i.optBoolean("submit"))
                "scroll" -> {
                    val m = resources.displayMetrics; val w = m.widthPixels; val h = m.heightPixels
                    if (++scrollStreak > 3) return "error: you scrolled $scrollStreak times in a row without acting. STOP scrolling: re-read the list, say what the screen is and where the target should be, then use a search field, another tab/screen, find_tap or ask_user."
                    val before = sig()
                    val r0 = when (i.getString("direction")) {
                        "down" -> swipe(w / 2, h * 7 / 10, w / 2, h * 3 / 10, 300)
                        "up" -> swipe(w / 2, h * 3 / 10, w / 2, h * 7 / 10, 300)
                        "right" -> swipe(w * 8 / 10, h / 2, w * 2 / 10, h / 2, 300)
                        else -> swipe(w * 2 / 10, h / 2, w * 8 / 10, h / 2, 300)
                    }
                    settle()
                    if (r0 == "ok" && sig() == before) "scrolled but the screen did not change: this is the end of the list (or nothing scrolls here)" else r0
                }
                "swipe" -> swipe(i.getInt("x1"), i.getInt("y1"), i.getInt("x2"), i.getInt("y2"), i.optInt("ms", 300))
                "key" -> {
                    val a = when (i.getString("key")) {
                        "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME
                        "recents" -> GLOBAL_ACTION_RECENTS; "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
                        "lock" -> GLOBAL_ACTION_LOCK_SCREEN; "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT; else -> GLOBAL_ACTION_NOTIFICATIONS
                    }
                    if (performGlobalAction(a)) "ok" else "error: key failed"
                }
                "wait" -> { Thread.sleep((i.optDouble("seconds", 1.0).coerceIn(0.2, 10.0) * 1000).toLong()); "waited" }
                "open_url" -> {
                    val u = android.net.Uri.parse(i.getString("url"))
                    startActivity(Intent(if (u.scheme == "tel") Intent.ACTION_DIAL else Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Thread.sleep(1200); "opened"
                }
                "volume" -> {
                    val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                    val st = if (i.optString("stream", "media") == "ring") android.media.AudioManager.STREAM_RING else android.media.AudioManager.STREAM_MUSIC
                    val max = am.getStreamMaxVolume(st); am.setStreamVolume(st, (i.getInt("level") * max / 100).coerceIn(0, max), android.media.AudioManager.FLAG_SHOW_UI); "volume set"
                }
                "read_screen" -> readScreen()
                "tap_labels" -> tapLabels(i.getJSONArray("labels"))
                "find_tap" -> findTap(i.getString("text"))
                "remember_name" -> {
                    val p = getSharedPreferences("p", MODE_PRIVATE); val ac = i.getString("actual").trim(); val sp = i.optString("spoken").trim()
                    val cur = p.getString("names", "").orEmpty()
                    val e = p.edit()
                    if (ac.isNotEmpty() && !cur.split(",").any { it.trim().equals(ac, true) }) e.putString("names", if (cur.isBlank()) ac else "$cur, $ac")
                    if (sp.isNotEmpty() && !sp.equals(ac, true)) e.putString("aliases", (p.getString("aliases", "").orEmpty() + "\n$sp=$ac").lines().filter { it.isNotBlank() }.takeLast(60).joinToString("\n"))
                    e.apply(); "remembered"
                }
                "remember" -> remember(i.getString("fact"))
                "forget" -> forget(i.getString("text"))
                "plan" -> {
                    if (i.optString("screen_now").isNotBlank()) Bus.log("👁 " + i.optString("screen_now"))
                    if (i.optString("goal").isNotBlank()) goal = i.optString("goal").take(240)
                    if (i.optString("done_when").isNotBlank()) doneWhen = i.optString("done_when").take(240)
                    if (goal.isNotEmpty()) Bus.log("🎯 $goal" + if (doneWhen.isNotEmpty()) "  ✔ $doneWhen" else "")
                    setPlan(i.optJSONArray("items") ?: JSONArray())
                }
                "highlight" -> highlight(i)
                "ask_user" -> askUser(i.getString("question"))
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

    /** Cheap fingerprint of what is on screen (does not touch the ids in `nodes`). */
    private fun sig(): Int {
        var h = 1
        fun walk(n: AccessibilityNodeInfo) {
            if (!n.isVisibleToUser) return
            val r = Rect(); n.getBoundsInScreen(r)
            h = h * 31 + ((n.text ?: n.contentDescription)?.hashCode() ?: 0) * 7 + r.centerX() * 3 + r.centerY() + (if (n.isChecked) 5 else 0) + (if (n.isSelected) 11 else 0)
            for (k in 0 until n.childCount) n.getChild(k)?.let { walk(it) }
        }
        for (w in windows) if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) w.root?.let { if (it.packageName != packageName) walk(it) }
        return h
    }

    /** Reliable tap: scroll into view, real click on the (ancestor) clickable, fall back to a real touch, verify something changed, retry other way. */
    private fun tapNode(n: AccessibilityNodeInfo, snap: Rect?): String {
        val m = resources.displayMetrics; val sw = m.widthPixels; val sh = m.heightPixels
        val b = Rect()
        val fresh = n.refresh()
        n.getBoundsInScreen(b)
        if (!fresh || b.isEmpty) snap?.let { b.set(it) }
        if (b.isEmpty) return "error: element is gone (screen changed), read the new list"
        if (fresh && (b.top < 0 || b.bottom > sh || b.left < 0 || b.right > sw || !n.isVisibleToUser)) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id); Thread.sleep(450)
            n.refresh(); n.getBoundsInScreen(b)
        }
        val vis = Rect(b); if (!vis.intersect(0, 0, sw, sh)) return "error: element is off screen: scroll to it first"
        val cx = vis.centerX(); val cy = vis.centerY()
        val before = sig()
        var c: AccessibilityNodeInfo? = n; var depth = 0
        while (c != null && !c.isClickable && depth < 6) { c = c.parent; depth++ }
        val clicked = fresh && c?.isClickable == true && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var how = if (clicked) "click" else { tapAt(cx, cy, 60); "touch" }
        settle()
        if (sig() == before) {
            // nothing happened: try the other way, then slightly off-centre
            if (how == "click") { tapAt(cx, cy, 60); how = "click+touch" } else { tapAt(vis.left + vis.width() / 3, vis.top + vis.height() / 2, 80); how = "touch+retry" }
            settle()
            if (sig() == before) return "tapped via $how but the screen did NOT change: it may be the wrong element, disabled, or need a long press / different element"
        }
        return "tapped ($how)"
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
        passthrough(true)
        try { return gestureInner(path, ms) } finally { passthrough(false) }
    }

    /** The assistant card at the bottom must never swallow the gesture meant for the app below it. */
    private fun passthrough(on: Boolean) {
        val l = CountDownLatch(1)
        main.post { ov?.touchable(!on); l.countDown() }
        l.await(300, TimeUnit.MILLISECONDS)
        if (on) Thread.sleep(40)
    }

    private fun gestureInner(path: Path, ms: Long): String {
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
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("t", t))
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK); n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, n.text?.length ?: 0) })
            if (!n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return "error: could not type into this field (tap it and try again, or use a screenshot)"
        }
        Thread.sleep(600)
        if (submit) { Thread.sleep(300); n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }
        return "typed"
    }

    // ---------- observation ----------
    private fun observe(): String {
        // Walk every app/popup window (dropdowns, dialogs and menus are separate windows), not just the active one.
        val roots = ArrayList<AccessibilityNodeInfo>()
        var ime = false
        for (w in windows) {
            when (w.type) {
                android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> ime = true
                android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION, android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM ->
                    w.root?.let { if (it.packageName != packageName && (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION || w.isActive || it.packageName != "com.android.systemui")) roots.add(it) }
            }
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
        if (roots.isEmpty()) return "Screen: unavailable (try wait or screenshot)"
        nodes.clear(); rects.clear()
        val m = resources.displayMetrics
        val sb = StringBuilder("Screen ${m.widthPixels}x${m.heightPixels} app=${(rootInActiveWindow ?: roots[0]).packageName}${if (ime) " keyboard=open" else ""}\n")
        fun walk(n: AccessibilityNodeInfo) {
            if (!n.isVisibleToUser) return
            val tx = (n.text ?: "").toString().replace('\n', ' ').take(160)
            val ds = (n.contentDescription ?: "").toString().replace('\n', ' ').take(80)
            val rid = n.viewIdResourceName?.substringAfter(":id/") ?: ""
            if ((n.isClickable || n.isEditable || n.isScrollable || n.isCheckable || tx.isNotEmpty() || ds.isNotEmpty()) && nodes.size < 160) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty) {
                    val cn = n.className?.toString()?.substringAfterLast('.') ?: ""
                    sb.append("[${nodes.size}]${if (cn in plainClasses) "" else " $cn"}")
                    if (tx.isNotEmpty()) sb.append(" \"$tx\"")
                    if (ds.isNotEmpty() && ds != tx) sb.append(" desc=\"$ds\"")
                    if (rid.isNotEmpty() && tx.isEmpty() && ds.isEmpty()) sb.append(" #$rid")
                    sb.append(' ')
                    if (n.isClickable) sb.append('c'); if (n.isEditable) sb.append('e'); if (n.isScrollable) sb.append('s')
                    if (n.isChecked) sb.append('k'); if (n.isFocused) sb.append('f')
                    sb.append(" @${r.centerX()},${r.centerY()}\n")
                    nodes.add(n); rects.add(Rect(r))
                }
            }
            for (k in 0 until n.childCount) n.getChild(k)?.let { walk(it) }
        }
        roots.forEachIndexed { i, r -> if (roots.size > 1) sb.append("-- window ${i + 1}: ${r.packageName}${if (i == 0) " (top: popup/dropdown/dialog if small)" else ""} --\n"); walk(r) }
        if (nodes.size < 3) sb.append("(few elements visible: consider screenshot)\n")
        // identical labels (e.g. several icon buttons all called the same) cannot be told apart by text
        val labels = nodes.mapNotNull { n -> ((n.contentDescription ?: n.text)?.toString()?.takeIf { it.isNotBlank() && it.length < 30 }) }
        labels.groupingBy { it }.eachCount().filter { it.value > 1 && !it.key.all { c -> c.isDigit() } }.keys.take(3).takeIf { it.isNotEmpty() }
            ?.let { sb.append("(several elements share the label ${it.joinToString { l -> "\"$l\"" }}: use position or a screenshot to tell them apart)\n") }
        return sb.toString()
    }

    private fun norm(x: String) = java.text.Normalizer.normalize(x.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ").trim()

    private fun findTap(q: String): String {
        val nq = norm(q)
        if (nq.length < 2) return "error: text too short"
        val m = resources.displayMetrics; val w = m.widthPixels; val h = m.heightPixels
        for (attempt in 0..3) {
            if (stopFlag) return "stopped"
            var best: AccessibilityNodeInfo? = null; var bs = 0; var bl = 0; var count = 0
            fun walk(n: AccessibilityNodeInfo) {
                if (!n.isVisibleToUser) return
                val t = norm(((n.text ?: n.contentDescription) ?: "").toString())
                if (t.isNotEmpty()) {
                    val sc = if (t == nq) 3 else if (t.startsWith(nq)) 2 else if (t.contains(nq)) 1 else 0
                    if (sc > 0) { count++; if (sc > bs || (sc == bs && t.length < bl)) { best = n; bs = sc; bl = t.length } }
                }
                for (k in 0 until n.childCount) n.getChild(k)?.let { walk(it) }
            }
            for (win in windows) if (win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) win.root?.let { if (it.packageName != packageName) walk(it) }
            val hit = best
            if (hit != null) {
                val label = ((hit.text ?: hit.contentDescription) ?: "").toString().take(40)
                var c: AccessibilityNodeInfo? = hit; var depth = 0
                while (c != null && !c.isClickable && depth < 6) { c = c.parent; depth++ }
                val r0 = tapNode(hit, null)
                return (if (r0.startsWith("error")) r0 else "tapped \"$label\"" + r0.removePrefix("tapped")) + if (count > 1) " (${count - 1} other matches on screen)" else ""
            }
            if (attempt == 3 || !anyScrollable()) break      // nothing to scroll (or enough looking): report back instead of scrolling blindly
            swipe(w / 2, h * 7 / 10, w / 2, h * 3 / 10, 300)
            settle()
        }
        return "error: \"$q\" not found on this screen" + (if (anyScrollable()) " nor in the next 3 pages down" else "") + ". Look at the screen list again: use a search field, another tab/screen, other wording, a screenshot or ask_user."
    }

    /** Live lookup of the best element for a text, in every app window. */
    private fun locate(q: String): AccessibilityNodeInfo? {
        val nq = norm(q); if (nq.isEmpty()) return null
        val minScore = if (nq.length < 2) 3 else 1   // single characters (digits) must match exactly
        var best: AccessibilityNodeInfo? = null; var bs = 0; var bl = 0
        fun walk(n: AccessibilityNodeInfo) {
            if (!n.isVisibleToUser) return
            val t = norm(((n.text ?: n.contentDescription) ?: "").toString())
            if (t.isNotEmpty()) {
                val sc = if (t == nq) 3 else if (t.startsWith(nq)) 2 else if (t.contains(nq)) 1 else 0
                if (sc >= minScore && (sc > bs || (sc == bs && sc > 0 && t.length < bl))) { best = n; bs = sc; bl = t.length }
            }
            for (k in 0 until n.childCount) n.getChild(k)?.let { walk(it) }
        }
        for (win in windows) if (win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) win.root?.let { if (it.packageName != packageName) walk(it) }
        return best
    }

    /** Exact on-screen boxes of a substring inside a text node (one box per line of text). */
    @Suppress("DEPRECATION")
    private fun charBoxes(n: AccessibilityNodeInfo, sub: String): List<RectF> {
        val full = (n.text ?: return emptyList()).toString()
        val at = full.indexOf(sub, ignoreCase = true).takeIf { it >= 0 } ?: return emptyList()
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, at)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, sub.length.coerceAtMost(200))
        }
        if (!n.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)) return emptyList()
        val arr = n.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY) ?: return emptyList()
        val lines = ArrayList<RectF>()
        for (o in arr) {
            val r = o as? RectF ?: continue
            if (r.isEmpty) continue
            val cur = lines.lastOrNull()
            if (cur != null && kotlin.math.abs(r.centerY() - cur.centerY()) < cur.height() * 0.6f) cur.union(r) else lines.add(RectF(r))
        }
        return lines
    }

    private fun highlight(i: JSONObject): String {
        val arr = i.optJSONArray("items") ?: return "error: items missing"
        val m = resources.displayMetrics; val screen = RectF(0f, 0f, m.widthPixels.toFloat(), m.heightPixels.toFloat())
        val list = ArrayList<Annotations.Item>(); val notes = ArrayList<String>()
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            val label = o.optString("label")
            val sub = o.optString("substring")
            var node: AccessibilityNodeInfo? = null
            if (o.has("text") && o.optString("text").isNotBlank()) node = locate(o.optString("text"))
            if (node == null && o.has("id")) node = nodes.getOrNull(o.optInt("id"))
            val boxes = ArrayList<RectF>()
            if (node != null) {
                node.refresh()   // positions as they are NOW, not when the list was made
                if (sub.isNotBlank()) boxes.addAll(charBoxes(node, sub))
                if (boxes.isEmpty()) {
                    // a text label inside a clickable row/card: outline the whole row (what the user would tap), unless it is huge
                    var t: AccessibilityNodeInfo = node; var depth = 0
                    while (!t.isClickable && depth < 4) { t = t.parent ?: break; depth++ }
                    val r = Rect(); node.getBoundsInScreen(r)
                    if (t.isClickable) { val rr = Rect(); t.getBoundsInScreen(rr); if (!rr.isEmpty && rr.width().toLong() * rr.height() < m.widthPixels.toLong() * m.heightPixels * 0.4) r.set(rr) }
                    if (!r.isEmpty) boxes.add(RectF(r))
                }
            } else if (o.has("x")) {
                boxes.add(RectF(o.optInt("x").toFloat(), o.optInt("y").toFloat(), (o.optInt("x") + o.optInt("w", 200)).toFloat(), (o.optInt("y") + o.optInt("h", 120)).toFloat()))
            } else notes.add("not found: ${o.optString("text").ifEmpty { "id " + o.optInt("id", -1) }}")
            boxes.forEachIndexed { j, b -> if (b.intersect(screen) && b.width() > 4 && b.height() > 4) list.add(Annotations.Item(b, if (j == 0) label else "")) }
        }
        if (list.isEmpty()) return "error: nothing to highlight (${notes.joinToString("; ")}). Use text:\"visible text\" or fresh ids"
        main.post { (ann ?: Annotations(this).also { ann = it }).show(list, i.optInt("seconds", 10)) }
        if (!wantImageLock) wantImage = true   // look at the result: the next observation carries a screenshot to verify the outline
        return "highlighted ${list.size} box(es)" + if (notes.isEmpty()) "" else "; " + notes.joinToString("; ") + ". Check the screenshot: if an outline is not exactly on the right thing, call highlight again with text/substring."
    }

    private fun spoken(m: String): String { val f = m.trim().split(Regex("(?<=[.!?])\\s+")).first(); return if (f.length <= 170) f else f.take(170) }

    /** Tap several elements by their visible label in order (digits, keypad, dialer): resolved live, so no id mix-ups. */
    private fun tapLabels(arr: JSONArray): String {
        val ok = ArrayList<String>(); val miss = ArrayList<String>()
        for (k in 0 until arr.length()) {
            if (stopFlag) break
            val l = arr.optString(k)
            val n = locate(l)
            if (n == null) { miss.add(l); continue }
            n.refresh()
            var c: AccessibilityNodeInfo? = n; var depth = 0
            while (c != null && !c.isClickable && depth < 6) { c = c.parent; depth++ }
            if (!(c?.isClickable == true && c.performAction(AccessibilityNodeInfo.ACTION_CLICK))) { val r = Rect(); n.getBoundsInScreen(r); tapAt(r.centerX(), r.centerY(), 50) }
            ok.add(l); Thread.sleep(140)
        }
        return "tapped ${ok.size}/${arr.length()}: ${ok.joinToString(" ")}" + if (miss.isEmpty()) "" else "; NOT FOUND: " + miss.joinToString(", ") + " (use the exact label from the list)"
    }

    private fun anyScrollable(): Boolean {
        var found = false
        fun walk(n: AccessibilityNodeInfo) {
            if (found || !n.isVisibleToUser) return
            if (n.isScrollable) { found = true; return }
            for (k in 0 until n.childCount) n.getChild(k)?.let { walk(it) }
        }
        for (win in windows) if (win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) win.root?.let { if (it.packageName != packageName) walk(it) }
        return found
    }

    private fun readScreen(): String {
        val sb = StringBuilder(); var last = ""
        fun walk(n: AccessibilityNodeInfo) {
            if (!n.isVisibleToUser) return
            val t = ((n.text ?: n.contentDescription) ?: "").toString().trim()
            if (t.isNotEmpty() && t != last && sb.length < 6000) { sb.append(t.take(1500)).append('\n'); last = t }
            for (k in 0 until n.childCount) n.getChild(k)?.let { walk(it) }
        }
        for (w in windows) if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) w.root?.let { if (it.packageName != packageName) walk(it) }
        return if (sb.isEmpty()) "(no text on screen)" else sb.toString()
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

private const val RULES = """How to work (follow strictly):
0a. LOOK FIRST. Before every decision read the screen list: which app and screen is this, what is on it, is the target already visible? Say it in "why". Only act on what you actually see. Never scroll or swipe as a first reaction: if the target is not in the list, think where it should be (a search field/icon, a menu, another tab, a different screen, a link via open_url, find_tap) and use that; scroll at most 2-3 times in a row, each time re-reading the new list, and stop as soon as the end of the list is reached.
0. GOAL FIRST. Your first action is always plan: goal = the concrete end result the user wants (one sentence), done_when = what exactly must be visible/true on screen to prove it, items = 2-8 steps. The goal and your list are shown to you every turn. Everything you do must serve the goal: use every tool and trick available (find_tap, open_url, search, scroll, back, ask_user, screenshot...) until the goal is reached. Never stop at a partial result, never say you cannot without trying at least three different approaches. Whenever a step is done, call plan again in the same turn as your next action. Every tool has a "why" field: use it for one short sentence on how the action serves the goal.
1. Before EVERY action write 1-2 short sentences: GOAL (the end result you must reach), what you see now, whether your last action worked, and your next step. Never lose sight of the goal.
2. After every action check the new screen: did it do what you intended? If not, try something different (other element, tap_xy, scroll, back, screenshot). Never repeat a failing action more than twice. Keep trying until the goal is reached; don't give up early.
3a. Questions: answer exactly what was asked from the exact label/row on screen (e.g. "Android version" is not the same as the OS skin version). If the needed row is not visible, open the right sub-screen or scroll to it. Never answer from guesses or memory when the screen can tell you. Do not type calculations into a field unless it is a real input; tap the buttons like a person.
3. Finish only when done_when is verified on the current screen: call finish with evidence = what you saw that proves it. For multi-part requests do every part. If the user asked to be SHOWN or told something (where is X, which one, what does it say, find me...), find it, call highlight on it (ids from the latest list), then finish with a short spoken answer. Do exactly what was asked, never ignore part of a request.
4. Ask the user (ask_user) when the request is unclear or doesn't match what you see (e.g. contact not found, two matches, wrong app), when something unexpected blocks you (login, captcha, permission), or after two failed approaches. Ask short, concrete questions. Don't ask when the request is clear: just do it, without asking for confirmation.
5. Be efficient: fewest steps. For a sequence of buttons (calculator, keypad, dialer) use ONE tap_labels call with the exact labels from the list; never tap digits by guessing ids. Use find_tap{text} when you know the label of what to press, even if it is off-screen or in a list. Prefer open_app over navigating. Prefer tap by id; ids change after every screen change, use only the latest list.
6. The user may interrupt while you work. Lines "User then added (INTERRUPT…): ..." are new instructions: first update the to-do list with plan (add, remove, reorder or replace steps as they ask), then continue with the updated list right away. "Earlier this session" lists previous commands for context.
7. The user may speak Dutch; match app names as installed; write spoken messages (finish, ask_user) in the user's language.
8. Names: the request comes from speech recognition, so names are often misspelled or misheard. Match names on screen approximately (ignore case/accents, similar sounds, first name only, partial text). Search with a short partial name rather than the full one. If exactly one plausible match: use it. If several or none: ask_user and read out the options.
8b. Memory: "About the user" lists saved facts. Use them to resolve "mijn moeder", "mijn broer", "ik" etc. (look up the NAME from the facts in the app). When the user states a new lasting fact (who someone is, a name, preference, address) call remember with a very short fact; if a fact is wrong, forget it and remember the right one. Never save temporary or sensitive things (passwords, codes).
9. Dropdowns, suggestion lists and dialogs appear as an extra "-- window --" section in the list: after typing into a search/recipient field, look there and tap the right entry. Recipient/contact search: type a SHORT distinctive part (3-5 letters of the last name, or the first name), wait for the suggestions, pick the entry that best matches (check class/extra info the user mentioned). If nothing matches try another spelling or the other part of the name (swap first/last, ij/y, ph/f, c/k, double letters), up to 4 attempts, then ask_user. After picking, verify the recipient shows in the field, and call remember_name if the real name differs from what was said.
10. Several elements can share one label (e.g. icon buttons): then use a screenshot, and tap by position. Before an irreversible send/post: verify recipient, subject and text on screen (screenshot if unsure), send, then verify it was sent (screen changed, "verzonden"/sent state). If the user gave no subject, write a short fitting one yourself; don't ask.
11. App notes — Smartschool (be.smartschool.mobile): Berichten → "Nieuw bericht" at the bottom. In the compose screen the three header icons are ALL labeled "Verstuur": left = attach, MIDDLE (paper plane, around x=890,y=200 on a 1080-wide screen) = SEND, right = schedule. Fields: "Aan:" (recipient, shows a suggestions dropdown as "Firstname Lastname - Class"), "Onderwerp" (subject), then the message body (separate field). Recipient format when you type: try the last name or first name part.
12. Follow-ups and corrections: after a command the user may say "no, I meant Jan", "undo that", "also send it to Els", "that's wrong". The section "Earlier commands this session" shows what you did; judge from the current screen what state things are in, then do the new request or fix it (edit, delete/unsend, redo with the correction) and verify. A new message is ALWAYS a new job: do it, never reply that it is already done. Never answer "I can't" before trying: look at the screen and find a way. Short spoken answers: if the user asked a question, put the answer in the finish message (use read_screen to read long texts).
13. Do everything the user says: it is their own phone. Enter details they gave you. Use open_url for calls (dialer), texts, maps and links, key quick_settings for toggles, volume for sound. If an element is missing from the list, use a screenshot and tap_xy. If an app is stuck or crashed: key back/home and reopen it."""

private const val SYSTEM = """You control an Android phone via tools to complete the user's request. Each turn you receive a compact list of on-screen elements: [id] Class "text" desc="..." flags(c=clickable,e=editable,s=scrollable,k=checked,f=focused) @centerX,centerY.
$RULES
Screenshots are expensive: use the screenshot tool only when the list lacks what you need (camera preview, images); one is attached automatically when the screen seems stuck. It is scaled to 540px wide; tap_xy takes real screen pixels (see Screen WxH), so scale coordinates up."""

private const val SYSTEM_TEXT = """You control an Android phone to complete the user's request. Each turn you get a compact list of on-screen elements: [id] Class "text" desc="..." flags(c=clickable,e=editable,s=scrollable,k=checked,f=focused) @centerX,centerY.
Reply with your 1-2 sentence thinking, then a JSON array of actions (nothing after it), e.g.
GOAL: send a snap to Anna. Snapchat isn't open yet. [{"tool":"open_app","name":"Snapchat"}]
Tools:
open_app{name} | tap{id} | tap_xy{x,y} (real screen pixels) | long_press{id or x,y} | type{text,id?,submit?} (replaces field text; submit presses Enter/Send) | scroll{direction: up|down|left|right} (down = see content further down) | swipe{x1,y1,x2,y2,ms?} | key{key: back|home|recents|notifications|quick_settings|lock|screenshot} | wait{seconds} | plan{screen_now,goal,done_when,items:[{text,status: pending|doing|done|skipped}]} (your goal and to-do list; send everything; do this first) | highlight{items:[{id|x,y,w,h, label}],seconds} (point things out to the user on screen) | tap_labels{labels:[...]} (tap a sequence of buttons by label: digits, keypads) | find_tap{text} (finds by text, scrolls if needed, taps) | remember{fact} (short lasting fact about the user) | forget{text} | open_url{url} | volume{level,stream?} | read_screen{} (all visible text) | remember_name{spoken,actual} | ask_user{question} (asks the user out loud, returns their answer) | finish{message,evidence} (only when done_when is verified)
No screenshots are available.
$RULES
Always end your reply with the JSON array of actions."""
