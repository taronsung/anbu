package app.anbu.car.anbu

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 시제품용 전송 구현. 가족 화면을 띄우는 데모 릴레이(relay/server.js)로 HTTP 요청을 보낸다.
 * 실제 서비스에서는 인증과 TLS가 있는 서버로 바꿔야 한다(README 로드맵 참고).
 */
class RelayTransport(private val baseUrl: () -> String) : Transport {

    override fun postNote(wireJson: String): Boolean = request("POST", "/api/notes", wireJson)

    override fun deleteFamily(familyCode: String): Boolean =
        request("DELETE", "/api/notes?code=" + URLEncoder.encode(familyCode, "UTF-8"), null)

    override fun fetchReplies(familyCode: String, afterId: String?): List<String>? {
        var conn: HttpURLConnection? = null
        return try {
            val query = "code=" + URLEncoder.encode(familyCode, "UTF-8") +
                (afterId?.let { "&after=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            conn = (URL(baseUrl().trimEnd('/') + "/api/replies?" + query).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3_000
                readTimeout = 5_000
            }
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            // 답장은 짧은 이름과 시각뿐이다. 그보다 훨씬 큰 응답은 받지 않는다.
            if (text.length > 16 * 1024) return null
            val arr = org.json.JSONObject(text).optJSONArray("replies") ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.toString() }
        } catch (e: Exception) {
            Log.i("AnbuRelay", "GET /api/replies failed: ${e.javaClass.simpleName}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun request(method: String, path: String, body: String?): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseUrl().trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 3_000
                readTimeout = 5_000
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            Log.i("AnbuRelay", "$method $path failed: ${e.javaClass.simpleName}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
