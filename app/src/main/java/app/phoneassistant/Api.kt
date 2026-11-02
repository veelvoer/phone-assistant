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
            c.readTimeout = 300000
            c.doOutput = true
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
            if (code in 200..299) return JSONObject(text)
            last = "API $code: ${text.take(300)}"
            if (code != 429 && code != 529 && code < 500) throw RuntimeException(last)
            Thread.sleep(2000L * (attempt + 1))
        }
        throw RuntimeException(last)
    }
}
