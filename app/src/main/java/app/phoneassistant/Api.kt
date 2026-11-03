package app.phoneassistant

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Api {
    fun post(base: String, key: String, body: JSONObject): JSONObject {
        var last = ""
        repeat(3) { attempt ->
            val c = URL(if (base.isBlank()) "https://api.anthropic.com/v1/messages" else base.trim().trimEnd('/').removeSuffix("/v1/messages") + "/v1/messages").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 20000
            c.readTimeout = if (base.isBlank()) 60000 else 300000
            c.doOutput = true
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.setRequestProperty("content-type", "application/json")
            val code: Int; val text: String
            try {
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                code = c.responseCode
                text = ((if (code in 200..299) c.inputStream else c.errorStream) ?: return@repeat).bufferedReader().use { it.readText() }
            } catch (e: java.io.IOException) {   // no internet, timeout, dropped connection: retry a couple of times
                last = "NETWORK: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
                if (attempt < 2) Thread.sleep(1500L * (attempt + 1))
                return@repeat
            } finally { c.disconnect() }
            if (code in 200..299) return JSONObject(text)
            last = "API $code: ${text.take(900)}"
            if (code != 429 && code != 529 && code < 500) throw RuntimeException(last)
            Thread.sleep(2000L * (attempt + 1))
        }
        throw RuntimeException(last)
    }
}
