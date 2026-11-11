package app.anbu.car.core

import app.anbu.car.baseline.Weeks
import app.anbu.car.navi.Places
import app.anbu.car.trip.TripSummary

/**
 * 시연용 예시 기록.
 *
 * 주간 일기는 지난 기록이 4주 이상 있어야 "평소"와 비교할 수 있다. 심사나 시연 자리에서
 * 몇 주를 기다릴 수 없으므로, 지난 8주와 최근 7일의 주행 요약을 만들어 넣는다.
 * 실제 주행에서 나온 값이 아니며 모든 항목에 synthetic 표시를 한다.
 *
 * 최근 7일은 밤 운전이 평소보다 조금 많게 만들어 "조금 다름"이 나오도록 했다.
 */
object ExampleHistory {
    private const val DAY = 24L * 3600 * 1000
    private const val MIN = 60_000L
    private val destinations = listOf(Places.HOSPITAL, Places.MART, Places.DAUGHTER, Places.HOME, Places.MART)

    fun build(nowMs: Long): List<TripSummary> {
        val out = ArrayList<TripSummary>()
        for (week in 8 downTo 1) {
            val weekEnd = nowMs - week * Weeks.WEEK_MS
            val offsets = doubleArrayOf(0.6, 1.7, 3.1, 4.4, 5.8)
            for (i in offsets.indices) {
                val r = pseudo(week * 31 + i * 7)
                val moving = (18 + r % 14) * MIN
                // 지난 8주: 2주에 한 번쯤 해 진 뒤 짧게 운전
                val night = if (i == 3 && week % 2 == 0) moving / 2 else 0L
                out += trip(
                    endMs = weekEnd - (offsets[i] * DAY).toLong(),
                    movingMs = moving,
                    distanceKm = 6.0 + (r % 80) / 10.0,
                    nightMs = night,
                    rainMs = if (week == 3 || week == 6) moving / 3 else 0L,
                    hardBrakes = if ((week + i) % 4 == 0) 1 else 0,
                    lateBelt = week == 5 && i == 1,
                    reroutes = if ((week * 3 + i) % 5 == 0) 1 else 0,
                    schoolZones = 1 + (r % 2).toInt(),
                    placeId = destinations[i],
                )
            }
        }
        // 최근 7일: 다섯 번 가운데 두 번은 절반쯤이 해 진 뒤 운전(밤 운전 비율 약 20%)
        val recent = doubleArrayOf(0.4, 1.3, 2.6, 4.2, 5.7)
        for (i in recent.indices) {
            val moving = (20 + i * 3) * MIN
            out += trip(
                endMs = nowMs - (recent[i] * DAY).toLong(),
                movingMs = moving,
                distanceKm = 8.0 + i * 1.5,
                nightMs = when (i) {
                    1, 3 -> moving / 2
                    else -> 0L
                },
                rainMs = if (i == 2) moving / 2 else 0L,
                hardBrakes = if (i == 1) 1 else 0,
                lateBelt = false,
                reroutes = if (i == 4) 1 else 0,
                schoolZones = 1,
                placeId = destinations[i],
            )
        }
        return out
    }

    private fun trip(
        endMs: Long, movingMs: Long, distanceKm: Double, nightMs: Long, rainMs: Long,
        hardBrakes: Int, lateBelt: Boolean, reroutes: Int, schoolZones: Int, placeId: String,
    ) = TripSummary(
        id = "example-$endMs",
        startMs = endMs - movingMs - 4 * MIN,
        endMs = endMs,
        distanceKm = distanceKm,
        movingMs = movingMs,
        nightMs = nightMs,
        rainMs = rainMs,
        hardBrakes = hardBrakes,
        lateBelt = lateBelt,
        reroutes = reroutes,
        schoolZoneAlerts = schoolZones,
        wrongWayAlerts = 0,
        arrivedPlaceId = placeId,
        synthetic = true,
    )

    /** 실행할 때마다 같은 값이 나오는 간단한 난수. */
    private fun pseudo(seed: Int): Long {
        var x = seed.toLong() * 1103515245L + 12345L
        x = (x xor (x ushr 13)) * 2685821657736338717L
        return (x ushr 20) and 0x7FFF
    }
}
