package app.phoneassistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var task: EditText
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("p", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        fun btn(t: String, w: Float = 1f, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
        }
        key = EditText(this).apply {
            hint = "Claude API key (sk-ant-...)"; setText(p.getString("key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        model = EditText(this).apply { hint = "Model"; setText(p.getString("model", "claude-haiku-5-5")); setSingleLine() }
        task = EditText(this).apply { hint = "What should I do? e.g. open Snapchat, open Anna's snap and send one back"; minLines = 2 }
        log = TextView(this).apply { textSize = 12f }
        scroll = ScrollView(this).apply { addView(log) }
        val row = LinearLayout(this).apply {
            addView(btn("🎤") { startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM), 1) }.also { (it.layoutParams as LinearLayout.LayoutParams).weight = 0.5f })
            addView(btn("Run") { run(p) })
            addView(btn("Stop") { AgentService.instance?.stop() })
        }
        root.addView(btn("1. Enable accessibility service") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2) })
        root.addView(key); root.addView(model); root.addView(task); root.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun run(p: android.content.SharedPreferences) {
        val svc = AgentService.instance
        if (svc == null) {
            Toast.makeText(this, "Enable the Phone Assistant accessibility service first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        val k = key.text.toString().trim(); val m = model.text.toString().trim(); val t = task.text.toString().trim()
        if (k.isEmpty() || t.isEmpty()) { Toast.makeText(this, "API key and task required", Toast.LENGTH_SHORT).show(); return }
        p.edit().putString("key", k).putString("model", m).apply()
        if (svc.start(t, k, m)) moveTaskToBack(true)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { task.setText(it) }
    }

    override fun onResume() {
        super.onResume()
        Bus.listener = { runOnUiThread { log.text = Bus.text(); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } } }
        Bus.listener?.invoke()
    }

    override fun onPause() { super.onPause(); Bus.listener = null }
}
