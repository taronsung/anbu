package app.anbu.car

import app.anbu.car.anbu.AnbuNote
import app.anbu.car.anbu.NoteType
import app.anbu.car.anbu.Outbox
import app.anbu.car.core.AnbuEngine
import app.anbu.car.core.BlockReason
import app.anbu.car.core.EngineEffect
import app.anbu.car.navi.Places
import app.anbu.car.privacy.ConsentState
import app.anbu.car.privacy.Outbound
import app.anbu.car.privacy.PrivacyGate
import app.anbu.car.signals.DemoScript
import app.anbu.car.signals.Gear
import app.anbu.car.signals.GearEvent
import app.anbu.car.signals.PlaceProximityEvent
import app.anbu.car.signals.SpeedEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 동의 관문. 동의 없이는 아무것도 기록하거나 보내지 않고,
 * 쉬는 동안에는 기록이 멈추고, 삭제하면 모두 사라지는지 확인한다.
 */
class ConsentGateTest {

    @Test
    fun `without consent a whole trip leaves nothing behind`() {
        val rig = Rig()
        rig.play(DemoScript.hospitalTrip(rig.now))
        rig.advance(15_000)

        assertTrue("nothing written to the car's store", rig.store.names.isEmpty())
        assertEquals(0, rig.engine.tripCount)
        assertTrue(rig.outbox.pending().isEmpty())
        assertTrue(rig.transport.posted.isEmpty())
        assertTrue("no arrival countdown is even offered", rig.effectsOf<EngineEffect.ArrivalCountdown>().isEmpty())
        assertTrue(rig.effectsOf<EngineEffect.TripEnded>().isEmpty())
    }

    @Test
    fun `without consent every manual send is refused`() {
        val rig = Rig()
        assertNull(rig.engine.sendDeparture())
        assertFalse(rig.engine.requestArrivalNote())
        assertNull(rig.engine.shareWeekly())
        val blocked = rig.effectsOf<EngineEffect.Blocked>()
        assertEquals(3, blocked.size)
        assertTrue(blocked.all { it.reason == BlockReason.NO_CONSENT })
        assertTrue(rig.outbox.pending().isEmpty())
        assertTrue(rig.store.names.isEmpty())
    }

    @Test
    fun `with consent the trip is recorded and the arrival note goes out after ten seconds`() {
        val rig = Rig()
        rig.consent()
        rig.play(DemoScript.hospitalTrip(rig.now))

        assertEquals(1, rig.engine.tripCount)
        val countdown = rig.effectsOf<EngineEffect.ArrivalCountdown>().single()
        assertEquals("병원", countdown.note.placeLabel)
        assertEquals(10_000, countdown.deadlineWallMs - rig.now)
        assertTrue("not sent yet", rig.outbox.pending().isEmpty())

        rig.advance(9_000)
        assertTrue("still inside the cancel window", rig.outbox.pending().isEmpty())
        rig.advance(1_000)
        assertEquals(1, rig.outbox.pending().size)
        assertEquals(1, rig.outbox.flush(rig.now))

        val wire = JSONObject(rig.transport.posted.single())
        assertEquals("ARRIVAL", wire.getString("type"))
        assertEquals("아버지께서 병원에 잘 도착하셨어요.", wire.getString("line"))
        assertEquals("TEST23", wire.getString("familyCode"))
        assertTrue("only whitelisted keys leave the car", wire.keySet().all { it in AnbuNote.WIRE_KEYS })
    }

    @Test
    fun `saying do not send inside the window sends nothing`() {
        val rig = Rig()
        rig.consent()
        rig.play(DemoScript.hospitalTrip(rig.now))
        rig.advance(4_000)
        rig.engine.cancelArrival()
        rig.advance(20_000)

        assertTrue(rig.outbox.pending().isEmpty())
        assertTrue(rig.engine.recentNotes.isEmpty())
        assertEquals(1, rig.effectsOf<EngineEffect.ArrivalCancelled>().size)
        assertEquals("the trip itself stays in the driver's own diary", 1, rig.engine.tripCount)
    }

    @Test
    fun `arrival sharing switched off records the trip but sends no note`() {
        val rig = Rig()
        rig.consent(shareArrival = false)
        rig.play(DemoScript.hospitalTrip(rig.now))
        rig.advance(15_000)

        assertEquals(1, rig.engine.tripCount)
        assertTrue(rig.effectsOf<EngineEffect.ArrivalCountdown>().isEmpty())
        assertTrue(rig.outbox.pending().isEmpty())
        assertFalse(rig.effectsOf<EngineEffect.TripEnded>().single().notifying)
    }

    @Test
    fun `pause for today stops recording and sending until midnight`() {
        val rig = Rig(at(14))
        rig.consent()
        rig.engine.pauseToday()
        assertEquals("paused until local midnight", at(14) + 10 * 3600_000L, rig.engine.consent.pausedUntilMs)

        rig.play(DemoScript.hospitalTrip(rig.now))
        rig.advance(15_000)
        assertEquals(0, rig.engine.tripCount)
        assertTrue(rig.outbox.pending().isEmpty())
        assertNull("a manual note is refused too", rig.engine.sendDeparture())
        assertEquals(BlockReason.PAUSED, rig.effectsOf<EngineEffect.Blocked>().last().reason)

        // 자정이 지나면 다시 기록한다.
        rig.now = at(14) + 11 * 3600_000L
        rig.play(DemoScript.hospitalTrip(rig.now))
        assertEquals(1, rig.engine.tripCount)
    }

    @Test
    fun `pausing in the middle of a drive discards that drive and any pending note`() {
        val rig = Rig()
        rig.consent()
        val script = DemoScript.hospitalTrip(rig.now)
        val half = script.size / 2
        rig.play(script.take(half))
        assertTrue(rig.engine.inTrip)

        rig.engine.pauseToday()
        rig.play(script.drop(half))
        rig.advance(15_000)

        assertFalse(rig.engine.inTrip)
        assertEquals(0, rig.engine.tripCount)
        assertTrue(rig.outbox.pending().isEmpty())
    }

    @Test
    fun `skipping one drive leaves no record and no note, and ends by itself once parked`() {
        val rig = Rig()
        rig.consent()
        rig.engine.onSignal(GearEvent(rig.now, Gear.PARK))
        rig.engine.pauseThisTrip()
        assertTrue(rig.engine.gate.isPaused(rig.now))

        // 다른 사람이 병원까지 몰고 가서 세운다.
        rig.play(DemoScript.hospitalTrip(rig.now))
        rig.advance(15_000)
        assertEquals("the skipped drive is not in the diary's base", 0, rig.engine.tripCount)
        assertTrue(rig.effectsOf<EngineEffect.ArrivalCountdown>().isEmpty())
        assertTrue(rig.outbox.pending().isEmpty())
        assertFalse("parked for twenty seconds, so the pause is over", rig.engine.consent.skipTrip)
        assertEquals(1, rig.effectsOf<EngineEffect.TripSkipEnded>().size)

        // 그다음 운전은 다시 기록하고 알린다.
        rig.now += 3_600_000
        rig.play(DemoScript.hospitalTrip(rig.now))
        assertEquals(1, rig.engine.tripCount)
        assertEquals(1, rig.effectsOf<EngineEffect.ArrivalCountdown>().size)
    }

    @Test
    fun `skip this drive pressed while moving drops that drive, and waits in P do not end it early`() {
        val rig = Rig()
        rig.consent()
        val t0 = rig.now
        val script = DemoScript.hospitalTrip(t0)
        rig.play(script.filter { it.atMs <= t0 + 60_000 })
        rig.engine.pauseThisTrip()
        // P에 두었다가 20초가 되기 전에 다시 출발한다.
        val t1 = rig.now
        rig.play(listOf(GearEvent(t1 + 1_000, Gear.PARK)))
        rig.advance(10_000)
        rig.play(listOf(GearEvent(rig.now + 500, Gear.DRIVE)))
        rig.advance(30_000)
        assertTrue("still skipping", rig.engine.consent.skipTrip)
        rig.play(listOf(GearEvent(rig.now + 500, Gear.PARK)))
        rig.advance(21_000)
        assertFalse(rig.engine.consent.skipTrip)
        assertEquals(0, rig.engine.tripCount)
        assertTrue(rig.outbox.pending().isEmpty())
    }

    @Test
    fun `skip this drive survives a restart and is lifted by resume`() {
        val rig = Rig()
        rig.consent()
        rig.engine.pauseThisTrip()
        val again = AnbuEngine(rig.store, rig.outbox, { rig.now }, SEOUL)
        assertTrue(again.consent.skipTrip)
        assertTrue(again.gate.isPaused(rig.now))
        again.resumeNow()
        assertFalse(again.consent.skipTrip)
        assertFalse(again.gate.isPaused(rig.now))
    }

    @Test
    fun `resume makes the next trip count again`() {
        val rig = Rig()
        rig.consent()
        rig.engine.pauseToday()
        rig.engine.resumeNow()
        rig.play(DemoScript.hospitalTrip(rig.now))
        assertEquals(1, rig.engine.tripCount)
    }

    @Test
    fun `delete everything wipes the store, the outbox and the consent`() {
        val rig = Rig()
        rig.consent()
        rig.engine.seedExampleHistory()
        rig.play(DemoScript.hospitalTrip(rig.now))
        rig.engine.sendDeparture()
        rig.advance(10_000)
        assertTrue(rig.store.names.containsAll(listOf(AnbuEngine.BLOB_CONSENT, AnbuEngine.BLOB_TRIPS, AnbuEngine.BLOB_NOTES, Outbox.NAME)))
        assertTrue(rig.engine.tripCount > 40)

        val code = rig.engine.deleteEverything()

        assertEquals("the relay is asked to forget this family code", "TEST23", code)
        assertTrue("nothing is left in the car's store", rig.store.names.isEmpty())
        assertTrue(rig.outbox.pending().isEmpty())
        assertEquals(ConsentState(), rig.engine.consent)
        assertEquals(0, rig.engine.tripCount)
        assertTrue(rig.engine.recentNotes.isEmpty())
        assertEquals("saved coordinates go back to the built in defaults", Places.defaults, rig.engine.places)

        // 지운 뒤에는 다시 동의하기 전까지 아무것도 남지 않는다.
        rig.play(DemoScript.hospitalTrip(rig.now + 60_000))
        assertTrue(rig.store.names.isEmpty())
    }

    @Test
    fun `state survives a restart because it is read back from the store`() {
        val rig = Rig()
        rig.consent()
        rig.play(DemoScript.hospitalTrip(rig.now))
        rig.advance(10_000)

        val reborn = AnbuEngine(rig.store, rig.outbox, { rig.now }, SEOUL)
        assertTrue(reborn.consent.granted)
        assertEquals("TEST23", reborn.consent.familyCode)
        assertEquals(1, reborn.tripCount)
        assertEquals(NoteType.ARRIVAL, reborn.recentNotes.single().type)

        rig.engine.shareWeekly()
        val again = AnbuEngine(rig.store, rig.outbox, { rig.now }, SEOUL)
        assertEquals("the weekly decision is remembered for the day", app.anbu.car.core.WeeklyDecision.SHARED, again.diary().decision)
    }

    /** 마트 근처에 들어선 뒤 P에 두는 주행. */
    private fun driveToMart(rig: Rig) {
        val t0 = rig.now
        val ev = buildList {
            add(GearEvent(t0, Gear.DRIVE))
            for (i in 0..240) add(SpeedEvent(t0 + i * 500L, 10f))
            add(PlaceProximityEvent(t0 + 119_000, Places.MART, entered = true))
            add(SpeedEvent(t0 + 121_000, 0f))
            add(GearEvent(t0 + 122_000, Gear.PARK))
        }.sortedBy { it.atMs }
        rig.play(ev)
        rig.advance(25_000)
    }

    @Test
    fun `place proximity is ignored unless the driver switched it on`() {
        val on = Rig().also { it.consent(useProximity = true); driveToMart(it) }
        assertEquals("마트", on.effectsOf<EngineEffect.ArrivalCountdown>().single().note.placeLabel)

        val off = Rig().also { it.consent(useProximity = false); driveToMart(it) }
        assertTrue("without the proximity option the stop has no name, so nothing is offered", off.effectsOf<EngineEffect.ArrivalCountdown>().isEmpty())
    }

    @Test
    fun `a stop at a place without a name is recorded but not announced by default`() {
        val rig = Rig()
        rig.consent(useProximity = false)
        driveToMart(rig)
        rig.advance(15_000)

        assertEquals("the trip stays in the driver's own diary", 1, rig.engine.tripCount)
        val ended = rig.effectsOf<EngineEffect.TripEnded>().single()
        assertFalse(ended.notifying)
        assertTrue(ended.unnamedPlace)
        assertTrue(rig.effectsOf<EngineEffect.ArrivalCountdown>().isEmpty())
        assertTrue(rig.outbox.pending().isEmpty())
    }

    @Test
    fun `places without a name are announced only after the driver switched that on`() {
        val rig = Rig()
        rig.consent(useProximity = false)
        assertFalse("off by default", rig.engine.consent.shareUnnamedArrival)
        rig.engine.updateConsent { it.copy(shareUnnamedArrival = true) }
        driveToMart(rig)

        val countdown = rig.effectsOf<EngineEffect.ArrivalCountdown>().single()
        assertEquals(Places.ELSEWHERE_LABEL, countdown.note.placeLabel)
        assertTrue("still inside the ten second window", rig.outbox.pending().isEmpty())
        rig.advance(10_000)
        assertEquals("아버지께서 외출지에 잘 도착하셨어요.", JSONObject(rig.outbox.pending().single().wire).getString("line"))
        assertFalse(rig.effectsOf<EngineEffect.TripEnded>().single().unnamedPlace)
    }

    @Test
    fun `trips older than nine weeks are dropped when a new trip is saved`() {
        val rig = Rig()
        rig.consent()
        rig.engine.seedExampleHistory()
        val before = rig.engine.tripCount
        assertEquals(45, before)

        rig.now += 14L * 24 * 3600 * 1000            // 두 주 뒤
        rig.play(DemoScript.hospitalTrip(rig.now))
        assertTrue("the two oldest weeks (10 records) have aged out", rig.engine.tripCount <= before - 10 + 1)
    }

    @Test
    fun `the gate itself`() {
        var state = ConsentState()
        val gate = PrivacyGate { state }
        val now = at(14)
        assertFalse(gate.canRecord(now))
        assertTrue(Outbound.values().none { gate.canSend(it, now) })

        state = ConsentState(granted = true, familyCode = "ABC234", shareArrival = true, checkInAlert = false)
        assertTrue(gate.canRecord(now))
        assertTrue(gate.canSend(Outbound.ARRIVAL, now))
        assertTrue(gate.canSend(Outbound.WEEKLY, now))
        assertFalse("check-in alert was not agreed to", gate.canSend(Outbound.CHECK_IN, now))
        assertFalse(gate.canUseProximity(now))

        state = state.copy(pausedUntilMs = now + 1)
        assertFalse(gate.canRecord(now))
        assertTrue(Outbound.values().none { gate.canSend(it, now) })
        assertTrue("the pause ends by itself", gate.canRecord(now + 1))

        state = state.copy(pausedUntilMs = 0, familyCode = "")
        assertFalse("no family code, nowhere to send", gate.canSend(Outbound.ARRIVAL, now))
        assertNotNull(state)
    }
}
