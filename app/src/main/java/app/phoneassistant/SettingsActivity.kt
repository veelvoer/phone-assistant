package app.phoneassistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Settings: a clean list of categories; each opens its own page. */
class SettingsActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var p: android.content.SharedPreferences
    private lateinit var scroll: ScrollView
    private var page = "home"
    private val hooks = ArrayList<() -> Unit>()   // save text fields when leaving a page
    private var tts: TextToSpeech? = null
    private var voiceName: TextView? = null
    private val ESS = 250

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("p", MODE_PRIVATE)
        ui = Ui(this); ui.statusBar(this)
        scroll = ui.page(LinearLayout(this))
        setContentView(scroll)
        tts = TextToSpeech(this) { voiceName?.let { refreshVoice() } }
        open("home")
    }

    // ---------------- navigation ----------------
    private fun open(id: String) {
        runHooks(); hooks.clear(); AgentService.recordKey = null; voiceName = null
        page = id
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(48)) }
        val title = TITLES[id] ?: "Settings"
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.iconButton(R.drawable.ic_back, ui.c.surface, ui.c.onSurface) { back() }.apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = ui.dp(14) })
            addView(ui.tv(title, 28f, ui.c.onSurface, true))
        })
        when (id) {
            "home" -> home(root); "ai" -> aiPage(root); "voice" -> voicePage(root); "shortcut" -> shortcutPage(root); "look" -> lookPage(root)
            "me" -> personalityPage(root); "calls" -> callsPage(root); "memory" -> memoryPage(root); "budget" -> budgetPage(root); "about" -> aboutPage(root)
        }
        scroll.removeAllViews(); scroll.addView(root); scroll.scrollTo(0, 0)
    }

    private fun back() { if (page == "home") finish() else open("home") }
    override fun onBackPressed() { back() }
    override fun onPause() { runHooks(); AgentService.recordKey = null; super.onPause() }
    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
    private fun runHooks() { hooks.toList().forEach { it() } }

    private val TITLES = mapOf("home" to "Settings", "ai" to "AI", "voice" to "Voice", "shortcut" to "Shortcut", "look" to "Appearance", "me" to "Personality", "calls" to "Calls", "memory" to "Memory", "budget" to "Budget", "about" to "About")

    private fun home(root: LinearLayout) {
        fun row(icon: Int, id: String, sub: String) = ui.navRow(icon, TITLES[id]!!, sub) { open(id) }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = ui.shape(ui.c.surface, 28); setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); layoutParams = ui.lp(top = 16) }
        val rows = listOf(
            row(R.drawable.ic_cat_ai, "ai", "Model, key, smart planning"), row(R.drawable.ic_mic, "voice", "Language, recognition quality, voice, volume"),
            row(R.drawable.ic_cat_key, "shortcut", p.getString("sc_label", "Essential Key").orEmpty()), row(R.drawable.ic_cat_look, "look", "Overlay colours, glow, card"),
            row(R.drawable.ic_cat_me, "me", "Name, tone, quiet hours"), row(R.drawable.ic_cat_call, "calls", if (CallManager.enabled(this)) "On" else "Let it answer and talk on calls"),
            row(R.drawable.ic_cat_memory, "memory", "About you, lessons, names"), row(R.drawable.ic_cat_budget, "budget", "Credits and limits"), row(R.drawable.ic_cat_info, "about", "Version v${Updater.current(this)} · updates"))
        rows.forEach { card.addView(it) }
        root.addView(card)
    }

    // ---------------- helpers ----------------
    private fun group(title: String, vararg v: View): Array<View> = arrayOf<View>(ui.sectionTitle(title), ui.card(*v))
    private fun add(root: LinearLayout, vararg groups: Array<View>) { groups.forEach { g -> g.forEach { root.addView(it) } } }
    private fun sub(t: String) = ui.tv(t, 12f, ui.c.onSurfaceV).apply { layoutParams = ui.lp(top = 6) }
    private fun label(t: String) = ui.tv(t, 15f, ui.c.onSurface, true).apply { layoutParams = ui.lp(top = 16) }
    private fun field(hint: String, key: String, def: String = "", multi: Boolean = false, password: Boolean = false, minLines: Int = 0): EditText {
        val e = ui.field(hint, multi, password); if (minLines > 0) e.minLines = minLines; e.setText(p.getString(key, def))
        hooks.add { p.edit().putString(key, e.text.toString().trim()).apply() }; return e
    }
    private fun numField(hint: String, key: String, def: Float): EditText {
        val e = ui.field(hint).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        e.setText(p.getFloat(key, def).let { if (it == 0f) "" else String.format(Locale.US, "%.2f", it) })
        hooks.add { p.edit().putFloat(key, e.text.toString().toFloatOrNull() ?: 0f).apply() }; return e
    }
    private fun bool(title: String, subtitle: String, key: String, def: Boolean, after: (Boolean) -> Unit = {}) =
        ui.toggleRow(title, subtitle, p.getBoolean(key, def)) { p.edit().putBoolean(key, it).apply(); after(it) }
    private fun seg(options: List<String>, key: String, def: Int, after: (Int) -> Unit = {}) = ui.segmented(options, p.getInt(key, def)) { p.edit().putInt(key, it).apply(); after(it) }
    private fun segS(options: List<Pair<String, String>>, key: String, def: String, after: (String) -> Unit = {}) =
        ui.segmented(options.map { it.second }, options.indexOfFirst { it.first == p.getString(key, def) }.coerceAtLeast(0)) { p.edit().putString(key, options[it].first).apply(); after(options[it].first) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun preview() { AgentService.instance?.previewOverlay() ?: toast("Turn on the accessibility service to preview") }

    // ---------------- AI ----------------
    private fun aiPage(root: LinearLayout) {
        var hearth = p.getBoolean("hearth", false)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun build() {
            box.removeAllViews(); runHooks(); hooks.clear()
            val k = field(if (hearth) "Hearth API key" else "Claude API key (sk-ant-…)", if (hearth) "key_h" else "key", password = true)
            val m = field("Model", if (hearth) "model_h" else "model", if (hearth) "haiku" else "claude-haiku-5-5")
            if (hearth) box.addView(field("Hearth server URL (https://…)", "url"))
            box.addView(k); box.addView(m)
            if (!hearth) box.addView(LinearLayout(this).apply {
                layoutParams = ui.lp(top = 10)
                listOf("claude-haiku-5-5" to "Haiku · fast", "claude-sonnet-5-5" to "Sonnet · smart", "claude-opus-5-5" to "Opus · best").forEach { (id, n) -> addView(ui.chip(n) { m.setText(id) }.apply { setSingleLine() }) }
            })
            box.addView(sub(if (hearth) "Use your Hearth server: model haiku, sonnet or opus. Text only, so no screenshots." else "Get a key at console.anthropic.com. Haiku is cheapest and fastest."))
            if (!hearth) box.addView(bool("Smart planning", "A stronger model (Sonnet) writes the plan, the cheap one carries it out", "smartplan", true))
        }
        val provider = ui.segmented(listOf("Claude API", "Hearth"), if (hearth) 1 else 0) { i -> runHooks(); hearth = i == 1; p.edit().putBoolean("hearth", hearth).apply(); build() }
        build()
        add(root, group("Provider", provider, box), group("Limits", label("Max cost per task ($)"), numField("0.30", "cost_cap", 0.30f), sub("It stops a task that costs more than this.")))
    }

    // ---------------- Voice ----------------
    private fun voicePage(root: LinearLayout) {
        val lang = ui.segmented(listOf("Nederlands", "English", "Auto"), listOf("nl-BE", "en-US", "").indexOf(p.getString("lang", "nl-BE")).coerceAtLeast(0)) { i -> p.edit().putString("lang", listOf("nl-BE", "en-US", "")[i]).putString("ttsvoice", "").apply(); refreshVoice() }
        val level = p.getInt("stt_level", 1)
        val levelNote = sub("")
        fun levelText(i: Int) = when (i) { 0 -> "Fast: answers quickly after you stop talking. May cut you off."; 2 -> "Accurate: waits longer, checks more alternatives. Best for names and long sentences."; else -> "Balanced: a good mix of speed and accuracy." }
        levelNote.text = levelText(level)
        val quality = ui.segmented(listOf("Fast", "Balanced", "Accurate"), level) { i -> p.edit().putInt("stt_level", i).apply(); levelNote.text = levelText(i) }
        voiceName = ui.tv("", 13f, ui.c.onSurfaceV).apply { layoutParams = ui.lp(top = 2) }
        add(root,
            group("Speech recognition", ui.tv("Language", 15f, ui.c.onSurface, true), lang, label("Recognition quality"), quality, levelNote,
                bool("Prefer on-device recognition", "Works offline; may be less accurate than Google's online model", "stt_offline", false)),
            group("Spoken answers", bool("Spoken answers", "Silent automatically during calls and quiet hours", "speak", true),
                label("Loudness"), seg(listOf("Normal", "Loud", "Max"), "loud", 1), label("Voice"), voiceName!!,
                LinearLayout(this).apply { layoutParams = ui.lp(top = 12)
                    addView(ui.button("Choose voice") { pickVoice() }.apply { layoutParams = ui.lp(0, -2, weight = 1f, right = 8) })
                    addView(ui.button("Test", filled = true) { AgentService.instance?.testVoice() ?: toast("Enable the service first") }.apply { layoutParams = ui.lp(0, -2, weight = 1f) }) },
                LinearLayout(this).apply { layoutParams = ui.lp(top = 8)
                    addView(ui.button("More voices") { try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (e: Exception) { toast("Not available") } }.apply { layoutParams = ui.lp(0, -2, weight = 1f, right = 8) })
                    addView(ui.button("Engine settings") { try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (e: Exception) { toast("Not available") } }.apply { layoutParams = ui.lp(0, -2, weight = 1f) }) }),
            group("Feedback", bool("Start / done sounds", "A short chime when listening starts and a task finishes", "sounds", true), bool("Haptics", "A tiny buzz when it starts and stops listening", "haptics", true)))
        refreshVoice()
    }

    private fun voiceLocale(): Locale = p.getString("lang", "nl-BE").orEmpty().let { if (it.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(it) }
    private fun voices(): List<android.speech.tts.Voice> = try {
        tts?.voices.orEmpty().filter { it.locale.language == voiceLocale().language && !it.features.orEmpty().contains("notInstalled") }.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.name })
    } catch (e: Exception) { emptyList() }
    private fun vlabel(v: android.speech.tts.Voice): String {
        val q = when { v.quality >= 500 -> "very high"; v.quality >= 400 -> "high"; v.quality >= 300 -> "normal"; else -> "low" }
        return "${v.locale.displayName} · $q" + if (v.isNetworkConnectionRequired) " · online" else " · offline"
    }
    private fun refreshVoice() {
        val t = voiceName ?: return; val chosen = p.getString("ttsvoice", "").orEmpty(); val v = voices().firstOrNull { it.name == chosen }
        t.text = if (v != null) vlabel(v) else "Automatic: the best installed voice (${voices().firstOrNull()?.let { vlabel(it) } ?: "none found"})"
    }
    private fun pickVoice() {
        val vs = voices(); if (vs.isEmpty()) { toast("No voices found: use More voices"); return }
        val items = (listOf("Automatic (best installed)") + vs.map { vlabel(it) }).toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose voice").setItems(items) { _, i -> p.edit().putString("ttsvoice", if (i == 0) "" else vs[i - 1].name).apply(); refreshVoice(); AgentService.instance?.testVoice() }.show()
    }

    // ---------------- Shortcut ----------------
    private fun shortcutPage(root: LinearLayout) {
        val name = ui.tv("", 17f, ui.c.onSurface, true).apply { layoutParams = ui.lp(top = 2) }
        val seen = sub("")
        fun paint() { name.text = p.getString("sc_label", "Essential Key"); seen.text = "Last button the assistant saw: " + AgentService.lastKeySeen.ifEmpty { "none yet" } }
        add(root, group("Wake button", ui.tv("Button that wakes the assistant", 13f, ui.c.onSurfaceV), name,
            ui.segmented(listOf("Press & hold", "Double press", "Single press"), p.getInt("sc_mode", 0)) { i -> p.edit().putInt("sc_mode", i).apply() },
            LinearLayout(this).apply { layoutParams = ui.lp(top = 12)
                addView(ui.button("Press a button…", filled = true) { recordShortcut(::paint) }.apply { layoutParams = ui.lp(0, -2, weight = 1f, right = 8) })
                addView(ui.button("Reset") { p.edit().putInt("sc_scan", ESS).putInt("sc_key", 0).putString("sc_label", "Essential Key").apply(); paint() }.apply { layoutParams = ui.lp(0, -2, weight = 1f) }) },
            seen, sub("Also available without any button: add the “Assistant” tile to your Quick Settings (swipe down twice → pencil icon). “Single press” takes over that button completely.")))
        paint()
    }
    private fun recordShortcut(done: () -> Unit) {
        if (AgentService.instance == null) { toast("Turn on the Assistant accessibility service first"); return }
        val dlg = AlertDialog.Builder(this).setTitle("Press the button").setMessage("Press the button you want to use now (Essential Key, volume, headset button…).").setNegativeButton("Cancel") { _, _ -> AgentService.recordKey = null }.setOnCancelListener { AgentService.recordKey = null }.create()
        AgentService.recordKey = { scan, key ->
            val label = if (scan == ESS) "Essential Key" else KeyEvent.keyCodeToString(key).removePrefix("KEYCODE_").lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } + if (scan > 0) " (button $scan)" else ""
            p.edit().putInt("sc_scan", scan).putInt("sc_key", key).putString("sc_label", label).apply(); dlg.dismiss(); done(); toast("Saved: $label")
        }
        dlg.show()
    }

    // ---------------- Appearance ----------------
    private val PRESETS = listOf(0, 0xFF7AA2F7.toInt(), 0xFF4F6BED.toInt(), 0xFF8B5CF6.toInt(), 0xFFD946EF.toInt(), 0xFFEF4444.toInt(), 0xFFF97316.toInt(), 0xFFEAB308.toInt(), 0xFF22C55E.toInt(), 0xFF14B8A6.toInt(), 0xFF06B6D4.toInt())
    private fun lookPage(root: LinearLayout) {
        fun s(key: String, def: Int, min: Int, max: Int, title: String, fmt: (Int) -> String, subT: String = "") = ui.slider(title, subT, min, max, p.getInt(key, def), fmt) { p.edit().putInt(key, it).apply(); preview() }
        val glowBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun glowOptions() { glowBox.removeAllViews(); if (p.getBoolean("ov_glow", true)) {
            glowBox.addView(label("Thickness")); glowBox.addView(seg(listOf("Thin", "Normal", "Bold"), "ov_gw_i", 1) { i -> p.edit().putInt("ov_gw", i + 1).apply(); preview() })
            glowBox.addView(s("ov_gi", 80, 20, 140, "Brightness", { "$it%" }))
            glowBox.addView(label("Animation speed")); glowBox.addView(seg(listOf("Calm", "Normal", "Lively"), "ov_gs_i", 1) { i -> p.edit().putInt("ov_gs", i + 1).apply(); preview() }) } }
        glowOptions()
        add(root,
            group("Colour", sub("Accent colour of the glow and the card. “A” follows your system colours."), ui.swatches(p.getInt("ov_accent", 0), PRESETS) { p.edit().putInt("ov_accent", it).apply(); preview() }),
            group("Screen glow", bool("Glowing edge", "The animated light around the screen while it works", "ov_glow", true) { glowOptions(); preview() }, glowBox),
            group("Card", label("Position"), seg(listOf("Bottom", "Top"), "ov_pos_i", 0) { i -> p.edit().putString("ov_pos", if (i == 0) "bottom" else "top").apply(); preview() },
                label("Size"), seg(listOf("Compact", "Normal", "Large"), "ov_size", 1) { preview() },
                s("ov_alpha", 94, 40, 100, "Transparency", { "$it% solid" }, "Lower = see-through"), s("ov_radius", 28, 8, 40, "Corner roundness", { "$it dp" }),
                bool("Voice bars", "", "ov_bars", true) { preview() }, bool("Plan progress bar", "", "ov_prog", true) { preview() },
                ui.slider("Show results for", "", 2, 15, p.getFloat("ov_hold", 4.5f).toInt(), { "$it s" }) { p.edit().putFloat("ov_hold", it.toFloat()).apply() }),
            arrayOf<View>(ui.button("Preview", filled = true) { preview() }.apply { layoutParams = ui.lp(top = 18) }))
    }

    // ---------------- Personality ----------------
    private fun personalityPage(root: LinearLayout) {
        fun minutes(key: String, def: Int) = p.getInt(key, def).let { String.format(Locale.US, "%02d:%02d", it / 60, it % 60) }
        val quiet = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun quietBox() { quiet.removeAllViews(); if (p.getBoolean("q_on", false)) quiet.addView(LinearLayout(this).apply { layoutParams = ui.lp(top = 10)
            for ((key, def, lab) in listOf(Triple("q_from", 22 * 60, "From"), Triple("q_to", 8 * 60, "Until"))) {
                val b = ui.button("$lab ${minutes(key, def)}") { }
                b.setOnClickListener { val cur = p.getInt(key, def); TimePickerDialog(this@SettingsActivity, { _, h, m -> p.edit().putInt(key, h * 60 + m).apply(); ui.setLabel(b, "$lab ${minutes(key, def)}") }, cur / 60, cur % 60, true).show() }
                b.layoutParams = ui.lp(0, -2, weight = 1f, right = 8); addView(b)
            } }) }
        quietBox()
        add(root,
            group("Character", label("Its name"), field("Assistant", "ai_name", "Assistant"), sub("It uses this when it introduces itself, also on calls."),
                label("Tone"), segS(listOf("neutral" to "Neutral", "friendly" to "Friendly", "concise" to "Concise", "playful" to "Playful"), "ai_tone", "friendly"),
                label("Spoken reply length"), segS(listOf("short" to "Short", "normal" to "Normal", "detailed" to "Detailed"), "ai_len", "short")),
            group("Quiet hours", bool("Stay quiet at night", "No spoken answers or sounds, and calls get a text instead of a talk", "q_on", false) { quietBox() }, quiet))
    }

    // ---------------- Calls ----------------
    private val VOIP = listOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca", "com.microsoft.teams", "com.skype.raider", "com.discord", "com.viber.voip", "com.google.android.apps.tachyon", "com.snapchat.android")
    private fun modeName(m: String) = when (m) { "speaker" -> "Talks to the caller (loudspeaker)"; "text" -> "Silent text reply"; else -> "Off" }
    private fun appLabel(pkg: String): String = if (pkg == CallManager.PHONE) "Phone (regular calls)" else try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }

    private fun callsPage(root: LinearLayout) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun build() {
            body.removeAllViews(); runHooks(); hooks.clear()
            val on = CallManager.enabled(this)
            body.addView(ui.card(ui.toggleRow("Call assistant", "Let the assistant handle calls you don't pick up", on) { v ->
                p.edit().putBoolean("call_on", v).apply()
                if (v) { askCallPermissions(); CallManager.watch(this) }
                build()
            }, sub("It always starts by saying it's your AI assistant. Experimental: Android doesn't give apps access to call audio, so a conversation works over the loudspeaker and the microphone and is not silent.")))
            if (!on) return
            val missing = CallManager.missingPermissions(this)
            val status = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun statusRow(ok: Boolean, text: String, actionLabel: String, action: () -> Unit) = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; layoutParams = ui.lp(top = 10)
                addView(ui.tv(if (ok) "✓" else "!", 16f, if (ok) ui.c.ok else ui.c.error, true).apply { layoutParams = ui.lp(ui.dp(22), -2) })
                addView(ui.tv(text, 14f, ui.c.onSurface).apply { layoutParams = ui.lp(0, -2, weight = 1f) })
                if (!ok) addView(ui.tv(actionLabel, 14f, ui.c.primary, true).apply { setPadding(ui.dp(10), ui.dp(8), ui.dp(4), ui.dp(8)); setOnClickListener { action() } })
            }
            status.addView(statusRow(missing.isEmpty(), "Phone, SMS, call log and microphone permissions", "Allow") { askCallPermissions() })
            status.addView(statusRow(CallManager.notificationAccess(this), "Notification access (to see WhatsApp, Telegram… calls)", "Open") { try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (e: Exception) { toast("Not available") } })
            body.addView(ui.sectionTitle("Status")); body.addView(ui.card(status))
            body.addView(ui.sectionTitle("When it should step in"))
            val delay = ui.slider("Answer after", "How long it waits for you to pick up first", 5, 40, p.getInt("call_delay", 20), { "$it s" }) { p.edit().putInt("call_delay", it).apply() }
            val apps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun listApps() {
                apps.removeAllViews()
                val modes = CallManager.modes(this)
                val pkgs = LinkedHashSet<String>(); pkgs.add(CallManager.PHONE)
                VOIP.filter { try { packageManager.getPackageInfo(it, 0); true } catch (e: Exception) { false } }.forEach { pkgs.add(it) }; pkgs.addAll(modes.keys)
                for (pkg in pkgs) apps.addView(ui.navRow(R.drawable.ic_cat_call, appLabel(pkg), modeName(modes[pkg] ?: "off")) { chooseMode(pkg) { listApps() } })
                apps.addView(ui.button("Add another app…") { addApp { listApps() } }.apply { layoutParams = ui.lp(top = 8) })
            }
            listApps()
            body.addView(ui.card(delay, label("Per app"), apps))
            body.addView(ui.sectionTitle("What it says"))
            body.addView(ui.card(label("Your name for callers"), field("e.g. Warre", "call_owner"), sub("“Hello, I'm Assistant, Warre's AI assistant.” Empty = “my owner”."),
                label("What it should do"), field("Ask who is calling and why, take a message and say I'll call back.", "call_rules", multi = true, minLines = 3),
                label("Text reply message"), field("Hi, this is Assistant. I can't pick up right now…", "call_text", multi = true, minLines = 3), sub("Sent as an SMS (or in the app chat) when you pick “Silent text reply”. Empty = a friendly default.")))
            val recent = CallManager.recent(this)
            body.addView(ui.sectionTitle("Recently handled"))
            body.addView(ui.card(*(if (recent.isEmpty()) listOf<View>(sub("Nothing yet.")) else recent.take(8).map { (t, who, what) ->
                LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = ui.lp(top = 10); addView(ui.tv(who + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t)), 12f, ui.c.onSurfaceV)); addView(ui.tv(what, 14f, ui.c.onSurface).apply { layoutParams = ui.lp(top = 2) }) }
            }).toTypedArray()))
        }
        root.addView(body); build()
    }

    private fun chooseMode(pkg: String, done: () -> Unit) {
        val opts = arrayOf("Off", "Talk to the caller (loudspeaker)", "Silent text reply"); val vals = arrayOf("off", "speaker", "text")
        AlertDialog.Builder(this).setTitle(appLabel(pkg)).setSingleChoiceItems(opts, vals.indexOf(CallManager.modes(this)[pkg] ?: "off").coerceAtLeast(0)) { d, i -> CallManager.setMode(this, pkg, vals[i]); d.dismiss(); done() }.setNegativeButton("Cancel", null).show()
    }

    private fun addApp(done: () -> Unit) {
        val launch = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }.distinctBy { it.first }.filter { it.first != packageName }.sortedBy { it.second.lowercase() }
        AlertDialog.Builder(this).setTitle("Choose an app").setItems(launch.map { it.second }.toTypedArray()) { _, i -> chooseMode(launch[i].first, done) }.show()
    }

    private fun askCallPermissions() {
        val need = CallManager.missingPermissions(this).toMutableList()
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 21)
    }
    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) { super.onRequestPermissionsResult(req, perms, res); CallManager.watch(this); if (page == "calls") open("calls") }
    override fun onResume() { super.onResume(); if (page == "calls") open("calls") }

    // ---------------- Memory / Budget / About ----------------
    private fun memoryPage(root: LinearLayout) {
        add(root, group("About you", sub("One short fact per line: who you are, family, friends. Sent with every task so it knows who “my mom” is. You can also say “onthoud dat …”."),
            field("I am Warre\nmom = Anna Peeters\nbrother = Lars", "facts", multi = true, minLines = 5)),
            group("Lessons from your corrections", sub("When you say “nee, ik bedoelde …” it saves a rule here so it doesn't repeat the mistake. Edit or delete lines freely."), field("Nothing learned yet", "lessons", multi = true, minLines = 3)),
            group("Speech recognition", sub("Names and words you use; they help it hear them correctly."), field("Jan, Sanne, Wout", "names")))
    }

    private fun budgetPage(root: LinearLayout) {
        val bal = ui.field("Credit balance in $").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; Usage.left(this@SettingsActivity)?.let { setText(String.format(Locale.US, "%.2f", it)) } }
        hooks.add { bal.text.toString().trim().toFloatOrNull()?.let { v -> if (kotlin.math.abs(v - (Usage.left(this) ?: -1f)) > 0.004f) Usage.setBalance(this, v) } }
        add(root, group("Credits", sub("Anthropic doesn't let apps read your balance. Enter it once (console.anthropic.com → Billing) and the app subtracts what it spends."), bal),
            group("Limits", label("Daily limit ($)"), numField("0 = no limit", "daily_limit", 0f), sub("It stops starting tasks once today's spending reaches this."), label("Max cost per task ($)"), numField("0.30", "cost_cap", 0.30f)))
    }

    private fun aboutPage(root: LinearLayout) {
        val status = sub(Updater.message.ifEmpty { "Version ${Updater.current(this)}" })
        add(root, group("Assistant", ui.tv("Version ${Updater.current(this)}", 16f, ui.c.onSurface, true), status,
            ui.button("Check for updates") { Updater.check(this, true); Bus.listener = { runOnUiThread { status.text = if (Updater.rel != null) "New version ${Updater.rel!!.version} available. Open the main screen to install." else Updater.message.ifEmpty { "Version ${Updater.current(this)}" } } } }.apply { layoutParams = ui.lp(top = 12) }))
    }
}
