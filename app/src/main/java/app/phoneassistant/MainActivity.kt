package app.phoneassistant

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var p: android.content.SharedPreferences

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var updateCard: LinearLayout
    private lateinit var updateTitle: TextView
    private lateinit var updateNotes: TextView
    private lateinit var updateBtn: LinearLayout
    private lateinit var setupCard: LinearLayout
    private lateinit var task: EditText
    private lateinit var runBtn: LinearLayout
    private lateinit var planCard: LinearLayout
    private lateinit var planText: TextView
    private lateinit var statToday: TextView
    private lateinit var statLeft: TextView
    private lateinit var statLast: TextView
    private lateinit var logCard: LinearLayout
    private lateinit var log: TextView

    private val hearth get() = p.getBoolean("hearth", false)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("p", MODE_PRIVATE)
        ui = Ui(this); ui.statusBar(this)
        val c = ui.c
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(26), ui.dp(20), ui.dp(32)) }

        // header
        statusDot = View(this).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)).apply { rightMargin = ui.dp(8) } }
        statusText = ui.tv("", 13f, c.onSurfaceV)
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; layoutParams = ui.lp(0, -2, weight = 1f)
                addView(ui.tv("Assistant", 32f, c.onSurface, true))
                addView(LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; layoutParams = ui.lp(top = 4); addView(statusDot); addView(statusText) })
            })
            addView(ui.iconButton(R.drawable.ic_settings, c.surface, c.onSurface) { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) })
        })

        // update
        updateTitle = ui.tv("", 16f, c.onPrimaryC, true)
        updateNotes = ui.tv("", 13f, c.onPrimaryC).apply { layoutParams = ui.lp(top = 6) }
        updateBtn = ui.button("Update now", filled = true) { Updater.install(this) }.apply { layoutParams = ui.lp(top = 14) }
        updateCard = ui.card(updateTitle, updateNotes, updateBtn, bg = c.primaryC)
        root.addView(updateCard)

        // setup
        fun step(n: String, title: String, text: String, btn: LinearLayout) = LinearLayout(this).apply {
            layoutParams = ui.lp(top = 16)
            addView(ui.tv(n, 14f, c.onPrimary, true).apply { gravity = Gravity.CENTER; background = ui.shape(c.primary, 14); layoutParams = ui.lp(ui.dp(28), ui.dp(28), right = 12) })
            addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; layoutParams = ui.lp(0, -2, weight = 1f)
                addView(ui.tv(title, 15f, c.onSurface, true)); addView(ui.tv(text, 13f, c.onSurfaceV).apply { layoutParams = ui.lp(top = 2) }); addView(btn.apply { layoutParams = ui.lp(top = 10) }) })
        }
        setupCard = ui.card(
            ui.tv("Set up once", 20f, c.onSurface, true),
            ui.tv("Android blocks sideloaded apps from using accessibility until you allow it.", 13f, c.onSurfaceV).apply { layoutParams = ui.lp(top = 4) },
            step("1", "Allow restricted settings", "App info → ⋮ (top right) → Allow restricted settings.", ui.button("Open App info") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }),
            step("2", "Turn on the service", "Accessibility → Installed apps → Phone Assistant → On.", ui.button("Open Accessibility", filled = true) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        )
        root.addView(setupCard)

        // ask
        task = EditText(this).apply {
            hint = "What should I do?"; textSize = 19f; setTextColor(c.onSurface); setHintTextColor(c.onSurfaceV)
            background = null; minLines = 3; gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val chips = LinearLayout(this)
        listOf("Open Snapchat and reply to the newest snap", "Turn on Wi-Fi", "Search lofi on YouTube").forEach { ex -> chips.addView(ui.chip(ex) { task.setText(ex); task.setSelection(ex.length) }) }
        runBtn = ui.button("Run", R.drawable.ic_play, filled = true) { onRun() }.apply { layoutParams = ui.lp(0, -2, weight = 1f) }
        val mic = ui.iconButton(R.drawable.ic_mic, c.primaryC, c.onPrimaryC, 52) {
            try { startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).apply { p.getString("lang", "nl-BE")?.takeIf { it.isNotEmpty() }?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) } }, 1) }
            catch (e: Exception) { Toast.makeText(this, "No speech recognizer on this phone", Toast.LENGTH_SHORT).show() }
        }.apply { layoutParams = ui.lp(ui.dp(52), ui.dp(52), right = 10) }
        root.addView(ui.card(
            task,
            HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips); layoutParams = ui.lp(top = 8) },
            LinearLayout(this).apply { layoutParams = ui.lp(top = 14); gravity = Gravity.CENTER_VERTICAL; addView(mic); addView(runBtn) },
            ui.tv("or hold the Essential Key and just talk", 12f, c.onSurfaceV).apply { gravity = Gravity.CENTER; layoutParams = ui.lp(top = 12) }
        ))

        // plan
        planText = ui.tv("", 15f, c.onSurface).apply { setLineSpacing(0f, 1.15f); layoutParams = ui.lp(top = 8) }
        val planIn = ui.field("Change the plan… (or just say it)")
        planCard = ui.card(
            ui.tv("Plan", 18f, c.onSurface, true), planText, planIn,
            ui.button("Send change") { val t = planIn.text.toString().trim(); if (t.isNotEmpty()) { AgentService.instance?.interrupt(t); planIn.setText("") } }.apply { layoutParams = ui.lp(top = 10) }
        )
        root.addView(planCard)

        // stats
        fun stat(label: String): Pair<LinearLayout, TextView> {
            val v = ui.tv("–", 18f, c.onSurface, true)
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; background = ui.shape(c.surface, 22); setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
                layoutParams = ui.lp(0, -2, weight = 1f, right = 8)
                addView(ui.tv(label, 12f, c.onSurfaceV)); addView(v.apply { layoutParams = ui.lp(top = 2) })
            } to v
        }
        val (t1, v1) = stat("Today"); val (t2, v2) = stat("Credits left"); val (t3, v3) = stat("Last task")
        statToday = v1; statLeft = v2; statLast = v3
        root.addView(LinearLayout(this).apply { layoutParams = ui.lp(top = 14); addView(t1); addView(t2); addView(t3.apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = 0 }) })

        // activity
        log = ui.tv("", 12f, c.onSurfaceV, mono = true).apply { layoutParams = ui.lp(top = 8) }
        logCard = ui.card(
            LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL
                addView(ui.tv("Activity", 18f, c.onSurface, true).apply { layoutParams = ui.lp(0, -2, weight = 1f) })
                addView(ui.tv("Clear", 13f, c.primary, true).apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6)); setOnClickListener { synchronized(Bus.lines) { Bus.lines.clear() }; render() } }) },
            log
        )
        root.addView(logCard)

        setContentView(ui.page(root))
    }

    private fun onRun() {
        val svc = AgentService.instance
        if (svc?.running == true) { svc.stop(); return }
        if (svc == null) { Toast.makeText(this, "Finish the setup first", Toast.LENGTH_LONG).show(); return }
        val t = task.text.toString().trim()
        val key = p.getString(if (hearth) "key_h" else "key", "").orEmpty().trim()
        if (key.isEmpty() || (hearth && p.getString("url", "").orEmpty().isBlank())) {
            Toast.makeText(this, "Add your API key in Settings first", Toast.LENGTH_LONG).show(); startActivity(Intent(this, SettingsActivity::class.java)); return
        }
        if (t.isEmpty()) { Toast.makeText(this, "Describe a task first", Toast.LENGTH_SHORT).show(); return }
        svc.startFromPrefs(t); moveTaskToBack(true)
    }

    private fun render() {
        val c = ui.c
        val svc = AgentService.instance
        val running = svc?.running == true
        statusDot.background = ui.shape(if (svc == null) c.error else if (running) c.primary else c.ok, 4)
        statusText.text = (if (svc == null) "Setup needed" else if (running) "Working…" else "Ready") + "  ·  v${Updater.current(this)}"
        setupCard.visibility = if (svc == null) View.VISIBLE else View.GONE

        val r = Updater.rel
        updateCard.visibility = if (r != null) View.VISIBLE else View.GONE
        if (r != null) {
            updateTitle.text = "Update available: v${r.version}"
            updateNotes.text = r.notes.lines().map { it.trim().replace(Regex("^[#*\\-\\s]+"), "• ") }.filter { it.length > 2 }.take(4).joinToString("\n")
            updateNotes.visibility = if (updateNotes.text.isEmpty()) View.GONE else View.VISIBLE
            ui.setLabel(updateBtn, if (Updater.progress >= 0) "${Updater.message} ${Updater.progress}%" else if (Updater.busy) Updater.message else "Update now")
            updateBtn.isEnabled = !Updater.busy
        }

        val td = AgentService.todos
        planCard.visibility = if (td.isEmpty()) View.GONE else View.VISIBLE
        planText.text = td.joinToString("\n") { (when (it.status) { "done" -> "✓  "; "doing" -> "▶  "; "skipped" -> "–  "; else -> "○  " }) + it.text }

        ui.setLabel(runBtn, if (running) "Stop" else "Run")
        runBtn.background = ui.ripple(0, ui.shape(if (running) c.error else c.primary, 26))
        (runBtn.getChildAt(0) as? ImageView)?.apply { setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_play) }

        val left = Usage.left(this)
        statToday.text = if (hearth) "plan" else Usage.money(Usage.today(this).toDouble())
        statLeft.text = if (hearth) "–" else if (left != null) Usage.money(left.toDouble()) else "set in Settings"
        statLeft.textSize = if (left == null && !hearth) 13f else 18f
        statLast.text = if (Usage.calls > 0) (if (hearth) Usage.k(Usage.taskIn + Usage.taskOut) + " tok" else Usage.money(Usage.taskCost)) else "–"

        val lines = synchronized(Bus.lines) { Bus.lines.takeLast(9) }
        logCard.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        log.text = lines.joinToString("\n") { it.lineSequence().first().take(120) }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { task.setText(it) }
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2)
        Bus.listener = { runOnUiThread { render() } }
        render(); Updater.check(this)
    }

    override fun onPause() { super.onPause(); Bus.listener = null }
}
