package app.anbu.car

import app.anbu.car.baseline.BaselineModel
import app.anbu.car.baseline.Level
import app.anbu.car.baseline.Metric
import app.anbu.car.baseline.RobustStats
import app.anbu.car.baseline.WeekStats
import app.anbu.car.baseline.Weeks
import app.anbu.car.core.ExampleHistory
import app.anbu.car.trip.TripSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineTest {

    private val min = 60_000L

    private fun week(
        index: Int,
        trips: Int = 5,
        km: Double = 50.0,
        movingMin: Int = 120,
        nightMin: Int = 10,
        hardBrakes: Int = 1,
        lateBelt: Int = 0,
        reroutes: Int = 1,
        cautions: Int = 5,
    ) = WeekStats(
        weekStartMs = at(14) - (index + 1) * Weeks.WEEK_MS,
        trips = trips, distanceKm = km, movingMs = movingMin * min, nightMs = nightMin * min, rainMs = 0,
        hardBrakes = hardBrakes, lateBeltTrips = lateBelt, reroutes = reroutes,
        schoolZoneAlerts = cautions, wrongWayAlerts = 0, hasSynthetic = false,
    )

    /** 조금씩 다른 평범한 8주. */
    private fun usualEightWeeks(): List<WeekStats> = listOf(
        week(1, trips = 5, km = 48.0, nightMin = 8), week(2, trips = 6, km = 55.0, nightMin = 12),
        week(3, trips = 5, km = 50.0, nightMin = 10), week(4, trips = 4, km = 44.0, nightMin = 6),
        week(5, trips = 5, km = 52.0, nightMin = 10), week(6, trips = 6, km = 57.0, nightMin = 14),
        week(7, trips = 5, km = 49.0, nightMin = 9), week(8, trips = 5, km = 51.0, nightMin = 11),
    )

    @Test
    fun `median and MAD`() {
        assertEquals(3.0, RobustStats.median(listOf(5.0, 1.0, 3.0)), 1e-9)
        assertEquals(2.5, RobustStats.median(listOf(4.0, 1.0, 2.0, 3.0)), 1e-9)
        // 중앙값 3, 편차 [2,1,0,1,97] 의 중앙값 1. 튀는 값 100이 폭을 키우지 못한다.
        assertEquals(1.0, RobustStats.mad(listOf(1.0, 2.0, 3.0, 4.0, 100.0)), 1e-9)
    }

    @Test
    fun `fewer than four past weeks means no baseline yet`() {
        val a = BaselineModel().assess(week(0), usualEightWeeks().take(3))
        assertFalse(a.baselineReady)
        assertEquals(3, a.weeksUsed)
        assertEquals("without a baseline nothing is called different", Level.SIMILAR, a.overall)
    }

    @Test
    fun `an ordinary week is similar`() {
        val a = BaselineModel().assess(week(0, trips = 5, km = 53.0, nightMin = 11), usualEightWeeks())
        assertTrue(a.baselineReady)
        assertEquals(8, a.weeksUsed)
        assertEquals(Level.SIMILAR, a.overall)
    }

    @Test
    fun `noticeably more night driving is slightly different, much more is very different`() {
        val model = BaselineModel()
        // 평소 밤 운전 비율은 약 8%. 최소 폭 0.06 기준으로 2배, 3.5배 넘는 지점을 본다.
        val slightly = model.assess(week(0, nightMin = 30), usualEightWeeks())   // 25%
        assertEquals(Level.SLIGHTLY_DIFFERENT, slightly.overall)
        assertEquals(Metric.NIGHT_SHARE, slightly.mostDifferent?.metric)

        val very = model.assess(week(0, nightMin = 60), usualEightWeeks())       // 50%
        assertEquals(Level.VERY_DIFFERENT, very.overall)
        assertEquals(Metric.NIGHT_SHARE, very.mostDifferent?.metric)
    }

    @Test
    fun `fewer hard brakes or less night driving than usual is not flagged`() {
        val a = BaselineModel().assess(week(0, hardBrakes = 0, nightMin = 0, reroutes = 0, cautions = 0), usualEightWeeks())
        assertEquals(Level.SIMILAR, a.overall)
    }

    @Test
    fun `driving much less than usual is flagged, because trip count is two sided`() {
        val a = BaselineModel().assess(week(0, trips = 0, km = 0.0, movingMin = 0, nightMin = 0, hardBrakes = 0), usualEightWeeks())
        assertTrue(a.overall != Level.SIMILAR)
        val trips = a.metrics.first { it.metric == Metric.TRIPS }
        assertTrue("z is negative when below usual", trips.z < 0)
        assertTrue(trips.level != Level.SIMILAR)
    }

    @Test
    fun `one outlier week does not move the baseline`() {
        val history = usualEightWeeks().toMutableList()
        history[2] = week(3, trips = 14, km = 620.0, nightMin = 90, movingMin = 600)   // 명절 장거리
        val a = BaselineModel().assess(week(0, trips = 5, km = 53.0, nightMin = 11), history)
        assertEquals("with median and MAD an ordinary week stays ordinary", Level.SIMILAR, a.overall)
    }

    @Test
    fun `only the most recent eight weeks are used`() {
        val twelve = (1..12).map { week(it) }
        assertEquals(8, BaselineModel().assess(week(0), twelve).weeksUsed)
    }

    @Test
    fun `rolling windows are seven days long and stop at the first record`() {
        val now = at(14)
        fun trip(daysAgo: Double) = TripSummary(
            id = "t$daysAgo", startMs = 0, endMs = now - (daysAgo * 24 * 3600 * 1000).toLong(),
            distanceKm = 10.0, movingMs = 20 * min, nightMs = 0, rainMs = 0, hardBrakes = 0,
            lateBelt = false, reroutes = 0, schoolZoneAlerts = 0, wrongWayAlerts = 0,
        )
        val windows = Weeks.rolling(listOf(trip(0.5), trip(6.9), trip(7.1), trip(20.0)), now, count = 9)
        assertEquals("windows 0, 1 and 2 hold records; older ones are not invented", 3, windows.size)
        assertEquals(2, windows[0].trips)
        assertEquals(1, windows[1].trips)
        assertEquals("a week with no driving still counts as a week", 1, windows[2].trips)
        assertEquals(20.0, windows[0].distanceKm, 1e-9)
    }

    @Test
    fun `the example history shown in demos reads as slightly different because of night driving`() {
        val now = at(14)
        val trips = ExampleHistory.build(now)
        assertTrue("every example record is marked synthetic", trips.all { it.synthetic })
        val windows = Weeks.rolling(trips, now, count = 9)
        assertEquals(9, windows.size)
        val a = BaselineModel().assess(windows.first(), windows.drop(1))
        assertTrue(a.baselineReady)
        assertEquals(8, a.weeksUsed)
        val dump = a.metrics.joinToString { "${it.metric}=%.3f (usual %.3f, z %.2f)".format(it.value, it.median, it.z) }
        assertEquals(dump, Level.SLIGHTLY_DIFFERENT, a.overall)
        assertEquals(Metric.NIGHT_SHARE, a.mostDifferent?.metric)
        assertEquals(
            "nothing else is flagged",
            listOf(Metric.NIGHT_SHARE), a.metrics.filter { it.level != Level.SIMILAR }.map { it.metric },
        )
    }
}
