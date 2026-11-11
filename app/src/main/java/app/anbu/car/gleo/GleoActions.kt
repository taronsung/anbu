package app.anbu.car.gleo

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * Gleo AI 연동(Gleo Interface).
 *
 * assets/schema.json에 적은 function 이름이 그대로 Intent action으로 들어온다.
 * 인자는 "ACTION_PARAM" extra에 JSON 문자열로 담겨 오고, 처리 결과는
 * APP_ACTION_RESPONSE 브로드캐스트로 돌려준다.
 */
object GleoActions {
    const val SEND_DEPARTURE_NOTE = "SEND_DEPARTURE_NOTE"
    const val SEND_ARRIVAL_NOTE = "SEND_ARRIVAL_NOTE"
    const val READ_WEEKLY_DIARY = "READ_WEEKLY_DIARY"

    val ALL = setOf(SEND_DEPARTURE_NOTE, SEND_ARRIVAL_NOTE, READ_WEEKLY_DIARY)

    const val EXTRA_PARAM = "ACTION_PARAM"
    const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    data class Reply(
        val functionName: String,
        /** "success", "asking", "fail" 가운데 하나 */
        val status: String,
        /** 영어 한 문장 */
        val message: String,
        /** JSON 객체 배열 문자열 */
        val data: String? = null,
        /** 앱이 직접 음성 안내를 하므로 Gleo의 음성은 끈다. */
        val mode: String = "silent",
    )

    /** ACTION_PARAM을 읽는다. 바깥에 따옴표가 한 겹 더 붙어 오는 경우도 받는다. */
    fun parseParams(raw: String?): JSONObject {
        if (raw.isNullOrBlank()) return JSONObject()
        var text = raw.trim()
        if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length - 1).replace("\\\"", "\"")
        }
        return runCatching { JSONObject(text) }.getOrElse { JSONObject() }
    }

    fun respond(context: Context, reply: Reply) {
        runCatching {
            val json = JSONObject()
                .put("functionName", reply.functionName)
                .put("status", reply.status)
                .put("message", reply.message)
                .put("mode", reply.mode)
            reply.data?.let { json.put("data", it) }
            context.sendBroadcast(
                Intent(RESPONSE_ACTION)
                    .putExtra("packageName", context.packageName)
                    .putExtra("response", json.toString())
            )
            Log.i("AnbuGleo", "response: $json")
        }.onFailure { Log.w("AnbuGleo", "respond failed", it) }
    }
}
