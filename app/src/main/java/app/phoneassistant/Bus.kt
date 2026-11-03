package app.phoneassistant

object Bus {
    val lines = ArrayList<String>()
    @Volatile var listener: (() -> Unit)? = null

    fun log(s: String) {
        android.util.Log.i("PAlog", s.replace("\n", " ⏎ ").take(900))
        synchronized(lines) { lines.add(s); if (lines.size > 300) lines.removeAt(0) }
        listener?.invoke()
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }
}
