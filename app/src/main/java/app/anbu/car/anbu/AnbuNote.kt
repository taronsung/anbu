package app.anbu.car.anbu

import app.anbu.car.baseline.Level
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class NoteType { DEPARTURE, ARRIVAL, WEEKLY, CHECK_IN }

/**
 * 가족에게 가는 안부 한 건.
 *
 * 차 밖으로 나가는 것은 이 클래스의 필드가 전부다. 좌표, 경로, 속도는 필드 자체가 없다.
 */
data class AnbuNote(
    val id: String,
    val type: NoteType,
    /** 운전자 호칭(아버지, 어머니 등). 이름이 아니다. */
    val who: String,
    val atMs: Long,
    /** 가족이 읽는 한 줄. */
    val line: String,
    /** 본인이 붙인 장소 이름표. 집, 병원, 딸네, 마트, 외출지 가운데 하나. */
    val placeLabel: String? = null,
    /** "15:10쯤 도착 예정" 같은 도착 예정 문구. */
    val etaText: String? = null,
    /** "평소보다 10분쯤 더 걸렸어요". 본인이 켰을 때만. */
    val durationNote: String? = null,
    /** 주간 일기의 단계. */
    val level: Level? = null,
    /** 주간 일기 세부 수치. 본인이 켰을 때만 채운다. */
    val details: Map<String, String>? = null,
) {
    /** 릴레이로 보내는 JSON. 여기 적은 키만 나간다. */
    fun toWire(familyCode: String, zone: ZoneId): JSONObject {
        val o = JSONObject()
            .put("v", 1)
            .put("id", id)
            .put("familyCode", familyCode)
            .put("type", type.name)
            .put("who", who)
            .put("at", ISO.format(Instant.ofEpochMilli(atMs).atZone(zone)))
            .put("line", line)
        placeLabel?.let { o.put("placeLabel", it) }
        etaText?.let { o.put("etaText", it) }
        durationNote?.let { o.put("durationNote", it) }
        level?.let { o.put("level", it.name) }
        details?.let { d -> o.put("details", JSONObject().apply { d.forEach { (k, v) -> put(k, v) } }) }
        return o
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id).put("type", type.name).put("who", who).put("atMs", atMs).put("line", line)
        placeLabel?.let { o.put("placeLabel", it) }
        etaText?.let { o.put("etaText", it) }
        durationNote?.let { o.put("durationNote", it) }
        level?.let { o.put("level", it.name) }
        details?.let { d -> o.put("details", JSONObject().apply { d.forEach { (k, v) -> put(k, v) } }) }
        return o
    }

    companion object {
        private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX")

        /** 릴레이가 받아 주는 키. 릴레이 서버도 같은 목록으로 검사한다. */
        val WIRE_KEYS = setOf(
            "v", "id", "familyCode", "type", "who", "at", "line",
            "placeLabel", "etaText", "durationNote", "level", "details",
        )

        fun fromJson(o: JSONObject): AnbuNote {
            val details = o.optJSONObject("details")?.let { d ->
                d.keys().asSequence().associateWith { d.getString(it) }
            }
            return AnbuNote(
                id = o.getString("id"),
                type = NoteType.valueOf(o.getString("type")),
                who = o.getString("who"),
                atMs = o.getLong("atMs"),
                line = o.getString("line"),
                placeLabel = o.optString("placeLabel", "").ifEmpty { null },
                etaText = o.optString("etaText", "").ifEmpty { null },
                durationNote = o.optString("durationNote", "").ifEmpty { null },
                level = o.optString("level", "").ifEmpty { null }?.let { Level.valueOf(it) },
                details = details,
            )
        }
    }
}
