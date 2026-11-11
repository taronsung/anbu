package app.anbu.car

import app.anbu.car.navi.Places
import app.anbu.car.signals.BrakeEvent
import app.anbu.car.signals.DemoScript
import app.anbu.car.signals.DriverBeltEvent
import app.anbu.car.signals.Gear
import app.anbu.car.signals.GearEvent
import app.anbu.car.signals.LowBeamEvent
import app.anbu.car.signals.NaviCautionEvent
import app.anbu.car.signals.CautionKind
import app.anbu.car.signals.OdometerEvent
import app.anbu.car.signals.SignalEvent
import app.anbu.car.signals.SpeedEvent
import app.anbu.car.trip.TripOutput
import app.anbu.car.trip.TripRecorder
import app.anbu.car.trip.TripSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRecorderTest {

    private fun run(events: List<SignalEvent>, tickAfterMs: Long = 30_000): List<TripOutput> {
        val r = TripRecorder(zone = SEOUL)
        val out = ArrayList<TripOutput>()
        for (e in events) out += r.onEvent(e)
        out += r.onTick(events.last().atMs + tickAfterMs)
        return out
    }

    private fun List<TripOutput>.summary(): TripSummary =
        filterIsInstance<TripOutput.Ended>().single().summary

    /** 일정한 속도로 [seconds]초 달리는 이벤트. 0.5초 간격. */
    private fun cruise(startMs: Long, seconds: Int, mps: Float): List<SignalEvent> =
        (0..seconds * 2).map { SpeedEvent(startMs + it * 500L, mps) }

    @Test
    fun `hospital script is summarised into one trip with the expected metrics`() {
        val t0 = at(14)
        val s = run(DemoScript.hospitalTrip(t0)).summary()

        assertEquals("trip starts when the gear leaves P", t0 + 4_000, s.startMs)
        assertEquals("trip ends at the moment P was selected", t0 + 143_000, s.endMs)
        assertTrue("distance from odometer, about 1.5 km but was ${s.distanceKm}", s.distanceKm in 1.3..1.7)
        assertEquals("one hard brake", 1, s.hardBrakes)
        assertEquals("one school zone alert", 1, s.schoolZoneAlerts)
        assertEquals(0, s.wrongWayAlerts)
        assertEquals("one reroute", 1, s.reroutes)
        assertFalse("belt was buckled before moving", s.lateBelt)
        assertEquals(Places.HOSPITAL, s.arrivedPlaceId)
        assertEquals("daytime, lights off", 0L, s.nightMs)
        assertTrue("wiper was on for 40 s of driving but was ${s.rainMs}", s.rainMs in 39_000..41_000)
        assertTrue("moving time about 134 s but was ${s.movingMs}", s.movingMs in 130_000..138_000)
    }

    @Test
    fun `gentle slowing is not a hard brake`() {
        val t0 = at(10)
        val ev = ArrayList<SignalEvent>()
        ev += GearEvent(t0, Gear.DRIVE)
        ev += BrakeEvent(t0, true)
        // 14 m/s 에서 초당 2 m/s 씩 줄인다(약 0.2g).
        var v = 14f
        var t = t0
        ev += cruise(t0, 60, 14f)
        t = t0 + 60_000
        while (v > 0f) {
            v = (v - 1f).coerceAtLeast(0f)
            t += 500
            ev += SpeedEvent(t, v)
        }
        ev += GearEvent(t + 1_000, Gear.PARK)
        assertEquals(0, run(ev).summary().hardBrakes)
    }

    @Test
    fun `sharp slowing without the brake pedal is not counted, and is counted when the pedal signal is missing`() {
        fun script(brake: Boolean?): List<SignalEvent> {
            val t0 = at(10)
            val ev = ArrayList<SignalEvent>()
            ev += GearEvent(t0, Gear.DRIVE)
            if (brake != null) ev += BrakeEvent(t0, brake)
            ev += cruise(t0, 60, 15f)
            val t = t0 + 60_000
            ev += SpeedEvent(t + 500, 12f)
            ev += SpeedEvent(t + 1_000, 9f)
            ev += SpeedEvent(t + 1_500, 6f)
            ev += SpeedEvent(t + 2_000, 3f)
            ev += SpeedEvent(t + 2_500, 0f)
            ev += GearEvent(t + 4_000, Gear.PARK)
            return ev
        }
        assertEquals("pedal released: sensor glitch or coasting data, not counted", 0, run(script(false)).summary().hardBrakes)
        assertEquals("pedal pressed", 1, run(script(true)).summary().hardBrakes)
        assertEquals("no pedal signal on this car: fall back to speed alone", 1, run(script(null)).summary().hardBrakes)
    }

    @Test
    fun `brake pedal signal arriving just after the speed drop still counts, a late one does not`() {
        fun script(brakeDelayMs: Long): List<SignalEvent> {
            val t0 = at(10)
            val ev = ArrayList<SignalEvent>()
            ev += GearEvent(t0, Gear.DRIVE)
            ev += BrakeEvent(t0, false)
            ev += cruise(t0, 60, 15f)
            val t = t0 + 60_000
            ev += SpeedEvent(t + 500, 11f)                    // 급감속이 먼저 보이고
            ev += BrakeEvent(t + 500 + brakeDelayMs, true)    // 브레이크 신호가 뒤따라온다
            // 그 뒤로는 천천히 선다(초당 2 m/s).
            for (i in 1..11) ev += SpeedEvent(t + 500 + i * 500L, 11f - i)
            ev += GearEvent(t + 8_000, Gear.PARK)
            return ev.sortedBy { it.atMs }
        }
        assertEquals(1, run(script(300)).summary().hardBrakes)
        assertEquals(0, run(script(2_000)).summary().hardBrakes)
    }

    @Test
    fun `state learned before recording began is used once recording starts`() {
        val r = TripRecorder(zone = SEOUL)
        val t0 = at(10)
        // 동의 전에 읽어 둔 값: 안전띠를 매지 않았고 와이퍼가 켜져 있다.
        r.prime(beltBuckled = false, brakePressed = false, wiperOn = true, lowBeamOn = null, odometerKm = 100.0)
        val out = ArrayList<TripOutput>()
        out += r.onEvent(GearEvent(t0, Gear.DRIVE))
        cruise(t0, 90, 10f).forEach { out += r.onEvent(it) }
        out += r.onEvent(SpeedEvent(t0 + 91_000, 0f))
        out += r.onEvent(GearEvent(t0 + 92_000, Gear.PARK))
        out += r.onTick(t0 + 120_000)
        val s = out.summary()
        assertTrue("moving off unbuckled is a late belt", s.lateBelt)
        assertEquals("wiper was on the whole way", 1.0, s.rainShare, 0.01)
    }

    @Test
    fun `buckling up after the car is already moving is a late belt`() {
        val t0 = at(10)
        val ev = ArrayList<SignalEvent>()
        ev += DriverBeltEvent(t0, false)
        ev += GearEvent(t0 + 1_000, Gear.DRIVE)
        ev += cruise(t0 + 2_000, 30, 8f)
        ev += DriverBeltEvent(t0 + 33_000, true)
        ev += cruise(t0 + 33_000, 60, 8f)
        ev += SpeedEvent(t0 + 94_000, 0f)
        ev += GearEvent(t0 + 95_000, Gear.PARK)
        assertTrue(run(ev).summary().lateBelt)
    }

    @Test
    fun `night share counts evening hours and any time the low beam is on`() {
        fun trip(t0: Long, lowBeamHalf: Boolean): TripSummary {
            val ev = ArrayList<SignalEvent>()
            ev += GearEvent(t0, Gear.DRIVE)
            ev += cruise(t0, 120, 10f)
            if (lowBeamHalf) ev += LowBeamEvent(t0 + 60_000, true)
            ev += SpeedEvent(t0 + 121_000, 0f)
            ev += GearEvent(t0 + 122_000, Gear.PARK)
            return run(ev.sortedBy { it.atMs }).summary()
        }
        assertEquals("21:00 is night by the clock", 1.0, trip(at(21), false).nightShare, 0.01)
        assertEquals("14:00 with lights off", 0.0, trip(at(14), false).nightShare, 0.01)
        assertEquals("14:00, low beam on for the second half (tunnel, dusk)", 0.5, trip(at(14), true).nightShare, 0.02)
    }

    @Test
    fun `P has to be held for twenty seconds before the trip ends`() {
        val t0 = at(10)
        val ev = ArrayList<SignalEvent>()
        ev += GearEvent(t0, Gear.DRIVE)
        ev += cruise(t0, 60, 10f)
        ev += SpeedEvent(t0 + 61_000, 0f)
        ev += GearEvent(t0 + 62_000, Gear.PARK)          // 잠깐 세움
        ev += GearEvent(t0 + 72_000, Gear.DRIVE)         // 10초 만에 다시 출발
        ev += cruise(t0 + 73_000, 60, 10f)
        ev += SpeedEvent(t0 + 134_000, 0f)
        ev += GearEvent(t0 + 135_000, Gear.PARK)
        val out = run(ev)
        assertEquals("a ten second stop in P does not split the trip", 1, out.filterIsInstance<TripOutput.Ended>().size)
        assertEquals(t0, out.summary().startMs)
        assertEquals(t0 + 135_000, out.summary().endMs)
    }

    @Test
    fun `moving the car a few metres is discarded`() {
        val t0 = at(10)
        val ev = ArrayList<SignalEvent>()
        ev += GearEvent(t0, Gear.REVERSE)
        ev += cruise(t0, 8, 1.5f)
        ev += SpeedEvent(t0 + 9_000, 0f)
        ev += GearEvent(t0 + 10_000, Gear.PARK)
        val out = run(ev)
        assertTrue(out.none { it is TripOutput.Ended })
        assertEquals(1, out.filterIsInstance<TripOutput.Discarded>().size)
    }

    @Test
    fun `distance falls back to integrated speed when the odometer does not move`() {
        val t0 = at(10)
        val ev = ArrayList<SignalEvent>()
        ev += OdometerEvent(t0, 500f)                    // 1km 단위로만 바뀌는 차
        ev += GearEvent(t0, Gear.DRIVE)
        ev += cruise(t0, 60, 10f)                        // 600m
        ev += SpeedEvent(t0 + 61_000, 0f)
        ev += GearEvent(t0 + 62_000, Gear.PARK)
        assertEquals(0.6, run(ev).summary().distanceKm, 0.05)
    }

    @Test
    fun `caution alerts outside a trip are ignored and abort drops the running trip`() {
        val r = TripRecorder(zone = SEOUL)
        val t0 = at(10)
        r.onEvent(NaviCautionEvent(t0, CautionKind.SCHOOL_ZONE))
        r.onEvent(GearEvent(t0 + 1_000, Gear.DRIVE))
        cruise(t0 + 1_000, 60, 10f).forEach { r.onEvent(it) }
        assertTrue(r.inTrip)
        r.abort()
        assertFalse(r.inTrip)
        val out = r.onEvent(GearEvent(t0 + 70_000, Gear.PARK)) + r.onTick(t0 + 100_000)
        assertTrue("nothing is emitted for an aborted trip", out.isEmpty())
        assertNull(out.filterIsInstance<TripOutput.Ended>().firstOrNull())
    }
}
