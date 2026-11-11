package app.anbu.car

import app.anbu.car.anbu.FamilyReply
import app.anbu.car.anbu.ReplyPreset
import app.anbu.car.core.AnbuEngine
import app.anbu.car.core.EngineEffect
import app.anbu.car.navi.withObjectParticle
import app.anbu.car.signals.AirbagEvent
import app.anbu.car.signals.DemoScript
import app.anbu.car.signals.Gear
import app.anbu.car.signals.GearEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가족의 답장. 정해 둔 답장만 받고, 차가 서 있을 때(기어 P)만 읽어 주는지 확인한다.
 */
class ReplyTest {

    private fun wire(id: String, code: String, atMs: Long): String =
        JSONObject().put("id", id).put("code", code).put("atMs", atMs).toString()

    private fun parked(): Rig = Rig().also {
        it.consent()
        it.engine.onSignal(GearEvent(it.now, Gear.PARK))
    }

    @Test
    fun `a reply that arrives while the car is parked is read at once and kept`() {
        val rig = parked()
        rig.advance(6_000)
        rig.engine.onRepliesFetched(listOf(wire("r1", "SAFE_TRIP", rig.now)))

        val ready = rig.effectsOf<EngineEffect.RepliesReady>().single()
        assertEquals("잘 다녀오세요", ready.replies.single().preset.text)
        val kept = rig.engine.recentReplies.single()
        assertEquals(ReplyPreset.SAFE_TRIP, kept.preset)
        assertNotNull("marked as read", kept.readAtMs)
        assertEquals("r1", rig.engine.replyCursor)
    }

    @Test
    fun `a reply that arrives during a drive waits until the arrival note is done`() {
        val rig = Rig()
        rig.consent()
        val t0 = rig.now
        val script = DemoScript.hospitalTrip(t0)
        rig.play(script.filter { it.atMs <= t0 + 60_000 })           // 달리는 중
        rig.engine.onRepliesFetched(listOf(wire("r1", "CALL_ON_ARRIVAL", rig.now)))
        rig.advance(10_000)
        assertTrue("nothing is read while driving", rig.effectsOf<EngineEffect.RepliesReady>().isEmpty())
        assertNull(rig.engine.recentReplies.single().readAtMs)

        rig.play(script.filter { it.atMs > t0 + 60_000 })            // 도착해서 P, 20초 뒤 카운트다운
        assertNotNull(rig.engine.pendingArrival)
        rig.advance(9_000)
        assertTrue("not during the countdown", rig.effectsOf<EngineEffect.RepliesReady>().isEmpty())
        rig.advance(1_000)                                            // 도착 안부 전송
        assertEquals(1, rig.effectsOf<EngineEffect.NoteQueued>().size)
        rig.advance(4_000)
        assertTrue("the car has only just spoken", rig.effectsOf<EngineEffect.RepliesReady>().isEmpty())
        rig.advance(1_500)
        assertEquals("도착하면 전화 주세요", rig.effectsOf<EngineEffect.RepliesReady>().single().replies.single().preset.text)
    }

    @Test
    fun `gear D alone is enough to hold a reply back`() {
        val rig = Rig()
        rig.consent()
        rig.engine.onSignal(GearEvent(rig.now, Gear.DRIVE))
        rig.advance(10_000)
        rig.engine.onRepliesFetched(listOf(wire("r1", "THANKS", rig.now)))
        rig.advance(30_000)
        assertTrue(rig.effectsOf<EngineEffect.RepliesReady>().isEmpty())
    }

    @Test
    fun `only the preset replies get in, free text has no way in`() {
        val rig = parked()
        rig.advance(6_000)
        rig.engine.onRepliesFetched(
            listOf(
                wire("r1", "아무 글이나 써서 보냄", rig.now),
                JSONObject().put("id", "r2").put("code", "SAFE_TRIP").put("atMs", rig.now).put("text", "긴 글").toString(),
                JSONObject().put("id", "r3").put("text", "글만 있음").put("atMs", rig.now).toString(),
                "not json",
            )
        )
        assertTrue(rig.engine.recentReplies.isEmpty())
        assertTrue(rig.effectsOf<EngineEffect.RepliesReady>().isEmpty())
        assertNull(FamilyReply.fromWire(wire("r4", "safe_trip", rig.now)))
        assertEquals("only an id, a preset name and a time come in", setOf("id", "code", "atMs"), FamilyReply.WIRE_KEYS)
    }

    @Test
    fun `the same reply is not read twice and several are read together`() {
        val rig = parked()
        rig.advance(6_000)
        rig.engine.onRepliesFetched(listOf(wire("r1", "SAFE_TRIP", rig.now)))
        rig.engine.onRepliesFetched(listOf(wire("r1", "SAFE_TRIP", rig.now)))
        assertEquals(1, rig.effectsOf<EngineEffect.RepliesReady>().size)

        rig.advance(1_000)
        rig.engine.onRepliesFetched(listOf(wire("r2", "DRIVE_SAFE", rig.now), wire("r3", "THANKS", rig.now)))
        assertEquals("still inside the quiet gap after the first one", 1, rig.effectsOf<EngineEffect.RepliesReady>().size)
        rig.advance(5_000)
        val second = rig.effectsOf<EngineEffect.RepliesReady>().last()
        assertEquals(listOf("조심히 오세요", "고마워요"), second.replies.map { it.preset.text })
        assertEquals("r3", rig.engine.replyCursor)
    }

    @Test
    fun `no replies without consent, on a day off, or with the option switched off`() {
        val none = Rig()
        none.engine.onSignal(GearEvent(none.now, Gear.PARK))
        assertFalse(none.engine.canReceiveReplies())
        none.engine.onRepliesFetched(listOf(wire("r1", "SAFE_TRIP", none.now)))
        assertTrue(none.engine.recentReplies.isEmpty())
        assertTrue("nothing written to the car's store", none.store.names.isEmpty())

        val paused = parked()
        paused.engine.pauseToday()
        assertFalse(paused.engine.canReceiveReplies())
        paused.engine.onRepliesFetched(listOf(wire("r1", "SAFE_TRIP", paused.now)))
        assertTrue(paused.engine.recentReplies.isEmpty())

        val off = parked()
        off.advance(6_000)
        off.engine.onRepliesFetched(listOf(wire("r1", "SAFE_TRIP", off.now)))
        assertEquals(1, off.engine.recentReplies.size)
        off.engine.updateConsent { it.copy(receiveReplies = false) }
        assertFalse(off.engine.canReceiveReplies())
        assertTrue("switching it off also drops what was kept", off.engine.recentReplies.isEmpty())
    }

    @Test
    fun `a reply left unread when the day off starts is not read during it`() {
        val rig = Rig()
        rig.consent()
        rig.engine.onSignal(GearEvent(rig.now, Gear.DRIVE))
        rig.engine.onRepliesFetched(listOf(wire("r1", "SAFE_TRIP", rig.now)))
        rig.engine.pauseToday()
        rig.engine.onSignal(GearEvent(rig.now, Gear.PARK))
        rig.advance(60_000)
        assertTrue(rig.effectsOf<EngineEffect.RepliesReady>().isEmpty())
    }

    @Test
    fun `a reply several hours old is not read`() {
        val rig = parked()
        rig.advance(6_000)
        rig.engine.onRepliesFetched(listOf(wire("r1", "SAFE_TRIP", rig.now - 4 * 3600_000L)))
        assertTrue(rig.engine.recentReplies.isEmpty())
        assertEquals("but it is not asked for again", "r1", rig.engine.replyCursor)
    }

    @Test
    fun `while the car is asking are you all right the reply waits`() {
        val rig = parked()
        rig.advance(6_000)
        rig.engine.onSignal(AirbagEvent(rig.now, true))
        rig.engine.onRepliesFetched(listOf(wire("r1", "SAFE_TRIP", rig.now)))
        rig.advance(5_000)
        assertTrue(rig.effectsOf<EngineEffect.RepliesReady>().isEmpty())
        rig.engine.answerCheckInOk()
        rig.advance(5_500)
        assertEquals(1, rig.effectsOf<EngineEffect.RepliesReady>().size)
    }

    @Test
    fun `replies survive a restart and are wiped by delete everything`() {
        val rig = parked()
        rig.advance(6_000)
        rig.engine.onRepliesFetched(listOf(wire("r1", "THANKS", rig.now)))

        val again = AnbuEngine(rig.store, rig.outbox, { rig.now }, SEOUL)
        assertEquals("고마워요", again.recentReplies.single().preset.text)
        assertEquals("r1", again.replyCursor)

        rig.engine.deleteEverything()
        assertTrue(rig.engine.recentReplies.isEmpty())
        assertNull(rig.engine.replyCursor)
        assertTrue(rig.store.names.isEmpty())
    }

    @Test
    fun `object particle follows the final consonant`() {
        assertEquals("병원을", withObjectParticle("병원"))
        assertEquals("집을", withObjectParticle("집"))
        assertEquals("딸네를", withObjectParticle("딸네"))
        assertEquals("마트를", withObjectParticle("마트"))
    }
}
