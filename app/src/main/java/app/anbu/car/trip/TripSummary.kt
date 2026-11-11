package app.anbu.car.trip

import org.json.JSONObject

/**
 * 주행 한 번의 요약. 차 안 저장소에 남는 것은 이 값뿐이고,
 * 초 단위 원신호(속도 변화, 제동 시점 등)는 요약을 만든 뒤 버린다.
 */
data class TripSummary(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Double,
    /** 실제로 움직인 시간. 신호 대기 같은 정지 시간은 뺀다. */
    val movingMs: Long,
    val nightMs: Long,
    val rainMs: Long,
    val hardBrakes: Int,
    val lateBelt: Boolean,
    val reroutes: Int,
    val schoolZoneAlerts: Int,
    val wrongWayAlerts: Int,
    /** 도착한 저장 장소 id. 저장 장소가 아니면 null. */
    val arrivedPlaceId: String? = null,
    /** 시연용으로 만들어 넣은 예시 기록이면 true. 화면에 표시한다. */
    val synthetic: Boolean = false,
) {
    val durationMs: Long get() = endMs - startMs
    val nightShare: Double get() = if (movingMs > 0) nightMs.toDouble() / movingMs else 0.0
    val rainShare: Double get() = if (movingMs > 0) rainMs.toDouble() / movingMs else 0.0

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("startMs", startMs)
        .put("endMs", endMs)
        .put("distanceKm", distanceKm)
        .put("movingMs", movingMs)
        .put("nightMs", nightMs)
        .put("rainMs", rainMs)
        .put("hardBrakes", hardBrakes)
        .put("lateBelt", lateBelt)
        .put("reroutes", reroutes)
        .put("schoolZoneAlerts", schoolZoneAlerts)
        .put("wrongWayAlerts", wrongWayAlerts)
        .put("arrivedPlaceId", arrivedPlaceId ?: JSONObject.NULL)
        .put("synthetic", synthetic)

    companion object {
        fun fromJson(o: JSONObject) = TripSummary(
            id = o.getString("id"),
            startMs = o.getLong("startMs"),
            endMs = o.getLong("endMs"),
            distanceKm = o.getDouble("distanceKm"),
            movingMs = o.getLong("movingMs"),
            nightMs = o.getLong("nightMs"),
            rainMs = o.getLong("rainMs"),
            hardBrakes = o.getInt("hardBrakes"),
            lateBelt = o.getBoolean("lateBelt"),
            reroutes = o.getInt("reroutes"),
            schoolZoneAlerts = o.getInt("schoolZoneAlerts"),
            wrongWayAlerts = o.getInt("wrongWayAlerts"),
            arrivedPlaceId = if (o.isNull("arrivedPlaceId")) null else o.getString("arrivedPlaceId"),
            synthetic = o.optBoolean("synthetic", false),
        )
    }
}
