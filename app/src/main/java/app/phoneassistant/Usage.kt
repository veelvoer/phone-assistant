package app.phoneassistant

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local token + cost tracking. Anthropic gives apps no credit-balance endpoint, so the balance is "what you entered minus what this app spent". */
object Usage {
    // $ per 1M tokens (input, output), first-party API rates
    private val prices = listOf(
        "haiku-5" to (0.10 to 0.50), "sonnet-5" to (2.0 to 10.0), "opus-5-5" to (4.0 to 20.0), "opus-5" to (5.0 to 25.0),
        "fable" to (10.0 to 50.0), "mythos" to (10.0 to 50.0), "opus-4" to (5.0 to 25.0), "sonnet-4" to (3.0 to 15.0), "haiku-4" to (1.0 to 5.0)
    )

    @Volatile var taskIn = 0L; @Volatile var taskOut = 0L; @Volatile var taskCost = 0.0; @Volatile var calls = 0

    fun beginTask() { taskIn = 0; taskOut = 0; taskCost = 0.0; calls = 0 }

    fun price(model: String): Pair<Double, Double> = prices.firstOrNull { model.contains(it.first) }?.second ?: (0.10 to 0.50)

    fun record(c: Context, model: String, resp: JSONObject, hearth: Boolean) {
        val u = resp.optJSONObject("usage") ?: return
        val inp = u.optLong("input_tokens"); val out = u.optLong("output_tokens")
        val cr = u.optLong("cache_read_input_tokens"); val cw = u.optLong("cache_creation_input_tokens")
        val (pi, po) = price(model)
        val cost = if (hearth) 0.0 else (inp * pi + cr * pi * 0.1 + cw * pi * 1.25 + out * po) / 1e6
        taskIn += inp + cr + cw; taskOut += out; taskCost += cost; calls++
        if (hearth || cost <= 0.0) return
        val p = c.getSharedPreferences("usage", Context.MODE_PRIVATE)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val today = if (p.getString("day", "") == day) p.getFloat("today", 0f) else 0f
        p.edit().putString("day", day).putFloat("today", today + cost.toFloat())
            .putFloat("total", p.getFloat("total", 0f) + cost.toFloat())
            .putFloat("since", p.getFloat("since", 0f) + cost.toFloat()).apply()
    }

    fun setBalance(c: Context, dollars: Float) { c.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().putFloat("balance", dollars).putFloat("since", 0f).putBoolean("hasBalance", true).apply() }

    fun clearBalance(c: Context) { c.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().remove("balance").remove("hasBalance").apply() }

    fun left(c: Context): Float? { val p = c.getSharedPreferences("usage", Context.MODE_PRIVATE); return if (p.getBoolean("hasBalance", false)) p.getFloat("balance", 0f) - p.getFloat("since", 0f) else null }
    fun today(c: Context): Float { val p = c.getSharedPreferences("usage", Context.MODE_PRIVATE); val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()); return if (p.getString("day", "") == day) p.getFloat("today", 0f) else 0f }
    fun total(c: Context): Float = c.getSharedPreferences("usage", Context.MODE_PRIVATE).getFloat("total", 0f)

    fun money(x: Double): String = if (x < 0.01) "$" + String.format(Locale.US, "%.4f", x) else "$" + String.format(Locale.US, "%.2f", x)
    fun k(n: Long): String = if (n >= 1000) String.format(Locale.US, "%.1fk", n / 1000.0) else n.toString()

    fun taskLine(hearth: Boolean): String = "${k(taskIn)} in · ${k(taskOut)} out · $calls calls" + if (hearth) "" else " ≈ ${money(taskCost)}"
}
