package app.anbu.car.baseline

import app.anbu.car.trip.TripSummary
import kotlin.math.abs
import kotlin.math.max

/** 가족과 본인에게 보여 주는 세 단계. 건강 판단이 아니라 "평소와 얼마나 다른가"만 말한다. */
enum class Level { SIMILAR, SLIGHTLY_DIFFERENT, VERY_DIFFERENT }

enum class Metric(val upOnly: Boolean, val scaleFloor: Double) {
    /** 주행 횟수. 늘어도 줄어도 "다름"이다. */
    TRIPS(upOnly = false, scaleFloor = 1.5),
    /** 주행 거리(km). */
    DISTANCE_KM(upOnly = false, scaleFloor = 8.0),
    /** 야간 주행 시간 비율(0~1). 늘어난 쪽만 본다. */
    NIGHT_SHARE(upOnly = true, scaleFloor = 0.06),
    /** 100km당 급감속 횟수. */
    HARD_BRAKES_PER_100KM(upOnly = true, scaleFloor = 1.2),
    /** 안전띠를 늦게 맨 주행 수. */
    LATE_BELT_TRIPS(upOnly = true, scaleFloor = 0.8),
    /** 경로 재탐색 횟수. */
    REROUTES(upOnly = true, scaleFloor = 1.0),
    /** 보호구역·역주행 주의 알림 횟수. */
    CAUTION_ALERTS(upOnly = true, scaleFloor = 1.0),
}

/** 7일 구간 하나의 집계. */
data class WeekStats(
    val weekStartMs: Long,
    val trips: Int,
    val distanceKm: Double,
    val movingMs: Long,
    val nightMs: Long,
    val rainMs: Long,
    val hardBrakes: Int,
    val lateBeltTrips: Int,
    val reroutes: Int,
    val schoolZoneAlerts: Int,
    val wrongWayAlerts: Int,
    val hasSynthetic: Boolean,
) {
    val nightShare: Double get() = if (movingMs > 0) nightMs.toDouble() / movingMs else 0.0
    val rainShare: Double get() = if (movingMs > 0) rainMs.toDouble() / movingMs else 0.0
    val hardBrakesPer100Km: Double get() = if (distanceKm >= 1.0) hardBrakes * 100.0 / distanceKm else 0.0

    fun value(m: Metric): Double = when (m) {
        Metric.TRIPS -> trips.toDouble()
        Metric.DISTANCE_KM -> distanceKm
        Metric.NIGHT_SHARE -> nightShare
        Metric.HARD_BRAKES_PER_100KM -> hardBrakesPer100Km
        Metric.LATE_BELT_TRIPS -> lateBeltTrips.toDouble()
        Metric.REROUTES -> reroutes.toDouble()
        Metric.CAUTION_ALERTS -> (schoolZoneAlerts + wrongWayAlerts).toDouble()
    }

    companion object {
        fun empty(weekStartMs: Long) = WeekStats(weekStartMs, 0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, false)
    }
}

object Weeks {
    const val WEEK_MS = 7L * 24 * 3600 * 1000

    /**
     * 지금부터 거꾸로 7일씩 끊은 구간별 집계. 0번이 최근 7일, 1번이 그 앞 7일이다.
     *
     * 달력 주(월~일)로 끊으면 주 초에는 이번 주 기록이 적어 "평소보다 덜 운전함"으로
     * 잘못 나오므로, 항상 길이가 같은 7일 구간끼리 비교한다.
     * 주행이 한 번도 없던 구간도 0으로 포함한다. 단, 첫 기록보다 앞선 구간은 뺀다.
     */
    fun rolling(trips: List<TripSummary>, nowMs: Long, count: Int): List<WeekStats> {
        val firstEnd = trips.minOfOrNull { it.endMs } ?: return listOf(WeekStats.empty(nowMs - WEEK_MS))
        val out = ArrayList<WeekStats>(count)
        for (k in 0 until count) {
            val end = nowMs - k * WEEK_MS
            val start = end - WEEK_MS
            if (k > 0 && end <= firstEnd) break
            out += summarize(start, trips.filter { it.endMs > start && it.endMs <= end })
        }
        return out
    }

    fun summarize(startMs: Long, list: List<TripSummary>) = WeekStats(
        weekStartMs = startMs,
        trips = list.size,
        distanceKm = list.sumOf { it.distanceKm },
        movingMs = list.sumOf { it.movingMs },
        nightMs = list.sumOf { it.nightMs },
        rainMs = list.sumOf { it.rainMs },
        hardBrakes = list.sumOf { it.hardBrakes },
        lateBeltTrips = list.count { it.lateBelt },
        reroutes = list.sumOf { it.reroutes },
        schoolZoneAlerts = list.sumOf { it.schoolZoneAlerts },
        wrongWayAlerts = list.sumOf { it.wrongWayAlerts },
        hasSynthetic = list.any { it.synthetic },
    )
}

object RobustStats {
    fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** 중앙값 절대 편차. */
    fun mad(values: List<Double>): Double {
        val m = median(values)
        return median(values.map { abs(it - m) })
    }
}

data class MetricAssessment(
    val metric: Metric,
    val value: Double,
    val median: Double,
    /** 평소 대비 편차(강건 z 점수). 양수면 평소보다 많다. */
    val z: Double,
    val level: Level,
)

data class DiaryAssessment(
    /** 기준선을 만들 만큼 지난 기록이 쌓였는가. */
    val baselineReady: Boolean,
    val weeksUsed: Int,
    val overall: Level,
    val metrics: List<MetricAssessment>,
) {
    /** 가장 많이 달라진 항목. 한 줄 문장을 만들 때 쓴다. */
    val mostDifferent: MetricAssessment?
        get() = metrics.filter { it.level != Level.SIMILAR }.maxByOrNull { abs(it.z) }
}

/**
 * 개인 기준선. 최근 [windowWeeks]주(기본 8주)의 주간 값에서 중앙값과 MAD를 구하고,
 * 이번 주 값이 거기서 얼마나 떨어져 있는지로 세 단계를 정한다.
 *
 * 평균·표준편차 대신 중앙값·MAD를 쓰는 이유는, 명절 장거리 한 번 같은 튀는 주가
 * 기준선을 끌고 가지 않게 하기 위해서다.
 */
class BaselineModel(
    val windowWeeks: Int = 8,
    val minWeeks: Int = 4,
    val slightZ: Double = 2.0,
    val veryZ: Double = 3.5,
) {
    fun assess(current: WeekStats, history: List<WeekStats>): DiaryAssessment {
        val window = history.sortedByDescending { it.weekStartMs }.take(windowWeeks)
        if (window.size < minWeeks) {
            return DiaryAssessment(
                baselineReady = false,
                weeksUsed = window.size,
                overall = Level.SIMILAR,
                metrics = Metric.values().map {
                    MetricAssessment(it, current.value(it), Double.NaN, 0.0, Level.SIMILAR)
                },
            )
        }
        val metrics = Metric.values().map { m ->
            val xs = window.map { it.value(m) }
            val med = RobustStats.median(xs)
            // 1.4826은 정규분포에서 MAD를 표준편차 크기로 맞추는 상수다.
            // 지난 주들이 거의 같아 MAD가 0에 가까우면 항목별 최소 폭을 쓴다.
            val scale = max(1.4826 * RobustStats.mad(xs), m.scaleFloor)
            val z = (current.value(m) - med) / scale
            val effective = if (m.upOnly) max(z, 0.0) else abs(z)
            val level = when {
                effective >= veryZ -> Level.VERY_DIFFERENT
                effective >= slightZ -> Level.SLIGHTLY_DIFFERENT
                else -> Level.SIMILAR
            }
            MetricAssessment(m, current.value(m), med, z, level)
        }
        val overall = metrics.maxOf { it.level }
        return DiaryAssessment(true, window.size, overall, metrics)
    }
}
