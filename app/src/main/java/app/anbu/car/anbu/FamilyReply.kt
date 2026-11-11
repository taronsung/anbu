package app.anbu.car.anbu

import org.json.JSONObject

/**
 * 가족이 고를 수 있는 답장. 가족 화면에는 이 단추들만 있고 글을 써서 보내는 길은 없다.
 * 주행 중에 긴 글이 차로 들어와 주의를 흩뜨리는 일을 만들지 않기 위해서다.
 */
enum class ReplyPreset(val text: String) {
    SAFE_TRIP("잘 다녀오세요"),
    DRIVE_SAFE("조심히 오세요"),
    CALL_ON_ARRIVAL("도착하면 전화 주세요"),
    THANKS("고마워요"),
}

/**
 * 가족이 보낸 답장 한 건.
 *
 * 차 밖에서 들어오는 것은 [WIRE_KEYS]의 세 가지가 전부다: 릴레이가 붙인 id, 답장의 이름, 받은 시각.
 * 글 자체는 오지 않고, 차가 [ReplyPreset]에서 문장을 고른다.
 */
data class FamilyReply(
    val id: String,
    val preset: ReplyPreset,
    val atMs: Long,
    /** 차가 읽어 준 시각. 아직 읽지 않았으면 null. */
    val readAtMs: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("code", preset.name).put("atMs", atMs)
        .apply { readAtMs?.let { put("readAtMs", it) } }

    companion object {
        val WIRE_KEYS = setOf("id", "code", "atMs")

        /** 릴레이가 준 한 건을 읽는다. 모르는 답장이거나 약속한 모양이 아니면 null. */
        fun fromWire(text: String): FamilyReply? = runCatching {
            val o = JSONObject(text)
            if (!o.keys().asSequence().all { it in WIRE_KEYS }) return null
            val id = o.getString("id")
            if (id.isEmpty() || id.length > 40) return null
            val preset = ReplyPreset.entries.firstOrNull { it.name == o.getString("code") } ?: return null
            FamilyReply(id, preset, o.getLong("atMs"))
        }.getOrNull()

        fun fromJson(o: JSONObject): FamilyReply? = runCatching {
            FamilyReply(
                id = o.getString("id"),
                preset = ReplyPreset.valueOf(o.getString("code")),
                atMs = o.getLong("atMs"),
                readAtMs = if (o.has("readAtMs")) o.getLong("readAtMs") else null,
            )
        }.getOrNull()
    }
}
