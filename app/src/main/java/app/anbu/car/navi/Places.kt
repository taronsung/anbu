package app.anbu.car.navi

import org.json.JSONArray
import org.json.JSONObject

/**
 * 자주 가는 곳 한 군데.
 *
 * 좌표는 길안내 요청(NaviHelper)과 도착 판정(FusedLocation)에만 쓰고 차 밖으로 내보내지 않는다.
 * 가족에게 가는 것은 [label]뿐이다.
 */
data class Place(
    val id: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val poiName: String,
    val address: String,
    /** 시연용으로 넣어 둔 공공장소 좌표면 true. 본인이 "지금 위치로 저장"하면 false가 된다. */
    val demoCoordinate: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("label", label)
        .put("latitude", latitude).put("longitude", longitude)
        .put("poiName", poiName).put("address", address)
        .put("demoCoordinate", demoCoordinate)

    companion object {
        fun fromJson(o: JSONObject) = Place(
            id = o.getString("id"),
            label = o.getString("label"),
            latitude = o.getDouble("latitude"),
            longitude = o.getDouble("longitude"),
            poiName = o.optString("poiName", ""),
            address = o.optString("address", ""),
            demoCoordinate = o.optBoolean("demoCoordinate", true),
        )
    }
}

object Places {
    const val HOME = "home"
    const val HOSPITAL = "hospital"
    const val DAUGHTER = "daughter"
    const val MART = "mart"

    /** 저장 장소가 아닌 곳에 도착했을 때 가족에게 가는 이름. */
    const val ELSEWHERE_LABEL = "외출지"

    /**
     * 처음 설치했을 때의 기본값. 개인 주소가 아니라 누구나 아는 공공장소 좌표를 넣어 두었고,
     * 실제 사용자는 설정에서 "지금 위치로 저장"으로 바꾼다.
     */
    val defaults: List<Place> = listOf(
        Place(HOME, "집", 37.5512, 126.9882, "남산공원 입구", "서울 중구 (시연용 좌표)"),
        Place(HOSPITAL, "병원", 37.5796, 126.9990, "대학로 병원 앞", "서울 종로구 (시연용 좌표)"),
        Place(DAUGHTER, "딸네", 37.5133, 127.1001, "잠실역 사거리", "서울 송파구 (시연용 좌표)"),
        Place(MART, "마트", 37.4636, 127.0429, "양재 농산물 시장", "서울 서초구 (시연용 좌표)"),
    )

    fun toJson(list: List<Place>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    fun fromJson(text: String): List<Place> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { Place.fromJson(arr.getJSONObject(it)) }
    }
}

/** 한국어 조사 "을/를"을 받침에 맞춰 붙인다. */
fun withObjectParticle(word: String): String {
    val last = word.lastOrNull() ?: return word
    if (last !in '가'..'힣') return word + "를"
    return if ((last.code - 0xAC00) % 28 == 0) word + "를" else word + "을"
}

/** 한국어 조사 "로/으로"를 받침에 맞춰 붙인다. */
fun withDirectionParticle(word: String): String {
    val last = word.lastOrNull() ?: return word
    if (last !in '가'..'힣') return word + "로"
    val jong = (last.code - 0xAC00) % 28
    // 받침이 없거나 ㄹ 받침이면 "로", 나머지는 "으로"
    return if (jong == 0 || jong == 8) word + "로" else word + "으로"
}
