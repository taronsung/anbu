package app.anbu.car.anbu

import app.anbu.car.baseline.DiaryAssessment
import app.anbu.car.baseline.Level
import app.anbu.car.baseline.Metric
import app.anbu.car.baseline.MetricAssessment
import app.anbu.car.baseline.RobustStats
import app.anbu.car.baseline.WeekStats
import app.anbu.car.navi.Places
import app.anbu.car.navi.withDirectionParticle
import app.anbu.car.trip.TripSummary
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 안부 문장을 만든다. 건강이나 능력을 판단하는 말은 쓰지 않고,
 * 사실(도착했다, 평소와 다르다)만 적는다.
 */
class NoteComposer(private val zone: ZoneId) {

    fun arrival(
        id: String,
        who: String,
        atMs: Long,
        placeLabel: String?,
        durationNote: String? = null,
    ): AnbuNote {
        val label = placeLabel ?: Places.ELSEWHERE_LABEL
        return AnbuNote(
            id = id,
            type = NoteType.ARRIVAL,
            who = who,
            atMs = atMs,
            line = "${who}께서 ${label}에 잘 도착하셨어요.",
            placeLabel = label,
            durationNote = durationNote,
        )
    }

    fun departure(
        id: String,
        who: String,
        atMs: Long,
        destinationLabel: String?,
        etaAtMs: Long?,
    ): AnbuNote {
        val head = if (destinationLabel != null) {
            "${who}께서 지금 ${withDirectionParticle(destinationLabel)} 출발하셨어요."
        } else {
            "${who}께서 지금 출발하셨어요."
        }
        return AnbuNote(
            id = id,
            type = NoteType.DEPARTURE,
            who = who,
            atMs = atMs,
            line = head,
            placeLabel = destinationLabel,
            etaText = etaAtMs?.let { etaText(it) },
        )
    }

    fun weekly(
        id: String,
        who: String,
        atMs: Long,
        week: WeekStats,
        assessment: DiaryAssessment,
        includeDetails: Boolean,
    ): AnbuNote = AnbuNote(
        id = id,
        type = NoteType.WEEKLY,
        who = who,
        atMs = atMs,
        line = weeklyLine(assessment),
        level = assessment.overall,
        details = if (includeDetails) weeklyDetails(week) else null,
    )

    fun checkIn(id: String, who: String, atMs: Long, lastPlaceLabel: String?): AnbuNote = AnbuNote(
        id = id,
        type = NoteType.CHECK_IN,
        who = who,
        atMs = atMs,
        line = "${who}께 \"괜찮으세요?\" 하고 여쭸는데 30초 동안 답이 없었어요. 전화해 보세요.",
        placeLabel = lastPlaceLabel,
    )

    /** 주간 일기 한 줄. 차 화면과 가족 화면이 같은 문장을 쓴다. */
    fun weeklyLine(a: DiaryAssessment): String {
        if (!a.baselineReady) {
            return "아직 평소 기준을 만드는 중이에요. 기록이 ${a.weeksUsed}주 쌓였어요."
        }
        val top = a.mostDifferent
        return when (a.overall) {
            Level.SIMILAR -> "이번 주 운전은 평소와 비슷했어요."
            Level.SLIGHTLY_DIFFERENT -> "이번 주는 평소와 조금 달랐어요." + (top?.let { " " + describe(it) } ?: "")
            Level.VERY_DIFFERENT -> "이번 주는 평소와 많이 달랐어요." + (top?.let { " " + describe(it) } ?: "")
        }
    }

    fun describe(m: MetricAssessment): String {
        val more = m.z > 0
        return when (m.metric) {
            Metric.TRIPS -> if (more) "운전한 횟수가 평소보다 많았어요." else "운전한 횟수가 평소보다 적었어요."
            Metric.DISTANCE_KM -> if (more) "운전한 거리가 평소보다 길었어요." else "운전한 거리가 평소보다 짧았어요."
            Metric.NIGHT_SHARE -> "밤 운전이 평소보다 많았어요."
            Metric.HARD_BRAKES_PER_100KM -> "급하게 멈춘 일이 평소보다 많았어요."
            Metric.LATE_BELT_TRIPS -> "안전띠를 늦게 맨 날이 평소보다 많았어요."
            Metric.REROUTES -> "길을 다시 찾은 일이 평소보다 많았어요."
            Metric.CAUTION_ALERTS -> "보호구역이나 역주행 주의 알림이 평소보다 많았어요."
        }
    }

    fun weeklyDetails(w: WeekStats): Map<String, String> = linkedMapOf(
        "주행" to "${w.trips}번",
        "거리" to "${w.distanceKm.roundToInt()}km",
        "밤 운전" to "${(w.nightShare * 100).roundToInt()}%",
        "비 오는 날 운전" to "${(w.rainShare * 100).roundToInt()}%",
        "급하게 멈춤" to "${w.hardBrakes}번",
        "안전띠 늦게 맴" to "${w.lateBeltTrips}번",
        "길 다시 찾음" to "${w.reroutes}번",
        "주의 구간 알림" to "${w.schoolZoneAlerts + w.wrongWayAlerts}번",
    )

    /**
     * 같은 장소로 간 지난 주행들의 중앙값과 비교한다.
     * 지난 주행이 3번 미만이거나 차이가 10분 미만이면 아무 말도 붙이지 않는다.
     */
    fun durationNote(trip: TripSummary, earlierTripsToSamePlace: List<TripSummary>): String? {
        if (earlierTripsToSamePlace.size < 3) return null
        val usual = RobustStats.median(earlierTripsToSamePlace.map { it.durationMs.toDouble() })
        val deltaMin = (trip.durationMs - usual) / 60_000.0
        if (abs(deltaMin) < 10) return null
        val rounded = ((abs(deltaMin) / 5.0).roundToInt() * 5)
        return if (deltaMin > 0) "평소보다 ${rounded}분쯤 더 걸렸어요." else "평소보다 ${rounded}분쯤 일찍 도착했어요."
    }

    /** "15:10쯤 도착 예정". 차 화면과 가족에게 가는 안부가 같은 문구를 쓴다. 5분 단위로 맞춘다. */
    fun etaText(etaAtMs: Long): String = "${clock(roundToFiveMinutes(etaAtMs))}쯤 도착 예정"

    fun clock(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return "%d:%02d".format(t.hour, t.minute)
    }

    private fun roundToFiveMinutes(ms: Long): Long {
        val five = 5 * 60_000L
        return ((ms + five / 2) / five) * five
    }
}

fun Level.label(): String = when (this) {
    Level.SIMILAR -> "평소와 비슷"
    Level.SLIGHTLY_DIFFERENT -> "조금 다름"
    Level.VERY_DIFFERENT -> "많이 다름"
}
