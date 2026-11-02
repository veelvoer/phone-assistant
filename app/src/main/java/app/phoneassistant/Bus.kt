package app.phoneassistant

object Bus {
    val lines = ArrayList<String>()
    @Volatile var listener: (() -> Unit)? = null

    fun log(s: String) {
        synchronized(lines) { lines.add(s); if (lines.size > 300) lines.removeAt(0) }
        listener?.invoke()
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }
}
