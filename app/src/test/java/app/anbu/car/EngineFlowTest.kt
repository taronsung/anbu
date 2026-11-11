package app.anbu.car

import app.anbu.car.anbu.NoteType
import app.anbu.car.anbu.Outbox
import app.anbu.car.baseline.Level
import app.anbu.car.core.CheckInReason
import app.anbu.car.core.EngineEffect
import app.anbu.car.core.WeeklyDecision
import app.anbu.car.navi.Places
import app.anbu.car.privacy.InMemoryBlobStore
import app.anbu.car.signals.AirbagEvent
import app.anbu.car.signals.DemoScript
import app.anbu.car.signals.Gear
import app.anbu.car.signals.GearEvent
import app.anbu.car.signals.NaviEtaEvent
import app.anbu.car.signals.SignalClock
import app.anbu.car.signals.SignalEvent
import app.anbu.car.signals.SimulatedSource
import app.anbu.car.signals.SpeedEvent
import app.anbu.car.voice.VoiceCommand
import app.anbu.car.voice.VoiceCommands
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineFlowTest {

    // ---------------------------------------------------------------- F2 출발 한마디

    @Test
    fun `departure note uses the chosen place and the navigation ETA`() {
        val rig = Rig(at(14, 59))
        rig.consent()
        rig.engine.setDestination(rig.engine.places.first { it.id == Places.HOSPITAL })
        rig.engine.onSignal(NaviEtaEvent(rig.now, remainSeconds = 9 * 60, remainMeters = 4_000))

        val note = rig.engine.sendDeparture()!!
        assertEquals("아버지께서 지금 병원으로 출발하셨어요.", note.line)
        assertEquals("15:10쯤 도착 예정", note.etaText)
        assertEquals("sent at once, no countdown", 1, rig.outbox.pending().size)
    }

    @Test
    fun `a stale ETA is not reused`() {
        val rig = Rig(at(14))
        rig.consent()
        rig.engine.onSignal(NaviEtaEvent(rig.now, 600, 4_000))     // 10분 뒤 도착 예정
        rig.now += 8 * 60_000
        assertEquals("still on the way: the ETA given at route start holds", "14:10쯤 도착 예정", rig.engine.sendDeparture()!!.etaText)
        rig.now += 8 * 60_000                                          // 예정 시각이 6분 지났다
        assertNull(rig.engine.sendDeparture()!!.etaText)
    }

    // ---------------------------------------------------------------- F1 직접 요청한 도착 안부

    @Test
    fun `asking by voice for an arrival note still gives the ten second window`() {
        val rig = Rig()
        rig.consent()
        assertTrue(rig.engine.requestArrivalNote())
        assertNotNull(rig.engine.pendingArrival)
        assertEquals("외출지", rig.engine.pendingArrival!!.note.placeLabel)
        rig.advance(10_000)
        assertNull(rig.engine.pendingArrival)
        assertEquals(1, rig.outbox.pending().size)
    }

    @Test
    fun `send now skips the rest of the countdown`() {
        val rig = Rig()
        rig.consent()
        rig.play(DemoScript.hospitalTrip(rig.now))
        rig.engine.sendArrivalNow()
        assertEquals(1, rig.outbox.pending().size)
        rig.advance(20_000)
        assertEquals("and it is not sent twice", 1, rig.outbox.pending().size)
    }

    @Test
    fun `switching sharing off during the countdown stops the note`() {
        val rig = Rig()
        rig.consent()
        rig.play(DemoScript.hospitalTrip(rig.now))
        rig.engine.updateConsent { it.copy(shareArrival = false) }
        rig.advance(15_000)
        assertTrue(rig.outbox.pending().isEmpty())
    }

    // ---------------------------------------------------------------- F3 주간 일기

    @Test
    fun `weekly diary is shown to the driver first and shared only on request`() {
        val rig = Rig()
        rig.consent()
        rig.engine.seedExampleHistory()

        val diary = rig.engine.diary()
        assertEquals(Level.SLIGHTLY_DIFFERENT, diary.assessment.overall)
        assertTrue(diary.hasSynthetic)
        assertNull("nothing decided yet", diary.decision)
        assertTrue("looking at the diary sends nothing", rig.outbox.pending().isEmpty())

        rig.engine.keepWeeklyPrivate()
        assertEquals(WeeklyDecision.PRIVATE, rig.engine.diary().decision)
        assertTrue(rig.outbox.pending().isEmpty())

        val note = rig.engine.shareWeekly()!!
        assertEquals(NoteType.WEEKLY, note.type)
        assertNull("details are off by default", note.details)
        assertEquals(WeeklyDecision.SHARED, rig.engine.diary().decision)
        val wire = JSONObject(rig.outbox.pending().single().wire)
        assertEquals("SLIGHTLY_DIFFERENT", wire.getString("level"))
        assertFalse(wire.has("details"))
    }

    @Test
    fun `details go along only when the driver switched them on`() {
        val rig = Rig()
        rig.consent()
        rig.engine.seedExampleHistory()
        rig.engine.updateConsent { it.copy(shareWeeklyDetails = true) }
        val note = rig.engine.shareWeekly()!!
        assertEquals("55km", note.details!!["거리"])
    }

    @Test
    fun `update consent cannot be used to grant consent, change the code or lift a pause`() {
        val rig = Rig()
        rig.engine.updateConsent { it.copy(granted = true, familyCode = "HACKED") }
        assertFalse(rig.engine.consent.granted)

        rig.consent()
        rig.engine.pauseToday()
        val paused = rig.engine.consent.pausedUntilMs
        rig.engine.updateConsent { it.copy(familyCode = "OTHER1", pausedUntilMs = 0, shareTripDuration = true) }
        assertEquals("TEST23", rig.engine.consent.familyCode)
        assertEquals(paused, rig.engine.consent.pausedUntilMs)
        assertTrue(rig.engine.consent.shareTripDuration)
    }

    // ---------------------------------------------------------------- F4 괜찮으세요

    @Test
    fun `airbag deployment asks, and thirty seconds of silence tells the family`() {
        val rig = Rig()
        rig.consent()
        rig.engine.onSignal(AirbagEvent(rig.now, deployed = true))
        val asked = rig.effectsOf<EngineEffect.CheckInAsked>().single()
        assertEquals(CheckInReason.AIRBAG, asked.reason)
        assertEquals(30_000, asked.deadlineWallMs - rig.now)

        rig.advance(29_000)
        assertTrue(rig.outbox.pending().isEmpty())
        rig.advance(1_000)
        assertTrue(rig.effectsOf<EngineEffect.CheckInEscalated>().single().sentToFamily)
        val wire = JSONObject(rig.outbox.pending().single().wire)
        assertEquals("CHECK_IN", wire.getString("type"))
        assertFalse("no place label unless agreed beforehand", wire.has("placeLabel"))
    }

    @Test
    fun `answering I am fine sends nothing`() {
        val rig = Rig()
        rig.consent()
        rig.engine.onSignal(AirbagEvent(rig.now, true))
        rig.advance(5_000)
        rig.engine.answerCheckInOk()
        rig.advance(60_000)
        assertTrue(rig.outbox.pending().isEmpty())
        assertEquals(1, rig.effectsOf<EngineEffect.CheckInAnswered>().size)
    }

    @Test
    fun `asking for help does not wait out the thirty seconds`() {
        val rig = Rig()
        rig.consent()
        rig.engine.onSignal(AirbagEvent(rig.now, true))
        rig.engine.answerCheckInNeedHelp()
        assertEquals(1, rig.outbox.pending().size)
    }

    @Test
    fun `hard stop followed by a minute standing still out of P asks`() {
        val rig = Rig()
        rig.consent()
        val script = DemoScript.hardStop(rig.now)
        val stopAt = rig.now + 8_500
        rig.play(script.filter { it.atMs <= stopAt + 50_000 })
        assertTrue("not yet a full minute", rig.effectsOf<EngineEffect.CheckInAsked>().isEmpty())
        rig.play(script.filter { it.atMs > stopAt + 50_000 })
        assertEquals(CheckInReason.HARD_STOP, rig.effectsOf<EngineEffect.CheckInAsked>().single().reason)
    }

    @Test
    fun `hard stop then parking, or driving on, does not ask`() {
        fun run(tail: (Long) -> List<SignalEvent>): Rig {
            val rig = Rig()
            rig.consent()
            val t0 = rig.now
            rig.play(DemoScript.hardStop(t0).filter { it.atMs <= t0 + 12_000 } + tail(t0))
            rig.advance(90_000)
            return rig
        }
        val parked = run { t0 -> listOf(GearEvent(t0 + 15_000, Gear.PARK)) }
        assertTrue(parked.effectsOf<EngineEffect.CheckInAsked>().isEmpty())

        val droveOn = run { t0 -> (0..60).map { SpeedEvent(t0 + 15_000 + it * 1_000L, 9f) } }
        assertTrue(droveOn.effectsOf<EngineEffect.CheckInAsked>().isEmpty())
    }

    @Test
    fun `check in is off unless the driver agreed to it`() {
        val rig = Rig()
        rig.consent(checkInAlert = false)
        rig.engine.onSignal(AirbagEvent(rig.now, true))
        rig.advance(40_000)
        assertTrue(rig.effectsOf<EngineEffect.CheckInAsked>().isEmpty())
        assertTrue(rig.outbox.pending().isEmpty())
    }

    @Test
    fun `the alert names the place the car left, and only with prior agreement`() {
        val rig = Rig()
        rig.consent()
        rig.engine.updateConsent { it.copy(checkInPlaceLabel = true) }
        rig.play(DemoScript.hospitalTrip(rig.now))
        rig.engine.cancelArrival()

        // 병원을 떠나 달리는 중에 에어백이 작동했다.
        val t0 = rig.now + 60_000
        rig.play(listOf(GearEvent(t0, Gear.DRIVE)) + (1..40).map { SpeedEvent(t0 + it * 500L, 12f) })
        rig.engine.onSignal(AirbagEvent(rig.now, true))
        rig.advance(30_000)
        val wire = JSONObject(rig.outbox.pending().single().wire)
        assertEquals("it is where the car came from, not where it is", "병원을 떠난 뒤", wire.getString("placeLabel"))
    }

    @Test
    fun `the place is fixed when the car asks, not thirty seconds later`() {
        val rig = Rig()
        rig.consent()
        rig.engine.updateConsent { it.copy(checkInPlaceLabel = true) }
        rig.play(DemoScript.hospitalTrip(rig.now))
        rig.engine.cancelArrival()

        val t0 = rig.now + 60_000
        rig.play(listOf(GearEvent(t0, Gear.DRIVE)) + (1..40).map { SpeedEvent(t0 + it * 500L, 12f) })
        rig.engine.onSignal(AirbagEvent(rig.now, true))
        // 답을 기다리는 사이에 P로 바뀌고 주행이 끝난다.
        rig.play(listOf(SpeedEvent(rig.now + 1_000, 0f), GearEvent(rig.now + 2_000, Gear.PARK)))
        rig.advance(30_000)
        val wire = JSONObject(rig.outbox.pending().single().wire)
        assertEquals("CHECK_IN", wire.getString("type"))
        assertEquals("병원을 떠난 뒤", wire.getString("placeLabel"))
    }

    @Test
    fun `while still parked at the place the alert says so`() {
        val rig = Rig()
        rig.consent()
        rig.engine.updateConsent { it.copy(checkInPlaceLabel = true) }
        rig.play(DemoScript.hospitalTrip(rig.now))
        rig.engine.cancelArrival()
        rig.engine.onSignal(AirbagEvent(rig.now, true))
        rig.advance(30_000)
        assertEquals("병원 도착 뒤", JSONObject(rig.outbox.pending().single().wire).getString("placeLabel"))
    }

    @Test
    fun `a day off forgets the place the car left`() {
        val rig = Rig()
        rig.consent()
        rig.engine.updateConsent { it.copy(checkInPlaceLabel = true) }
        rig.play(DemoScript.hospitalTrip(rig.now))
        rig.engine.cancelArrival()
        rig.engine.pauseToday()
        rig.engine.resumeNow()
        rig.advance(1_000)
        rig.engine.onSignal(AirbagEvent(rig.now, true))
        rig.advance(30_000)
        assertFalse(JSONObject(rig.outbox.pending().single().wire).has("placeLabel"))
    }

    // ---------------------------------------------------------------- 전송함

    @Test
    fun `outbox retries with growing gaps and gives up after a day`() {
        val transport = FakeTransport().apply { failNext = 2 }
        val outbox = Outbox(InMemoryBlobStore(), transport)
        val t0 = at(14)
        outbox.enqueue("""{"id":"a1"}""", t0)

        assertEquals(0, outbox.flush(t0))
        assertEquals(t0 + 5_000, outbox.pending().single().nextAttemptMs)
        assertEquals(1, transport.failNext)
        assertEquals(0, outbox.flush(t0 + 4_000))
        assertEquals("too early, so the transport was not even tried", 1, transport.failNext)

        assertEquals(0, outbox.flush(t0 + 5_000))
        assertEquals(t0 + 5_000 + 10_000, outbox.pending().single().nextAttemptMs)
        assertEquals(1, outbox.flush(t0 + 15_000))
        assertTrue(outbox.pending().isEmpty())
        assertEquals(listOf("""{"id":"a1"}"""), transport.posted)

        assertEquals(5_000, Outbox.backoffMs(1))
        assertEquals(10 * 60_000L, Outbox.backoffMs(30))

        transport.failNext = 100
        outbox.enqueue("""{"id":"a2"}""", t0)
        outbox.flush(t0 + 25L * 3600 * 1000)
        assertTrue("a day old note is dropped, not delivered late", outbox.pending().isEmpty())
        assertEquals(1, transport.posted.size)
    }

    // ---------------------------------------------------------------- 음성 명령

    @Test
    fun `spoken commands are recognised with loose spacing and endings`() {
        assertEquals(VoiceCommand.CANCEL_SEND, VoiceCommands.parse("보내지 마"))
        assertEquals(VoiceCommand.CANCEL_SEND, VoiceCommands.parse("아니 보내지마세요"))
        assertEquals(VoiceCommand.CANCEL_SEND, VoiceCommands.parse("취소해 줘"))
        assertEquals(VoiceCommand.IM_OK, VoiceCommands.parse("괜찮아"))
        assertEquals(VoiceCommand.IM_OK, VoiceCommands.parse("응, 괜찮습니다."))
        assertEquals(VoiceCommand.NEED_HELP, VoiceCommands.parse("안 괜찮아"))
        assertEquals(VoiceCommand.NEED_HELP, VoiceCommands.parse("괜찮지 않아요"))
        assertEquals(VoiceCommand.NEED_HELP, VoiceCommands.parse("도와줘"))
        assertEquals(VoiceCommand.SEND_DEPARTURE, VoiceCommands.parse("지금 출발한다고 전해 줘"))
        assertEquals(VoiceCommand.SEND_ARRIVAL, VoiceCommands.parse("도착했다고 알려 줘"))
        assertEquals(VoiceCommand.READ_DIARY, VoiceCommands.parse("운전 일기 읽어 줘"))
        assertEquals(VoiceCommand.SEND_NOW, VoiceCommands.parse("지금 보내"))
        assertEquals(VoiceCommand.NONE, VoiceCommands.parse("오늘 날씨 어때"))
        assertEquals(VoiceCommand.NONE, VoiceCommands.parse(""))
    }

    // ---------------------------------------------------------------- 시뮬레이션 소스

    @Test
    fun `simulated source replays the script four times faster and then returns to normal time`() {
        var wall = at(14)
        val clock = SignalClock { wall }
        val sim = SimulatedSource(clock, speed = 4.0)
        val got = ArrayList<SignalEvent>()
        sim.start({ got += it }) { }

        sim.play { DemoScript.hospitalTrip(it) }
        val total = DemoScript.hospitalTrip(0).size
        assertTrue(sim.playing)

        wall += 10_000                       // 실제 10초 = 대본 40초
        sim.pump()
        assertTrue(got.isNotEmpty() && got.size < total)
        assertTrue(got.all { it.atMs <= at(14) + 40_000 })

        wall += 35_000                       // 대본 180초 지점. 대본은 166초에 끝난다.
        sim.pump()
        assertEquals(total, got.size)
        assertFalse(sim.playing)

        // 재생이 끝나면 시계는 다시 실제 속도로 가고, 거꾸로 가지 않는다.
        val before = clock.now()
        wall += 1_000
        assertEquals(1_000, clock.now() - before)
        assertTrue(clock.now() > got.last().atMs)
    }

    @Test
    fun `the simulated trip driven through the engine ends in an arrival note for the hospital`() {
        val rig = Rig()
        rig.consent()
        val clock = SignalClock { rig.now }
        val sim = SimulatedSource(clock, speed = 4.0)
        sim.start({ rig.engine.onSignal(it) }) { }
        rig.engine.setDestination(rig.engine.places.first { it.id == Places.HOSPITAL })
        sim.play { DemoScript.hospitalTrip(it) }

        var guard = 0
        while (rig.effectsOf<EngineEffect.NoteQueued>().isEmpty() && guard++ < 2_000) {
            rig.now += 250
            sim.pump()
            rig.engine.onTick(clock.now())
        }
        val note = rig.effectsOf<EngineEffect.NoteQueued>().single().note
        assertEquals("아버지께서 병원에 잘 도착하셨어요.", note.line)
        val summary = rig.effectsOf<EngineEffect.TripEnded>().single().summary
        assertEquals(1, summary.hardBrakes)
        assertTrue("about a minute of real time for the whole demo, took ${guard * 250} ms", guard * 250 in 40_000..70_000)
    }
}
