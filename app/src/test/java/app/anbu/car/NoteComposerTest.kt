package app.anbu.car

import app.anbu.car.anbu.AnbuNote
import app.anbu.car.anbu.NoteComposer
import app.anbu.car.anbu.NoteType
import app.anbu.car.baseline.BaselineModel
import app.anbu.car.baseline.Level
import app.anbu.car.baseline.Weeks
import app.anbu.car.core.ExampleHistory
import app.anbu.car.navi.Places
import app.anbu.car.navi.withDirectionParticle
import app.anbu.car.trip.TripSummary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteComposerTest {

    private val composer = NoteComposer(SEOUL)

    @Test
    fun `arrival note names the saved place, or just says somewhere out`() {
        val saved = composer.arrival("a1", "어머니", at(14, 10), "병원")
        assertEquals("어머니께서 병원에 잘 도착하셨어요.", saved.line)
        assertEquals("병원", saved.placeLabel)
        assertEquals(NoteType.ARRIVAL, saved.type)

        val elsewhere = composer.arrival("a2", "아버지", at(14, 10), null)
        assertEquals("아버지께서 외출지에 잘 도착하셨어요.", elsewhere.line)
        assertEquals(Places.ELSEWHERE_LABEL, elsewhere.placeLabel)
    }

    @Test
    fun `departure note adds the expected arrival time rounded to five minutes`() {
        val n = composer.departure("d1", "아버지", at(14, 59), "병원", etaAtMs = at(15, 8))
        assertEquals("아버지께서 지금 병원으로 출발하셨어요.", n.line)
        assertEquals("15:10쯤 도착 예정", n.etaText)

        val noEta = composer.departure("d2", "아버지", at(14, 59), null, etaAtMs = null)
        assertEquals("아버지께서 지금 출발하셨어요.", noEta.line)
        assertNull(noEta.etaText)
        assertNull(noEta.placeLabel)
    }

    @Test
    fun `direction particle follows the final consonant`() {
        assertEquals("집으로", withDirectionParticle("집"))
        assertEquals("병원으로", withDirectionParticle("병원"))
        assertEquals("딸네로", withDirectionParticle("딸네"))
        assertEquals("마트로", withDirectionParticle("마트"))
        assertEquals("서울로", withDirectionParticle("서울"))
    }

    @Test
    fun `weekly note carries only the level and one line unless details are switched on`() {
        val now = at(14)
        val windows = Weeks.rolling(ExampleHistory.build(now), now, 9)
        val assessment = BaselineModel().assess(windows.first(), windows.drop(1))

        val brief = composer.weekly("w1", "아버지", now, windows.first(), assessment, includeDetails = false)
        assertEquals(Level.SLIGHTLY_DIFFERENT, brief.level)
        assertEquals("이번 주는 평소와 조금 달랐어요. 밤 운전이 평소보다 많았어요.", brief.line)
        assertNull(brief.details)
        assertFalse(brief.toWire("ABC234", SEOUL).has("details"))

        val full = composer.weekly("w1", "아버지", now, windows.first(), assessment, includeDetails = true)
        assertEquals("5번", full.details!!["주행"])
        assertTrue(full.toWire("ABC234", SEOUL).has("details"))
    }

    @Test
    fun `weekly line says so when there is not enough history`() {
        val now = at(14)
        val windows = Weeks.rolling(ExampleHistory.build(now).filter { it.endMs > now - 3 * Weeks.WEEK_MS }, now, 9)
        val assessment = BaselineModel().assess(windows.first(), windows.drop(1))
        assertEquals("아직 평소 기준을 만드는 중이에요. 기록이 2주 쌓였어요.", composer.weeklyLine(assessment))
    }

    @Test
    fun `what leaves the car is the whitelisted fields and never a coordinate`() {
        val notes = listOf(
            composer.arrival("a1", "아버지", at(14, 10), "병원", "평소보다 10분쯤 더 걸렸어요."),
            composer.departure("d1", "아버지", at(13, 50), "병원", at(14, 10)),
            composer.checkIn("c1", "아버지", at(14, 30), "병원을 떠난 뒤"),
        )
        val coordinateLike = Regex("""\d{2,3}\.\d{3,}""")
        for (n in notes) {
            val wire = n.toWire("ABC234", SEOUL)
            assertTrue("unexpected key in ${wire.keySet()}", wire.keySet().all { it in AnbuNote.WIRE_KEYS })
            val text = wire.toString()
            listOf("lat", "lng", "lon", "coord", "speed", "route", "address").forEach {
                assertFalse("$it found in $text", text.contains(it, ignoreCase = true))
            }
            assertFalse("something that looks like a coordinate in $text", coordinateLike.containsMatchIn(text))
        }
        assertEquals("2026-11-11T14:10+09:00", notes[0].toWire("ABC234", SEOUL).getString("at"))
    }

    @Test
    fun `a note round trips through the car's own store format`() {
        val n = composer.arrival("a1", "아버지", at(14, 10), "병원", "평소보다 10분쯤 더 걸렸어요.")
        assertEquals(n, AnbuNote.fromJson(JSONObject(n.toJson().toString())))
    }

    @Test
    fun `trip duration is mentioned only with three earlier trips and a ten minute gap`() {
        fun trip(minutes: Int) = TripSummary(
            id = "t$minutes", startMs = at(10), endMs = at(10) + minutes * 60_000L, distanceKm = 8.0,
            movingMs = minutes * 50_000L, nightMs = 0, rainMs = 0, hardBrakes = 0, lateBelt = false,
            reroutes = 0, schoolZoneAlerts = 0, wrongWayAlerts = 0, arrivedPlaceId = Places.HOSPITAL,
        )
        val usual = listOf(trip(20), trip(22), trip(25))
        assertNull("too few earlier trips", composer.durationNote(trip(50), usual.take(2)))
        assertNull("within ten minutes of usual", composer.durationNote(trip(30), usual))
        assertEquals("평소보다 15분쯤 더 걸렸어요.", composer.durationNote(trip(38), usual))
        assertEquals("평소보다 10분쯤 일찍 도착했어요.", composer.durationNote(trip(11), usual))
    }

    @Test
    fun `no note uses medical or judging words`() {
        val now = at(14)
        val windows = Weeks.rolling(ExampleHistory.build(now), now, 9)
        val assessment = BaselineModel().assess(windows.first(), windows.drop(1))
        val lines = assessment.metrics.map { composer.describe(it) } +
            composer.weeklyLine(assessment) +
            composer.checkIn("c", "아버지", now, null).line
        val banned = listOf("치매", "인지", "질환", "진단", "위험", "능력", "저하", "이상 징후", "노화")
        for (l in lines) banned.forEach { assertFalse("'$it' in '$l'", l.contains(it)) }
    }
}
