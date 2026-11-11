package app.anbu.car

import app.anbu.car.anbu.Outbox
import app.anbu.car.anbu.Transport
import app.anbu.car.core.AnbuEngine
import app.anbu.car.core.EngineConfig
import app.anbu.car.core.EngineEffect
import app.anbu.car.privacy.InMemoryBlobStore
import app.anbu.car.signals.SignalEvent
import java.time.ZoneId
import java.time.ZonedDateTime

val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

/** 2026-11-11 수요일 [hour]시 (서울). */
fun at(hour: Int, minute: Int = 0): Long =
    ZonedDateTime.of(2026, 11, 11, hour, minute, 0, 0, SEOUL).toInstant().toEpochMilli()

class FakeTransport : Transport {
    val posted = ArrayList<String>()
    val deletedCodes = ArrayList<String>()
    var failNext = 0

    override fun postNote(wireJson: String): Boolean {
        if (failNext > 0) {
            failNext--
            return false
        }
        posted += wireJson
        return true
    }

    override fun deleteFamily(familyCode: String): Boolean {
        deletedCodes += familyCode
        return true
    }

    /** 릴레이에 쌓여 있는 답장(JSON 글). */
    val replies = ArrayList<String>()

    override fun fetchReplies(familyCode: String, afterId: String?): List<String> = replies.toList()
}

/** 엔진과 그 주변(저장소, 전송, 시계)을 묶은 테스트용 세트. */
class Rig(startMs: Long = at(14), config: EngineConfig = EngineConfig()) {
    var now = startMs
    val store = InMemoryBlobStore()
    val transport = FakeTransport()
    val outbox = Outbox(store, transport)
    val effects = ArrayList<EngineEffect>()
    val engine = AnbuEngine(store, outbox, { now }, SEOUL, config) { "TEST23" }.also {
        it.listener = { e -> effects += e }
    }

    fun consent(
        shareArrival: Boolean = true,
        useProximity: Boolean = true,
        checkInAlert: Boolean = true,
    ) = engine.grantConsent("아버지", shareArrival, useProximity, checkInAlert)

    /** 대본을 끝까지 넣는다. 벽시계도 신호 시각을 따라가게 한다. */
    fun play(events: List<SignalEvent>) {
        for (e in events) {
            now = maxOf(now, e.atMs)
            engine.onSignal(e)
        }
        engine.onTick(now)
    }

    /** 벽시계와 신호 시계를 함께 [ms]만큼 보낸다. */
    fun advance(ms: Long) {
        now += ms
        engine.onTick(now)
    }

    inline fun <reified T : EngineEffect> effectsOf(): List<T> = effects.filterIsInstance<T>()
}
