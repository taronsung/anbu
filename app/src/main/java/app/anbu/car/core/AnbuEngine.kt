package app.anbu.car.core

import app.anbu.car.anbu.AnbuNote
import app.anbu.car.anbu.FamilyReply
import app.anbu.car.anbu.NoteComposer
import app.anbu.car.anbu.Outbox
import app.anbu.car.baseline.BaselineModel
import app.anbu.car.baseline.DiaryAssessment
import app.anbu.car.baseline.WeekStats
import app.anbu.car.baseline.Weeks
import app.anbu.car.navi.Place
import app.anbu.car.navi.Places
import app.anbu.car.navi.withObjectParticle
import app.anbu.car.privacy.BlobStore
import app.anbu.car.privacy.ConsentState
import app.anbu.car.privacy.Outbound
import app.anbu.car.privacy.PrivacyGate
import app.anbu.car.signals.AirbagEvent
import app.anbu.car.signals.BrakeEvent
import app.anbu.car.signals.DriverBeltEvent
import app.anbu.car.signals.Gear
import app.anbu.car.signals.GearEvent
import app.anbu.car.signals.LowBeamEvent
import app.anbu.car.signals.NaviArrivedEvent
import app.anbu.car.signals.NaviCautionEvent
import app.anbu.car.signals.NaviEtaEvent
import app.anbu.car.signals.NaviRerouteEvent
import app.anbu.car.signals.OdometerEvent
import app.anbu.car.signals.PlaceProximityEvent
import app.anbu.car.signals.SignalEvent
import app.anbu.car.signals.SpeedEvent
import app.anbu.car.signals.WiperEvent
import app.anbu.car.trip.TripConfig
import app.anbu.car.trip.TripOutput
import app.anbu.car.trip.TripRecorder
import app.anbu.car.trip.TripSummary
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId

data class EngineConfig(
    val trip: TripConfig = TripConfig(),
    /** 도착 안부를 보내기 전 취소할 수 있는 시간(SPEC F1: 10초). */
    val cancelWindowMs: Long = 10_000,
    /** "괜찮으세요?"에 답을 기다리는 시간(SPEC F4: 30초). */
    val checkInAnswerMs: Long = 30_000,
    /** 급감속 뒤 P가 아닌 채로 이만큼 서 있으면 괜찮은지 묻는다(SPEC F4: 60초). */
    val hardStopHoldMs: Long = 60_000,
    /** 급감속 뒤 이 시간 안에 멈추지 않으면 그냥 지나간 것으로 본다. */
    val hardStopSettleMs: Long = 10_000,
    /** 주행 요약 보관 기간. 기준선 8주와 최근 1주. */
    val retentionWeeks: Int = 9,
    /** 차가 다른 안내를 한 뒤 이만큼은 가족의 답장을 읽지 않고 기다린다. 말이 겹치지 않게 하기 위해서다. */
    val replyQuietMs: Long = 5_000,
    /** 이보다 오래된 답장은 받지 않는다. 몇 시간 지난 "잘 다녀오세요"는 읽지 않는다. */
    val replyMaxAgeMs: Long = 3L * 3600 * 1000,
)

enum class CheckInReason { AIRBAG, HARD_STOP }

enum class BlockReason { NO_CONSENT, PAUSED, SHARING_OFF }

sealed interface EngineEffect {
    data class TripStarted(val atMs: Long) : EngineEffect
    /**
     * @param notifying 도착 안부 카운트다운을 시작했는가
     * @param unnamedPlace 이름을 붙인 곳이 아니어서 알리지 않았는가
     */
    data class TripEnded(
        val summary: TripSummary,
        val notifying: Boolean,
        val unnamedPlace: Boolean = false,
    ) : EngineEffect
    data class ArrivalCountdown(val note: AnbuNote, val deadlineWallMs: Long) : EngineEffect
    data class ArrivalCancelled(val note: AnbuNote) : EngineEffect
    data class NoteQueued(val note: AnbuNote) : EngineEffect
    data class CheckInAsked(val reason: CheckInReason, val deadlineWallMs: Long) : EngineEffect
    data object CheckInAnswered : EngineEffect
    data class CheckInEscalated(val sentToFamily: Boolean) : EngineEffect
    data class Blocked(val what: Outbound, val reason: BlockReason) : EngineEffect
    /** "이번 운전만 쉬기"로 건너뛴 주행이 끝나 다시 기록을 시작했다. */
    data object TripSkipEnded : EngineEffect
    /** 가족의 답장을 읽어 줄 때가 되었다. 차가 서 있을 때(기어 P)만 나온다. */
    data class RepliesReady(val replies: List<FamilyReply>) : EngineEffect
}

/** 화면에 보여 줄 현재 차량 상태. 저장하지 않는다. */
data class VehicleView(
    val gear: Gear = Gear.UNKNOWN,
    val speedMps: Double? = null,
    val brakePressed: Boolean? = null,
    val beltBuckled: Boolean? = null,
    val wiperOn: Boolean? = null,
    val lowBeamOn: Boolean? = null,
    val odometerKm: Double? = null,
    val signalCount: Long = 0,
)

enum class WeeklyDecision { SHARED, PRIVATE }

data class DiaryView(
    val week: WeekStats,
    val assessment: DiaryAssessment,
    val line: String,
    val decision: WeeklyDecision?,
    val hasSynthetic: Boolean,
)

/**
 * 앱의 두뇌. 신호를 받아 주행을 기록하고, 도착 안부·주간 일기·괜찮으세요 확인을 결정한다.
 *
 * 안드로이드와 Pleos SDK에 의존하지 않는다. 저장은 [BlobStore], 전송은 [Outbox]를 통하고,
 * 저장하거나 내보내기 전에는 항상 [PrivacyGate]를 거친다. 한 스레드에서만 부른다.
 */
class AnbuEngine(
    private val store: BlobStore,
    private val outbox: Outbox,
    private val wallClock: () -> Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val config: EngineConfig = EngineConfig(),
    private val codeFactory: () -> String = ::randomFamilyCode,
) {
    var listener: (EngineEffect) -> Unit = {}

    /** 개발 중 확인용 기록. 주행 요약의 수치만 남기고 좌표나 원신호는 남기지 않는다. */
    var debugLog: (String) -> Unit = {}

    var consent: ConsentState = store.read(BLOB_CONSENT)?.let { runCatching { ConsentState.fromJson(org.json.JSONObject(it)) }.getOrNull() }
        ?: ConsentState()
        private set

    val gate = PrivacyGate { consent }
    val composer = NoteComposer(zone)
    private val baseline = BaselineModel()
    private var recorder = TripRecorder(config.trip, zone)

    var vehicle = VehicleView(); private set
    var places: List<Place> = store.read(BLOB_PLACES)?.let { runCatching { Places.fromJson(it) }.getOrNull() }
        ?: Places.defaults
        private set

    private var trips: MutableList<TripSummary> = loadTrips()
    private var notes: MutableList<AnbuNote> = loadNotes()
    private var replies: MutableList<FamilyReply> = loadReplies()
    /** 차가 마지막으로 안내(카운트다운, 전송 알림, 괜찮으세요)를 시작한 시각. */
    private var lastPromptAtMs = 0L

    /** 본인이 길안내를 요청한 저장 장소. */
    var activeDestination: Place? = null; private set
    private var lastEta: Pair<Long, Int>? = null // (받은 벽시계 시각, 남은 초)
    /** 내비게이션이 알려 준 도착 예정 시각. 그 시각이 5분 넘게 지났으면 낡은 값으로 보고 쓰지 않는다. */
    val etaAtMs: Long?
        get() = lastEta?.let { it.first + it.second * 1000L }?.takeIf { wallClock() <= it + 5 * 60_000 }

    data class PendingArrival(val note: AnbuNote, val deadlineWallMs: Long)
    var pendingArrival: PendingArrival? = null; private set

    /** @param where 묻는 순간에 정해 둔 "병원을 떠난 뒤" 같은 글. 알림에 붙일지는 보낼 때 설정을 보고 정한다. */
    data class CheckIn(
        val reason: CheckInReason,
        val askedWallMs: Long,
        val deadlineWallMs: Long,
        val where: String? = null,
    )
    var checkIn: CheckIn? = null; private set

    private var hardBrakeAtMs: Long? = null
    private var stoppedSinceMs: Long? = null
    private var lastArrival: Pair<Long, String?>? = null // (벽시계 시각, 장소 이름표)
    /** 주간 일기를 나눌지 말지 정한 시각과 결정. 그날 안에는 다시 묻지 않도록 저장해 둔다. */
    private var weeklyDecision: Pair<Long, WeeklyDecision>? = store.read(BLOB_WEEKLY)?.let { text ->
        runCatching {
            val (ms, name) = text.split(":")
            ms.toLong() to WeeklyDecision.valueOf(name)
        }.getOrNull()
    }
    private var recordingWasAllowed = false
    /** "이번 운전만 쉬기"를 누른 뒤 차가 움직였는가, 그리고 다시 P에 둔 시각. */
    private var skipSawDriving = false
    private var skipParkedSinceMs: Long? = null

    val inTrip: Boolean get() = recorder.inTrip
    val tripCount: Int get() = trips.size
    val recentNotes: List<AnbuNote> get() = notes.toList()
    val recentReplies: List<FamilyReply> get() = replies.toList()
    /** 릴레이에 "이 뒤에 온 것만" 달라고 할 때 쓰는, 마지막으로 받은 답장의 id. */
    var replyCursor: String? = store.read(BLOB_REPLY_CURSOR)
        private set

    // ---------------------------------------------------------------- 신호

    fun onSignal(e: SignalEvent) {
        updateView(e)
        val now = wallClock()
        trackSkippedTrip(e.atMs)

        if (e is AirbagEvent && e.deployed) askCheckIn(CheckInReason.AIRBAG, now)
        if (e is NaviEtaEvent) lastEta = now to e.remainSeconds

        if (!syncRecordingAllowed(now)) return
        // 장소 근접 신호는 본인이 켠 경우에만 쓴다.
        if (e is PlaceProximityEvent && !gate.canUseProximity(now)) return

        handle(recorder.onEvent(e), now)
        trackHardStop(e.atMs, now)
    }

    /**
     * 주기적으로 부른다(1초에 여러 번).
     * @param signalNowMs 신호 시계의 현재 시각. 주행 구간 판단에 쓴다.
     */
    fun onTick(signalNowMs: Long) {
        val now = wallClock()
        trackSkippedTrip(signalNowMs)
        if (syncRecordingAllowed(now)) {
            handle(recorder.onTick(signalNowMs), now)
            trackHardStop(signalNowMs, now)
        }
        pendingArrival?.let { if (now >= it.deadlineWallMs) commitArrival(now) }
        checkIn?.let { if (now >= it.deadlineWallMs) escalateCheckIn(now) }
        deliverReplies(now)
    }

    /**
     * "이번 운전만 쉬기"는 그 주행이 끝나면 저절로 풀린다.
     * 쉬는 동안에는 주행 기록기에 신호를 넘기지 않으므로 기어만 보고 끝을 판단한다:
     * 한 번 P를 벗어났다가 다시 P에 두고 [TripConfig.parkHoldMs]가 지나면 끝난 것으로 본다.
     */
    private fun trackSkippedTrip(signalNowMs: Long) {
        if (!consent.skipTrip) return
        when (vehicle.gear) {
            Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL -> {
                skipSawDriving = true
                skipParkedSinceMs = null
            }
            Gear.PARK -> if (skipSawDriving) {
                val since = skipParkedSinceMs ?: signalNowMs.also { skipParkedSinceMs = it }
                if (signalNowMs - since >= config.trip.parkHoldMs) {
                    consent = consent.copy(skipTrip = false)
                    saveConsent()
                    skipSawDriving = false
                    skipParkedSinceMs = null
                    listener(EngineEffect.TripSkipEnded)
                }
            }
            Gear.UNKNOWN -> Unit
        }
    }

    /** 동의가 없거나 쉬는 중으로 바뀌면 진행 중이던 주행 기록을 버린다. */
    private fun syncRecordingAllowed(now: Long): Boolean {
        val allowed = gate.canRecord(now)
        if (!allowed && recordingWasAllowed) {
            recorder.abort()
            hardBrakeAtMs = null
            stoppedSinceMs = null
            // 기록하지 않는 동안 차가 어디로 갔는지 모르므로 "떠나온 곳"도 잊는다.
            lastArrival = null
        }
        if (allowed && !recordingWasAllowed) {
            // 동의 전이나 쉬는 동안에는 신호를 기록기에 넘기지 않았다. 기록을 시작하는 순간의
            // 차 상태(안전띠, 와이퍼, 전조등 등)는 알아야 하므로 지금 값으로 맞춰 둔다.
            recorder.prime(
                beltBuckled = vehicle.beltBuckled, brakePressed = vehicle.brakePressed,
                wiperOn = vehicle.wiperOn, lowBeamOn = vehicle.lowBeamOn, odometerKm = vehicle.odometerKm,
            )
        }
        recordingWasAllowed = allowed
        return allowed
    }

    private fun updateView(e: SignalEvent) {
        val v = vehicle.copy(signalCount = vehicle.signalCount + 1)
        vehicle = when (e) {
            is GearEvent -> v.copy(gear = e.gear)
            is SpeedEvent -> v.copy(speedMps = e.mps.toDouble())
            is BrakeEvent -> v.copy(brakePressed = e.pressed)
            is DriverBeltEvent -> v.copy(beltBuckled = e.buckled)
            is WiperEvent -> v.copy(wiperOn = e.on)
            is LowBeamEvent -> v.copy(lowBeamOn = e.on)
            is OdometerEvent -> v.copy(odometerKm = e.km.toDouble())
            is AirbagEvent, is NaviArrivedEvent, is NaviEtaEvent, is NaviCautionEvent,
            is NaviRerouteEvent, is PlaceProximityEvent -> v
        }
    }

    private fun handle(outputs: List<TripOutput>, now: Long) {
        for (o in outputs) when (o) {
            is TripOutput.Started -> listener(EngineEffect.TripStarted(o.atMs))
            is TripOutput.HardBrake -> {
                hardBrakeAtMs = o.atMs
                stoppedSinceMs = null
                debugLog("hard brake: %.1f -> %.1f m/s".format(o.fromMps, o.toMps))
            }
            is TripOutput.Ended -> onTripEnded(o.summary, now)
            is TripOutput.Discarded -> Unit
        }
    }

    private fun onTripEnded(summary: TripSummary, now: Long) {
        debugLog(
            "trip ended: %.2f km, moving %d s, night %d s, rain %d s, hardBrakes %d, lateBelt %s, reroutes %d, cautions %d, place %s"
                .format(
                    summary.distanceKm, summary.movingMs / 1000, summary.nightMs / 1000, summary.rainMs / 1000,
                    summary.hardBrakes, summary.lateBelt, summary.reroutes,
                    summary.schoolZoneAlerts + summary.wrongWayAlerts, summary.arrivedPlaceId ?: "-",
                )
        )
        val earlier = trips.filter { it.arrivedPlaceId != null && it.arrivedPlaceId == summary.arrivedPlaceId }
        trips += summary
        pruneAndSaveTrips(now)

        val label = summary.arrivedPlaceId?.let { id -> places.firstOrNull { it.id == id }?.label }
        lastArrival = now to label
        activeDestination = null
        lastEta = null
        hardBrakeAtMs = null
        stoppedSinceMs = null

        // 이름을 붙인 곳(저장한 장소 진입, 내비게이션 목적지 도착)에 섰을 때만 알린다.
        // 주유소나 편의점처럼 이름 없는 정차는 본인이 따로 켠 경우에만 "외출지"로 알린다.
        val allowed = gate.canSend(Outbound.ARRIVAL, now)
        val named = label != null
        val canSend = allowed && (named || consent.shareUnnamedArrival)
        listener(EngineEffect.TripEnded(summary, canSend, unnamedPlace = allowed && !canSend))
        if (canSend) {
            val durationNote = if (consent.shareTripDuration) composer.durationNote(summary, earlier) else null
            startArrivalCountdown(composer.arrival("a-$now", consent.who, now, label, durationNote), now)
        }
    }

    // ---------------------------------------------------------------- F1 도착 안부

    private fun startArrivalCountdown(note: AnbuNote, now: Long) {
        val p = PendingArrival(note, now + config.cancelWindowMs)
        pendingArrival = p
        lastPromptAtMs = now
        listener(EngineEffect.ArrivalCountdown(note, p.deadlineWallMs))
    }

    /** "보내지 마". 취소하면 그 안부는 어디에도 남지 않는다. */
    fun cancelArrival() {
        val p = pendingArrival ?: return
        pendingArrival = null
        lastPromptAtMs = wallClock()
        listener(EngineEffect.ArrivalCancelled(p.note))
    }

    /** 카운트다운을 기다리지 않고 바로 보낸다. */
    fun sendArrivalNow() {
        if (pendingArrival != null) commitArrival(wallClock())
    }

    private fun commitArrival(now: Long) {
        val p = pendingArrival ?: return
        pendingArrival = null
        // 10초 사이에 쉬기를 누르거나 공유를 껐을 수 있으므로 다시 확인한다.
        if (gate.canSend(Outbound.ARRIVAL, now)) send(p.note, now)
    }

    /**
     * 음성("도착했다고 전해 줘")이나 인텐트로 본인이 직접 요청한 도착 안부. 취소 시간은 똑같이 둔다.
     * 본인이 요청한 것이므로 이름 없는 곳이어도 "외출지"로 보낸다.
     */
    fun requestArrivalNote(): Boolean {
        val now = wallClock()
        if (!checkSend(Outbound.ARRIVAL, now)) return false
        if (pendingArrival != null) return true
        val label = lastArrival?.takeIf { now - it.first <= 30 * 60_000 }?.second
        startArrivalCountdown(composer.arrival("a-$now", consent.who, now, label), now)
        return true
    }

    // ---------------------------------------------------------------- F2 출발 한마디

    fun sendDeparture(): AnbuNote? {
        val now = wallClock()
        if (!checkSend(Outbound.DEPARTURE, now)) return null
        val note = composer.departure("d-$now", consent.who, now, activeDestination?.label, etaAtMs)
        send(note, now)
        return note
    }

    // ---------------------------------------------------------------- F3 주간 운전 일기

    fun diary(): DiaryView {
        val now = wallClock()
        val windows = Weeks.rolling(trips, now, baseline.windowWeeks + 1)
        val current = windows.first()
        val assessment = baseline.assess(current, windows.drop(1))
        val decision = weeklyDecision?.takeIf { sameDay(it.first, now) }?.second
        return DiaryView(
            week = current,
            assessment = assessment,
            line = composer.weeklyLine(assessment),
            decision = decision,
            hasSynthetic = trips.any { it.synthetic },
        )
    }

    /** "가족과 나누기". 단계와 한 줄만 보내고, 세부 수치는 본인이 켠 경우에만 붙인다. */
    fun shareWeekly(): AnbuNote? {
        val now = wallClock()
        if (!checkSend(Outbound.WEEKLY, now)) return null
        val d = diary()
        val dayId = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        val note = composer.weekly("w-$dayId", consent.who, now, d.week, d.assessment, consent.shareWeeklyDetails)
        rememberWeekly(now, WeeklyDecision.SHARED)
        send(note, now)
        return note
    }

    /** "나만 보기". 아무것도 보내지 않는다. */
    fun keepWeeklyPrivate() {
        if (!consent.granted) return
        rememberWeekly(wallClock(), WeeklyDecision.PRIVATE)
    }

    private fun rememberWeekly(now: Long, decision: WeeklyDecision) {
        weeklyDecision = now to decision
        store.write(BLOB_WEEKLY, "$now:${decision.name}")
    }

    // ---------------------------------------------------------------- F4 괜찮으세요

    private fun trackHardStop(signalNowMs: Long, now: Long) {
        val brakeAt = hardBrakeAtMs ?: return
        val speed = vehicle.speedMps ?: return
        fun clear() {
            hardBrakeAtMs = null
            stoppedSinceMs = null
        }
        when {
            // 주차했다면 스스로 차를 세운 것이다.
            vehicle.gear == Gear.PARK -> clear()
            speed <= 0.3 -> {
                val since = stoppedSinceMs ?: signalNowMs.also { stoppedSinceMs = it }
                if (signalNowMs - since >= config.hardStopHoldMs) {
                    clear()
                    askCheckIn(CheckInReason.HARD_STOP, now)
                }
            }
            // 섰다가 다시 움직였다.
            stoppedSinceMs != null -> if (speed > 2.0) clear() else stoppedSinceMs = null
            // 급감속은 했지만 서지 않고 지나갔다.
            signalNowMs - brakeAt > config.hardStopSettleMs -> clear()
        }
    }

    private fun askCheckIn(reason: CheckInReason, now: Long) {
        if (checkIn != null) return
        if (!consent.granted || !consent.checkInAlert || gate.isPaused(now)) return
        // 붙이는 이름은 지금 있는 곳이 아니라 떠나온 곳이다. 가족이 사고 지점으로 읽지 않게 그렇게 적는다.
        // 답을 기다리는 30초 사이에 차 상태가 바뀔 수 있으므로 묻는 순간의 것으로 정해 둔다.
        val where = lastArrival?.second?.let {
            if (recorder.inTrip) "${withObjectParticle(it)} 떠난 뒤" else "$it 도착 뒤"
        }
        val c = CheckIn(reason, now, now + config.checkInAnswerMs, where)
        checkIn = c
        lastPromptAtMs = now
        listener(EngineEffect.CheckInAsked(reason, c.deadlineWallMs))
    }

    /** "괜찮아"라고 답했거나 버튼을 눌렀다. */
    fun answerCheckInOk() {
        if (checkIn == null) return
        checkIn = null
        lastPromptAtMs = wallClock()
        listener(EngineEffect.CheckInAnswered)
    }

    /** "도와줘"처럼 괜찮지 않다고 답했다. 기다리지 않고 가족에게 알린다. */
    fun answerCheckInNeedHelp() {
        if (checkIn != null) escalateCheckIn(wallClock())
    }

    private fun escalateCheckIn(now: Long) {
        val c = checkIn ?: return
        checkIn = null
        lastPromptAtMs = now
        val canSend = gate.canSend(Outbound.CHECK_IN, now)
        if (canSend) {
            val where = if (consent.checkInPlaceLabel) c.where else null
            send(composer.checkIn("c-$now", consent.who, now, where), now)
        }
        listener(EngineEffect.CheckInEscalated(canSend))
    }

    // ---------------------------------------------------------------- 가족의 답장

    /** 지금 릴레이에 답장을 물어봐도 되는가. */
    fun canReceiveReplies(): Boolean = gate.canReceiveReplies(wallClock())

    /**
     * 릴레이에서 가져온 답장. 정해 둔 답장만 받고, 이미 받은 것과 오래된 것은 버린다.
     * 읽어 주는 것은 [deliverReplies]가 차가 서 있을 때 한다.
     */
    fun onRepliesFetched(wire: List<String>) {
        val now = wallClock()
        if (!gate.canReceiveReplies(now)) return
        var added = false
        var last: String? = null
        for (text in wire) {
            val r = FamilyReply.fromWire(text) ?: continue
            last = r.id
            if (replies.any { it.id == r.id }) continue
            if (now - r.atMs > config.replyMaxAgeMs) continue
            replies += r
            added = true
        }
        if (last != null && last != replyCursor) {
            replyCursor = last
            store.write(BLOB_REPLY_CURSOR, last)
        }
        if (!added) return
        while (replies.size > MAX_REPLIES) replies.removeAt(0)
        saveReplies()
        deliverReplies(now)
    }

    /**
     * 아직 읽지 않은 답장을 읽어 줄 때인지 본다.
     * 주행 중에는 읽지 않는다. 기어가 P이고 주행이 끝났으며, 도착 안부나 괜찮으세요 안내가 끝난 뒤에 읽는다.
     */
    private fun deliverReplies(now: Long) {
        if (replies.none { it.readAtMs == null }) return
        if (!gate.canReceiveReplies(now)) return
        if (vehicle.gear != Gear.PARK || recorder.inTrip) return
        if (pendingArrival != null || checkIn != null) return
        if (now - lastPromptAtMs < config.replyQuietMs) return
        val unread = replies.filter { it.readAtMs == null }
        replies = replies.map { if (it.readAtMs == null) it.copy(readAtMs = now) else it }.toMutableList()
        saveReplies()
        lastPromptAtMs = now
        listener(EngineEffect.RepliesReady(unread))
    }

    private fun saveReplies() =
        store.write(BLOB_REPLIES, JSONArray().apply { replies.forEach { put(it.toJson()) } }.toString())

    private fun loadReplies(): MutableList<FamilyReply> = runCatching {
        val arr = JSONArray(store.read(BLOB_REPLIES) ?: "[]")
        (0 until arr.length()).mapNotNull { FamilyReply.fromJson(arr.getJSONObject(it)) }.toMutableList()
    }.getOrElse { mutableListOf() }

    // ---------------------------------------------------------------- F5 자주 가는 곳

    fun setDestination(place: Place?) {
        activeDestination = place
        lastEta = null
    }

    /** "지금 위치로 저장". 좌표는 차 안 저장소에만 남는다. */
    fun updatePlaceCoordinate(placeId: String, latitude: Double, longitude: Double) {
        places = places.map {
            if (it.id == placeId) it.copy(
                latitude = latitude, longitude = longitude,
                poiName = it.label, address = "", demoCoordinate = false,
            ) else it
        }
        if (consent.granted) store.write(BLOB_PLACES, Places.toJson(places))
    }

    // ---------------------------------------------------------------- 동의·쉬기·삭제

    fun grantConsent(
        who: String,
        shareArrival: Boolean,
        useProximity: Boolean,
        checkInAlert: Boolean,
    ) {
        val now = wallClock()
        consent = ConsentState(
            granted = true,
            grantedAtMs = now,
            who = who,
            familyCode = consent.familyCode.ifEmpty { codeFactory() },
            shareArrival = shareArrival,
            useProximity = useProximity,
            checkInAlert = checkInAlert,
        )
        saveConsent()
    }

    fun updateConsent(transform: (ConsentState) -> ConsentState) {
        if (!consent.granted) return
        // 동의 여부, 초대 코드, 쉬기 상태는 전용 함수로만 바꾼다.
        val next = transform(consent)
        consent = next.copy(
            granted = true,
            grantedAtMs = consent.grantedAtMs,
            familyCode = consent.familyCode,
            pausedUntilMs = consent.pausedUntilMs,
            skipTrip = consent.skipTrip,
        )
        saveConsent()
        if (!consent.shareArrival) pendingArrival = null
        if (!consent.checkInAlert) checkIn = null
        // 답장 받기를 끄면 받아 둔 답장도 지운다.
        if (!consent.receiveReplies && replies.isNotEmpty()) {
            replies = mutableListOf()
            store.delete(BLOB_REPLIES)
        }
    }

    /** "오늘은 쉬기". 오늘 자정까지 기록과 전송을 멈춘다. */
    fun pauseToday() {
        if (!consent.granted) return
        val now = wallClock()
        val midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        consent = consent.copy(pausedUntilMs = midnight)
        saveConsent()
        pendingArrival = null
        checkIn = null
        syncRecordingAllowed(now)
    }

    /**
     * "이번 운전만 쉬기". 다른 사람이 운전할 때 누른다. 지금부터 그 주행이 끝나 주차할 때까지
     * 기록도 전송도 하지 않고, 그 주행은 운전 일기의 기준에도 들어가지 않는다.
     */
    fun pauseThisTrip() {
        if (!consent.granted) return
        val now = wallClock()
        // 이미 달리는 중에 눌렀다면 지금 주행이 건너뛸 주행이다.
        skipSawDriving = recorder.inTrip || vehicle.gear == Gear.DRIVE || vehicle.gear == Gear.REVERSE || vehicle.gear == Gear.NEUTRAL
        skipParkedSinceMs = null
        consent = consent.copy(skipTrip = true)
        saveConsent()
        pendingArrival = null
        checkIn = null
        syncRecordingAllowed(now)
    }

    fun resumeNow() {
        if (!consent.granted) return
        consent = consent.copy(pausedUntilMs = 0, skipTrip = false)
        skipSawDriving = false
        skipParkedSinceMs = null
        saveConsent()
    }

    /**
     * 전체 삭제. 동의를 거두고 차 안 기록과 보내려던 안부를 모두 지운다.
     * @return 릴레이에서도 지워야 할 가족 코드. 없으면 null.
     */
    fun deleteEverything(): String? {
        val code = consent.familyCode.ifEmpty { null }
        outbox.clear()
        store.wipe()
        consent = ConsentState()
        trips = mutableListOf()
        notes = mutableListOf()
        replies = mutableListOf()
        replyCursor = null
        lastPromptAtMs = 0
        places = Places.defaults
        pendingArrival = null
        checkIn = null
        activeDestination = null
        lastEta = null
        lastArrival = null
        weeklyDecision = null
        hardBrakeAtMs = null
        stoppedSinceMs = null
        recorder = TripRecorder(config.trip, zone)
        recordingWasAllowed = false
        skipSawDriving = false
        skipParkedSinceMs = null
        return code
    }

    /**
     * 시연용 예시 기록. 지난 8주와 최근 7일의 주행 요약을 만들어 넣는다.
     * 모두 synthetic으로 표시하고 화면에도 "예시 기록 포함"이라고 보여 준다.
     */
    fun seedExampleHistory() {
        val now = wallClock()
        if (!gate.canRecord(now)) return
        trips.removeAll { it.synthetic }
        trips += ExampleHistory.build(now)
        pruneAndSaveTrips(now)
    }

    // ---------------------------------------------------------------- 내부

    private fun checkSend(kind: Outbound, now: Long): Boolean {
        if (gate.canSend(kind, now)) return true
        val reason = when {
            !consent.granted -> BlockReason.NO_CONSENT
            gate.isPaused(now) -> BlockReason.PAUSED
            else -> BlockReason.SHARING_OFF
        }
        listener(EngineEffect.Blocked(kind, reason))
        return false
    }

    private fun send(note: AnbuNote, now: Long) {
        outbox.enqueue(note.toWire(consent.familyCode, zone).toString(), now)
        notes += note
        while (notes.size > MAX_NOTES) notes.removeAt(0)
        store.write(BLOB_NOTES, JSONArray().apply { notes.forEach { put(it.toJson()) } }.toString())
        lastPromptAtMs = now
        listener(EngineEffect.NoteQueued(note))
    }

    private fun saveConsent() = store.write(BLOB_CONSENT, consent.toJson().toString())

    private fun pruneAndSaveTrips(now: Long) {
        val cutoff = now - config.retentionWeeks * Weeks.WEEK_MS
        trips.removeAll { it.endMs < cutoff }
        store.write(BLOB_TRIPS, JSONArray().apply { trips.forEach { put(it.toJson()) } }.toString())
    }

    private fun loadTrips(): MutableList<TripSummary> = runCatching {
        val arr = JSONArray(store.read(BLOB_TRIPS) ?: "[]")
        (0 until arr.length()).map { TripSummary.fromJson(arr.getJSONObject(it)) }.toMutableList()
    }.getOrElse { mutableListOf() }

    private fun loadNotes(): MutableList<AnbuNote> = runCatching {
        val arr = JSONArray(store.read(BLOB_NOTES) ?: "[]")
        (0 until arr.length()).map { AnbuNote.fromJson(arr.getJSONObject(it)) }.toMutableList()
    }.getOrElse { mutableListOf() }

    private fun sameDay(a: Long, b: Long): Boolean =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

    companion object {
        const val BLOB_CONSENT = "consent"
        const val BLOB_TRIPS = "trips"
        const val BLOB_NOTES = "notes"
        const val BLOB_PLACES = "places"
        const val BLOB_WEEKLY = "weekly"
        const val BLOB_REPLIES = "replies"
        const val BLOB_REPLY_CURSOR = "replyCursor"
        private const val MAX_NOTES = 30
        private const val MAX_REPLIES = 20

        /** 헷갈리는 글자(0, O, 1, I)를 뺀 6자리 코드. */
        fun randomFamilyCode(): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val rnd = java.security.SecureRandom()
            return (1..6).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
