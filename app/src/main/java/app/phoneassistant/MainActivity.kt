package app.phoneassistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private val night by lazy { resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
    private val bg by lazy { if (night) 0xFF16130F.toInt() else 0xFFFAF7F2.toInt() }
    private val card by lazy { if (night) 0xFF221E19.toInt() else 0xFFFFFFFF.toInt() }
    private val fg by lazy { if (night) 0xFFF2EDE6.toInt() else 0xFF1F1B16.toInt() }
    private val sub by lazy { if (night) 0xFFA39A8E.toInt() else 0xFF6B645B.toInt() }
    private val accent by lazy { if (night) 0xFFF08A52.toInt() else 0xFFD9622B.toInt() }
    private val line by lazy { if (night) 0xFF3A342C.toInt() else 0xFFE6DFD4.toInt() }
    private val danger = 0xFFC62828.toInt()
    private val ok = 0xFF2E7D32.toInt()

    private lateinit var p: android.content.SharedPreferences
    private var hearth = false
    private var settingsOpen = false

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var updateCard: LinearLayout
    private lateinit var updateTitle: TextView
    private lateinit var updateNotes: TextView
    private lateinit var updateBtn: Button
    private lateinit var setupCard: LinearLayout
    private lateinit var task: EditText
    private lateinit var runBtn: Button
    private lateinit var settingsBody: LinearLayout
    private lateinit var settingsArrow: TextView
    private lateinit var segClaude: Button
    private lateinit var segHearth: Button
    private lateinit var url: EditText
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var keyHint: TextView
    private lateinit var names: EditText
    private lateinit var langBtns: List<Pair<String, Button>>
    private lateinit var logCard: LinearLayout
    private lateinit var log: TextView
    private lateinit var updateStatus: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bgOf(color: Int, r: Int = 14, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun tv(t: String, size: Float = 14f, color: Int = fg, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun lp(w: Int = -1, h: Int = -2, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private fun button(label: String, filled: Boolean = false, f: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; stateListAnimator = null
        setTextColor(if (filled) 0xFFFFFFFF.toInt() else accent)
        background = if (filled) bgOf(accent, 12) else bgOf(0x00000000, 12, accent)
        setPadding(dp(14), dp(10), dp(14), dp(10)); minHeight = 0; minimumHeight = dp(44)
        setOnClickListener { f() }
    }

    private fun input(hint: String, multi: Boolean = false) = EditText(this).apply {
        this.hint = hint; textSize = 15f; setTextColor(fg); setHintTextColor(sub)
        background = bgOf(if (night) 0xFF2B2620.toInt() else 0xFFF4EFE7.toInt(), 12, line)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        if (multi) { minLines = 3; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES } else setSingleLine()
    }

    private fun cardView(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = bgOf(card, 18, line); setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = lp(top = 14); v.forEach { addView(it) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("p", MODE_PRIVATE)
        hearth = p.getBoolean("hearth", false)
        window.statusBarColor = bg; window.navigationBarColor = bg
        if (!night) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(24), dp(16), dp(24)) }

        // header
        statusDot = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8); gravity = Gravity.CENTER_VERTICAL } }
        statusText = tv("", 13f, sub)
        root.addView(tv("Phone Assistant", 28f, fg, true))
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; layoutParams = lp(top = 4)
            addView(statusDot); addView(statusText)
            addView(tv("  ·  v${Updater.current(this@MainActivity)}", 13f, sub))
        })

        // update card
        updateTitle = tv("", 16f, fg, true)
        updateNotes = tv("", 13f, sub).apply { layoutParams = lp(top = 6) }
        updateBtn = button("Update now", true) { Updater.install(this) }.apply { layoutParams = lp(top = 12) }
        updateCard = cardView(updateTitle, updateNotes, updateBtn).apply { background = bgOf(card, 18, accent) }
        root.addView(updateCard)

        // setup card
        setupCard = cardView(
            tv("Set up (one time)", 17f, fg, true),
            tv("Android blocks sideloaded apps from using accessibility until you allow it.", 13f, sub).apply { layoutParams = lp(top = 4) },
            tv("1. Allow restricted settings", 14f, fg, true).apply { layoutParams = lp(top = 14) },
            tv("Open App info, tap ⋮ (top right) and choose “Allow restricted settings”. If ⋮ is missing, do step 2 first and tap the greyed-out switch once.", 13f, sub),
            button("Open App info") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }.apply { layoutParams = lp(top = 8) },
            tv("2. Turn on the service", 14f, fg, true).apply { layoutParams = lp(top = 14) },
            tv("Accessibility → Installed apps → Phone Assistant → On.", 13f, sub),
            button("Open Accessibility settings", true) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.apply { layoutParams = lp(top = 8) }
        )
        root.addView(setupCard)

        // task card
        task = input("What should I do on your phone?", true)
        val chips = LinearLayout(this).apply { layoutParams = lp(top = 10) }
        listOf("Open Snapchat and reply to the newest snap", "Turn on Wi-Fi", "Open YouTube and search lofi").forEach { ex ->
            chips.addView(tv(ex, 12f, accent).apply {
                background = bgOf(0x00000000, 20, accent); setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
                setOnClickListener { task.setText(ex); task.setSelection(ex.length) }
            })
        }
        runBtn = button("Run", true) { onRun() }.apply { layoutParams = lp(0, -2, 0, 1f) }
        val mic = button("🎤 Speak") {
            try { startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).apply { p.getString("lang", "nl-BE")?.takeIf { it.isNotEmpty() }?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) } }, 1) }
            catch (e: Exception) { Toast.makeText(this, "No speech recognizer on this phone", Toast.LENGTH_SHORT).show() }
        }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) } }
        root.addView(cardView(
            tv("Task", 17f, fg, true), tv("Or press and hold the Essential Key: it keeps listening and does everything you say. Say “stop” or tap the overlay to end.", 12f, sub).apply { layoutParams = lp(top = 2) }, task.apply { layoutParams = lp(top = 10) },
            HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips); layoutParams = lp() },
            LinearLayout(this).apply { layoutParams = lp(top = 12); addView(mic); addView(runBtn) }
        ))

        // settings card
        segClaude = button("Claude API") { setProvider(false) }.apply { layoutParams = lp(0, -2, 0, 1f) }
        segHearth = button("Hearth") { setProvider(true) }.apply { layoutParams = lp(0, -2, 0, 1f) }
        url = input("Hearth server URL (https://…)").apply { layoutParams = lp(top = 10) }
        key = input("API key").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; layoutParams = lp(top = 10) }
        model = input("Model").apply { layoutParams = lp(top = 10) }
        keyHint = tv("", 12f, sub).apply { layoutParams = lp(top = 8) }
        updateStatus = tv("", 12f, sub).apply { layoutParams = lp(top = 14) }
        langBtns = listOf("nl-BE" to "Nederlands", "en-US" to "English", "" to "Auto").map { (c, n) ->
            c to button(n) { p.edit().putString("lang", c).apply(); render() }.apply { layoutParams = lp(0, -2, 0, 1f) }
        }
        val langRow = LinearLayout(this).apply {
            layoutParams = lp(top = 10)
            langBtns.forEachIndexed { i, (_, b) -> if (i > 0) addView(View(this@MainActivity).apply { layoutParams = lp(dp(8), 1) }); addView(b) }
        }
        names = input("Names & words you use, comma separated (friends, places…)").apply { layoutParams = lp(top = 6); setText(p.getString("names", "")) }
        settingsBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = lp(top = 12)
            addView(LinearLayout(this@MainActivity).apply { addView(segClaude); addView(View(this@MainActivity).apply { layoutParams = lp(dp(8), 1) }); addView(segHearth) })
            addView(url); addView(key); addView(model); addView(keyHint)
            addView(tv("Voice language", 13f, fg, true).apply { layoutParams = lp(top = 14) }); addView(langRow)
            addView(tv("Names it should recognise", 13f, fg, true).apply { layoutParams = lp(top = 14) }); addView(names)
            addView(tv("Helps speech recognition and matching with friends/contacts, e.g. Jan, Sanne, Wout.", 12f, sub).apply { layoutParams = lp(top = 4) })
            addView(updateStatus)
            addView(button("Check for updates") { Updater.check(this@MainActivity, true) }.apply { layoutParams = lp(top = 6) })
        }
        settingsArrow = tv("▾", 18f, sub)
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(tv("Settings", 17f, fg, true).apply { layoutParams = lp(0, -2, 0, 1f) }); addView(settingsArrow)
            setOnClickListener { settingsOpen = !settingsOpen; render() }
        }
        root.addView(cardView(head, settingsBody))

        // activity log
        log = tv("", 12f, sub).apply { typeface = Typeface.MONOSPACE }
        logCard = cardView(
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(tv("Activity", 17f, fg, true).apply { layoutParams = lp(0, -2, 0, 1f) })
                addView(tv("Clear", 13f, accent).apply { setOnClickListener { synchronized(Bus.lines) { Bus.lines.clear() }; render() } })
            },
            log.apply { layoutParams = lp(top = 8) }
        )
        root.addView(logCard)

        setContentView(ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true; addView(root) })

        loadProvider()
        settingsOpen = key.text.isEmpty()
    }

    private fun loadProvider() {
        key.setText(p.getString(if (hearth) "key_h" else "key", ""))
        model.setText(p.getString(if (hearth) "model_h" else "model", if (hearth) "haiku" else "claude-haiku-5-5"))
        url.setText(p.getString("url", ""))
    }

    private fun saveProvider() {
        p.edit().putBoolean("hearth", hearth).putString(if (hearth) "key_h" else "key", key.text.toString().trim())
            .putString(if (hearth) "model_h" else "model", model.text.toString().trim()).putString("url", url.text.toString().trim()).putString("names", names.text.toString().trim()).apply()
    }

    private fun setProvider(h: Boolean) {
        if (h == hearth) return
        saveProvider(); hearth = h; loadProvider(); render()
    }

    private fun onRun() {
        val svc = AgentService.instance
        if (svc?.running == true) { svc.stop(); return }
        if (svc == null) { Toast.makeText(this, "Finish the setup first", Toast.LENGTH_LONG).show(); return }
        val t = task.text.toString().trim()
        if (key.text.isBlank()) { settingsOpen = true; render(); Toast.makeText(this, "Add your API key in Settings", Toast.LENGTH_LONG).show(); return }
        if (hearth && url.text.isBlank()) { settingsOpen = true; render(); Toast.makeText(this, "Add your Hearth server URL", Toast.LENGTH_LONG).show(); return }
        if (t.isEmpty()) { Toast.makeText(this, "Describe a task first", Toast.LENGTH_SHORT).show(); return }
        saveProvider()
        val u = url.text.toString().trim()
        if (svc.start(t, AgentService.Cfg(hearth, if (hearth) u else "", key.text.toString().trim(), model.text.toString().trim()))) moveTaskToBack(true)
    }

    private fun render() {
        val svc = AgentService.instance
        val running = svc?.running == true
        statusDot.background = bgOf(if (svc == null) danger else if (running) accent else ok, 5)
        statusText.text = if (svc == null) "Setup needed" else if (running) "Working…" else "Ready"
        setupCard.visibility = if (svc == null) View.VISIBLE else View.GONE

        val r = Updater.rel
        updateCard.visibility = if (r != null) View.VISIBLE else View.GONE
        if (r != null) {
            updateTitle.text = "Update available: v${r.version}"
            updateNotes.text = r.notes.lines().map { it.trim().replace(Regex("^[#*\\-\\s]+"), "• ") }.filter { it.length > 2 }.take(5).joinToString("\n")
            updateNotes.visibility = if (updateNotes.text.isEmpty()) View.GONE else View.VISIBLE
            updateBtn.isEnabled = !Updater.busy
            updateBtn.text = if (Updater.progress >= 0) "${Updater.message} ${Updater.progress}%" else if (Updater.busy) Updater.message else "Update now"
        }
        updateStatus.text = if (r == null) Updater.message else ""

        runBtn.text = if (running) "Stop" else "Run"
        runBtn.background = bgOf(if (running) danger else accent, 12)

        settingsBody.visibility = if (settingsOpen) View.VISIBLE else View.GONE
        settingsArrow.text = if (settingsOpen) "▴" else "▾"
        fun seg(b: Button, on: Boolean) { b.setTextColor(if (on) 0xFFFFFFFF.toInt() else accent); b.background = if (on) bgOf(accent, 12) else bgOf(0x00000000, 12, accent) }
        seg(segClaude, !hearth); seg(segHearth, hearth)
        val lang = p.getString("lang", "nl-BE")
        langBtns.forEach { (c, b) -> seg(b, c == lang) }
        url.visibility = if (hearth) View.VISIBLE else View.GONE
        key.hint = if (hearth) "Hearth API key" else "Claude API key (sk-ant-…)"
        keyHint.text = if (hearth) "Don't have a Claude key? Use your Hearth server: model haiku, sonnet or opus. Text-only, so no screenshots." else "Get a key at console.anthropic.com. Haiku is cheapest and fastest."

        val t = Bus.text()
        logCard.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
        log.text = t
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { task.setText(it) }
    }

    override fun onResume() {
        super.onResume()
        Bus.listener = { runOnUiThread { render() } }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2)
        render(); Updater.check(this)
    }

    override fun onPause() { super.onPause(); saveProvider(); Bus.listener = null }
}
