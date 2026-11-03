package app.phoneassistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Updates from GitHub releases: check the newest release, download the APK, let Android install it over this app. */
object Updater {
    private const val REPO = "veelvoer/phone-assistant"
    class Rel(val version: String, val notes: String, val apk: String)

    @Volatile var rel: Rel? = null
    @Volatile var busy = false
    @Volatile var progress = -1
    @Volatile var message = ""
    @Volatile var checkedAt = 0L

    fun current(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"

    private fun parts(v: String) = v.trimStart('v').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    private fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    private fun ui() { Bus.listener?.invoke() }

    fun check(c: Context, force: Boolean = false) {
        if (busy || (!force && System.currentTimeMillis() - checkedAt < 6 * 3600_000L)) return
        busy = true; message = "Checking…"; ui()
        thread {
            try {
                val con = URL("https://api.github.com/repos/$REPO/releases?per_page=30").openConnection() as HttpURLConnection
                con.setRequestProperty("Accept", "application/vnd.github+json"); con.connectTimeout = 15000; con.readTimeout = 15000
                // newest phone release (tag v1.2.3) that has an APK; the desktop app has its own releases (desktop-v…) in the same repo
                val list = org.json.JSONArray(con.inputStream.bufferedReader().use { it.readText() })
                var j: JSONObject? = null
                for (k in 0 until list.length()) { val r = list.getJSONObject(k); if (!r.optBoolean("draft") && Regex("^v\\d+\\.\\d+").containsMatchIn(r.optString("tag_name"))) { j = r; break } }
                if (j == null) { message = "No release found"; busy = false; ui(); return@thread }
                val tag = j.getString("tag_name")
                val assets = j.getJSONArray("assets")
                var apk = ""
                for (i in 0 until assets.length()) assets.getJSONObject(i).let { if (it.getString("name").endsWith(".apk")) apk = it.getString("browser_download_url") }
                checkedAt = System.currentTimeMillis()
                if (apk.isNotEmpty() && newer(tag, current(c))) { rel = Rel(tag.trimStart('v'), j.optString("body"), apk); message = "" }
                else { rel = null; message = "You're up to date (v${current(c)})" }
            } catch (e: Exception) { message = "Update check failed: ${e.message}" }
            busy = false; ui()
        }
    }

    fun install(c: Context) {
        val r = rel ?: return
        if (busy) return
        if (!c.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(c, "Allow installing from this app, then tap Update again", Toast.LENGTH_LONG).show()
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        busy = true; progress = 0; message = "Downloading…"; ui()
        thread {
            try {
                val f = File(c.cacheDir, "update.apk")
                val con = URL(r.apk).openConnection() as HttpURLConnection
                con.connectTimeout = 15000; con.readTimeout = 30000
                val total = con.contentLength.toLong()
                con.inputStream.use { i -> f.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                    while (i.read(buf).also { n = it } > 0) { o.write(buf, 0, n); done += n; if (total > 0) { progress = (done * 100 / total).toInt(); ui() } }
                } }
                message = "Installing…"; ui()
                commit(c, f)
            } catch (e: Exception) { message = "Update failed: ${e.message}" }
            progress = -1; busy = false; ui()
        }
    }

    private fun commit(c: Context, f: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("app", 0, f.length()).use { o -> f.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val pend = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(pend.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> { Updater.message = "Install failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE); Bus.listener?.invoke() }
        }
    }
}
