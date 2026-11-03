package app.phoneassistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale

class SettingsActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var p: android.content.SharedPreferences
    private var hearth = false
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var url: EditText
    private lateinit var names: EditText
    private lateinit var memory: EditText
    private lateinit var balance: EditText
    private lateinit var keyHint: TextView
    private lateinit var voiceName: TextView
    private lateinit var updateStatus: TextView
    private var tts: TextToSpeech? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("p", MODE_PRIVATE)
        ui = Ui(this); ui.statusBar(this)
        hearth = p.getBoolean("hearth", false)
        val c = ui.c
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(40)) }

        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.iconButton(R.drawable.ic_back, c.surface, c.onSurface) { finish() }.apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = ui.dp(14) })
            addView(ui.tv("Settings", 28f, c.onSurface, true))
        })

        // ---- AI
        root.addView(ui.sectionTitle("AI"))
        key = ui.field("API key", password = true); model = ui.field("Model"); url = ui.field("Hearth server URL (https://…)")
        keyHint = ui.tv("", 12f, c.onSurfaceV).apply { layoutParams = ui.lp(top = 8) }
        val modelChips = LinearLayout(this).apply {
            layoutParams = ui.lp(top = 10)
            listOf("claude-haiku-5-5" to "Haiku · fast", "claude-sonnet-5-5" to "Sonnet · smart", "claude-opus-5-5" to "Opus · best").forEach { (id, n) -> addView(ui.chip(n) { if (!hearth) model.setText(id) }.apply { setSingleLine(); (layoutParams as LinearLayout.LayoutParams).rightMargin = ui.dp(6) }) }
        }
        val providerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun load() {
            key.setText(p.getString(if (hearth) "key_h" else "key", "")); model.setText(p.getString(if (hearth) "model_h" else "model", if (hearth) "haiku" else "claude-haiku-5-5")); url.setText(p.getString("url", ""))
            url.visibility = if (hearth) View.VISIBLE else View.GONE; modelChips.visibility = if (hearth) View.GONE else View.VISIBLE
            key.hint = if (hearth) "Hearth API key" else "Claude API key (sk-ant-…)"
            keyHint.text = if (hearth) "Use your Hearth server: model haiku, sonnet or opus. Text only, no screenshots." else "Get a key at console.anthropic.com. Haiku is cheapest and fastest."
        }
        val seg = ui.segmented(listOf("Claude API", "Hearth"), if (hearth) 1 else 0) { i -> save(); hearth = i == 1; p.edit().putBoolean("hearth", hearth).apply(); load() }
        providerBox.addView(seg); providerBox.addView(url); providerBox.addView(key); providerBox.addView(model); providerBox.addView(modelChips); providerBox.addView(keyHint)
        providerBox.addView(ui.toggleRow("Smart planning", "A stronger model (Sonnet) writes the plan, the cheap one carries it out", p.getBoolean("smartplan", true)) { p.edit().putBoolean("smartplan", it).apply() })
        root.addView(ui.card(providerBox)); load()

        // ---- Voice
        root.addView(ui.sectionTitle("Voice"))
        val langIdx = listOf("nl-BE", "en-US", "").indexOf(p.getString("lang", "nl-BE")).coerceAtLeast(0)
        voiceName = ui.tv("", 13f, c.onSurfaceV).apply { layoutParams = ui.lp(top = 2) }
        val voiceCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voiceCard.addView(ui.tv("Language", 15f, c.onSurface, true))
        voiceCard.addView(ui.segmented(listOf("Nederlands", "English", "Auto"), langIdx) { i -> p.edit().putString("lang", listOf("nl-BE", "en-US", "")[i]).putString("ttsvoice", "").apply(); refreshVoice() })
        voiceCard.addView(ui.toggleRow("Spoken answers", "Silent automatically during calls", p.getBoolean("speak", true)) { p.edit().putBoolean("speak", it).apply() })
        voiceCard.addView(ui.tv("Loudness", 15f, c.onSurface, true).apply { layoutParams = ui.lp(top = 16) })
        voiceCard.addView(ui.segmented(listOf("Normal", "Loud", "Max"), p.getInt("loud", 1)) { i -> p.edit().putInt("loud", i).apply() })
        voiceCard.addView(ui.tv("Voice", 15f, c.onSurface, true).apply { layoutParams = ui.lp(top = 16) }); voiceCard.addView(voiceName)
        voiceCard.addView(LinearLayout(this).apply { layoutParams = ui.lp(top = 12)
            addView(ui.button("Choose voice") { pickVoice() }.apply { layoutParams = ui.lp(0, -2, weight = 1f, right = 8) })
            addView(ui.button("Test", filled = true) { AgentService.instance?.testVoice() ?: Toast.makeText(this@SettingsActivity, "Enable the service first", Toast.LENGTH_SHORT).show() }.apply { layoutParams = ui.lp(0, -2, weight = 1f) }) })
        voiceCard.addView(LinearLayout(this).apply { layoutParams = ui.lp(top = 8)
            addView(ui.button("More voices") { try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (e: Exception) { toast("Not available") } }.apply { layoutParams = ui.lp(0, -2, weight = 1f, right = 8) })
            addView(ui.button("Engine settings") { try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (e: Exception) { toast("Not available") } }.apply { layoutParams = ui.lp(0, -2, weight = 1f) }) })
        root.addView(ui.card(voiceCard))
        tts = TextToSpeech(this) { refreshVoice() }

        // ---- Shortcut
        root.addView(ui.sectionTitle("Shortcut"))
        val scName = ui.tv("", 17f, c.onSurface, true)
        val scSeen = ui.tv("", 12f, c.onSurfaceV).apply { layoutParams = ui.lp(top = 6) }
        fun scLabel() = p.getString("sc_label", "Essential Key")
        fun paintSc() { scName.text = scLabel(); scSeen.text = "Last button the assistant saw: " + (AgentService.lastKeySeen.ifEmpty { "none yet" }) }
        val scCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scCard.addView(ui.tv("Button that wakes the assistant", 13f, c.onSurfaceV)); scCard.addView(scName.apply { layoutParams = ui.lp(top = 2) })
        scCard.addView(ui.segmented(listOf("Press & hold", "Double press", "Single press"), p.getInt("sc_mode", 0)) { i -> p.edit().putInt("sc_mode", i).apply() })
        scCard.addView(LinearLayout(this).apply { layoutParams = ui.lp(top = 12)
            addView(ui.button("Press a button…", filled = true) { recordShortcut(::paintSc) }.apply { layoutParams = ui.lp(0, -2, weight = 1f, right = 8) })
            addView(ui.button("Reset") { p.edit().putInt("sc_scan", 250).putInt("sc_key", 0).putString("sc_label", "Essential Key").apply(); paintSc() }.apply { layoutParams = ui.lp(0, -2, weight = 1f) }) })
        scCard.addView(scSeen)
        scCard.addView(ui.tv("Also available without any button: add the “Assistant” tile to your Quick Settings (swipe down twice → pencil icon). “Single press” takes over that button completely.", 12f, c.onSurfaceV).apply { layoutParams = ui.lp(top = 10) })
        root.addView(ui.card(scCard)); paintSc()

        // ---- Memory
        root.addView(ui.sectionTitle("Memory"))
        memory = ui.field("e.g.\nI am Warre\nmom = Anna Peeters\nbrother = Lars (Snapchat: lars_v)", multi = true).apply { minLines = 5; setText(p.getString("facts", "")) }
        names = ui.field("Names & words you use, comma separated").apply { setText(p.getString("names", "")) }
        root.addView(ui.card(
            ui.tv("About you", 15f, c.onSurface, true),
            ui.tv("One short fact per line: who you are, family, friends. Sent with every task so it knows who “my mom” is. You can also say “onthoud dat …”.", 12f, c.onSurfaceV).apply { layoutParams = ui.lp(top = 2) }, memory,
            ui.tv("Names for speech recognition", 15f, c.onSurface, true).apply { layoutParams = ui.lp(top = 18) }, names
        ))

        // ---- Credits
        root.addView(ui.sectionTitle("Credits"))
        balance = ui.field("Credit balance in $").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; Usage.left(this@SettingsActivity)?.let { setText(String.format(Locale.US, "%.2f", it)) } }
        root.addView(ui.card(
            ui.tv("Balance", 15f, c.onSurface, true),
            ui.tv("Anthropic doesn't let apps read your balance. Enter it once (console.anthropic.com → Billing) and the app subtracts what it spends.", 12f, c.onSurfaceV).apply { layoutParams = ui.lp(top = 2) }, balance
        ))

        // ---- Updates
        root.addView(ui.sectionTitle("About"))
        updateStatus = ui.tv("Version ${Updater.current(this)}", 13f, c.onSurfaceV).apply { layoutParams = ui.lp(top = 8) }
        root.addView(ui.card(ui.tv("Updates", 15f, c.onSurface, true), updateStatus, ui.button("Check for updates") { Updater.check(this, true); Bus.listener = { runOnUiThread { updateStatus.text = if (Updater.rel != null) "New version ${Updater.rel!!.version} available. Open the main screen to install." else Updater.message.ifEmpty { "Version ${Updater.current(this)}" } } } }.apply { layoutParams = ui.lp(top = 12) }))

        setContentView(ui.page(root))
    }

    private fun recordShortcut(done: () -> Unit) {
        if (AgentService.instance == null) { toast("Turn on the Assistant accessibility service first"); return }
        val dlg = AlertDialog.Builder(this).setTitle("Press the button").setMessage("Press the button you want to use now (Essential Key, volume, headset button…).").setNegativeButton("Cancel") { _, _ -> AgentService.recordKey = null }.setOnCancelListener { AgentService.recordKey = null }.create()
        AgentService.recordKey = { scan, key ->
            val label = if (scan == ESS) "Essential Key" else android.view.KeyEvent.keyCodeToString(key).removePrefix("KEYCODE_").lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } + if (scan > 0) " (button $scan)" else ""
            p.edit().putInt("sc_scan", scan).putInt("sc_key", key).putString("sc_label", label).apply()
            dlg.dismiss(); done(); toast("Saved: $label")
        }
        dlg.show()
    }

    private val ESS = 250

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun voiceLocale(): Locale = p.getString("lang", "nl-BE").orEmpty().let { if (it.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(it) }

    private fun voices(): List<android.speech.tts.Voice> = try {
        tts?.voices.orEmpty().filter { it.locale.language == voiceLocale().language && !it.features.orEmpty().contains("notInstalled") }
            .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.name })
    } catch (e: Exception) { emptyList() }

    private fun label(v: android.speech.tts.Voice): String {
        val q = when { v.quality >= 500 -> "very high"; v.quality >= 400 -> "high"; v.quality >= 300 -> "normal"; else -> "low" }
        return "${v.locale.displayName} · $q" + (if (v.isNetworkConnectionRequired) " · online" else " · offline")
    }

    private fun refreshVoice() {
        val chosen = p.getString("ttsvoice", "").orEmpty()
        val v = voices().firstOrNull { it.name == chosen }
        voiceName.text = if (v != null) label(v) else "Automatic: the best installed voice (${voices().firstOrNull()?.let { label(it) } ?: "none found"})"
    }

    private fun pickVoice() {
        val vs = voices()
        if (vs.isEmpty()) { toast("No voices found: use More voices"); return }
        val items = (listOf("Automatic (best installed)") + vs.map { label(it) }).toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose voice").setItems(items) { _, i ->
            p.edit().putString("ttsvoice", if (i == 0) "" else vs[i - 1].name).apply(); refreshVoice(); AgentService.instance?.testVoice()
        }.show()
    }

    private fun save() {
        p.edit().putString(if (hearth) "key_h" else "key", key.text.toString().trim()).putString(if (hearth) "model_h" else "model", model.text.toString().trim())
            .putString("url", url.text.toString().trim()).putString("names", names.text.toString().trim())
            .putString("facts", memory.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")).apply()
        balance.text.toString().trim().toFloatOrNull()?.let { v -> if (kotlin.math.abs(v - (Usage.left(this) ?: -1f)) > 0.004f) Usage.setBalance(this, v) }
    }

    override fun onPause() { AgentService.recordKey = null; save(); super.onPause() }
    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
}
