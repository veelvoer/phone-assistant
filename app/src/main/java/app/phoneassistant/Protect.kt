package app.phoneassistant

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Banking apps (KBC Mobile and others) refuse to run while an accessibility service is switched on.
 * So the assistant switches itself off the moment such an app comes to the front and the user switches it back on
 * with one tap (the Assistant tile or the app). Android lets an app switch itself on only with the WRITE_SECURE_SETTINGS
 * permission, which the user can grant once with adb (command shown in settings); without it the user turns it on in Settings.
 */
object Protect {
    private val defaults = listOf("kbc", "belfius", "bnpparibas", "argenta", "ing.mobile", "ing.banking", "bunq", "revolut", "paypal", "payconiq", "itsme", "rabobank", "abnamro")

    private fun prefs(c: Context) = c.getSharedPreferences("p", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("pause_banking", true)
    fun extra(c: Context): List<String> = prefs(c).getString("pause_apps", "").orEmpty().split(',', '\n', ' ').map { it.trim().lowercase() }.filter { it.length >= 3 }

    fun isProtected(c: Context, pkg: String?): Boolean {
        val p = pkg?.lowercase() ?: return false
        if (p == c.packageName || p.startsWith("com.android.systemui")) return false
        return (defaults + extra(c)).any { p.contains(it) }
    }

    fun canSwitchOn(c: Context) = c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    fun paused(c: Context) = prefs(c).getBoolean("paused_for_bank", false)
    fun markPaused(c: Context, v: Boolean) { prefs(c).edit().putBoolean("paused_for_bank", v).apply() }
    fun adbCommand(c: Context) = "adb shell pm grant ${c.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /** Switch the accessibility service back on. Returns true when it is (or already was) on. */
    fun resume(c: Context): Boolean {
        if (AgentService.instance != null) { markPaused(c, false); return true }
        if (!canSwitchOn(c)) return false
        return try {
            val me = ComponentName(c, AgentService::class.java).flattenToString()
            val cur = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val list = cur.split(':').filter { it.isNotBlank() }
            if (me !in list) Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (list + me).joinToString(":"))
            Settings.Secure.putInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            markPaused(c, false); true
        } catch (e: Exception) { false }
    }
}
