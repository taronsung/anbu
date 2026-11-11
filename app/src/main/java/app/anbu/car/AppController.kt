package app.anbu.car

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.anbu.car.anbu.AnbuNote
import app.anbu.car.anbu.FamilyReply
import app.anbu.car.anbu.NoteType
import app.anbu.car.anbu.Outbox
import app.anbu.car.anbu.RelayTransport
import app.anbu.car.anbu.label
import app.anbu.car.core.AnbuEngine
import app.anbu.car.core.BlockReason
import app.anbu.car.core.CheckInReason
import app.anbu.car.core.DiaryView
import app.anbu.car.core.EngineEffect
import app.anbu.car.core.VehicleView
import app.anbu.car.gleo.GleoActions
import app.anbu.car.navi.NaviGateway
import app.anbu.car.navi.Place
import app.anbu.car.navi.Places
import app.anbu.car.navi.ProximityGateway
import app.anbu.car.navi.withDirectionParticle
import app.anbu.car.privacy.ConsentState
import app.anbu.car.privacy.KeystoreBlobStore
import app.anbu.car.signals.AaosCarSource
import app.anbu.car.signals.DemoScript
import app.anbu.car.signals.DrivingRestrictions
import app.anbu.car.signals.Gear
import app.anbu.car.signals.PleosVehicleSource
import app.anbu.car.signals.SignalClock
import app.anbu.car.signals.SignalEvent
import app.anbu.car.signals.SimulatedSource
import app.anbu.car.signals.SourceKind
import app.anbu.car.signals.SourceStatus
import app.anbu.car.voice.VoiceCommand
import app.anbu.car.voice.VoiceCommands
import app.anbu.car.voice.VoiceEngine
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

enum class InviteStep { NONE, CODE, PERMISSIONS }

enum class Screen { CONSENT, HOME, DRIVING, ARRIVAL, DIARY, CHECK_IN, SETTINGS, PLACES, DELETE, STATUS }

data class ArrivalUi(val note: AnbuNote, val secondsLeft: Int, val totalSeconds: Int)
data class CheckInUi(val reason: CheckInReason, val secondsLeft: Int, val totalSeconds: Int)

/** 연결 상태 화면에 보여 주는, 지금 무엇이 실제로 연결되어 있는지에 대한 사실. */
data class StatusInfo(
    val pleos: SourceStatus = SourceStatus(SourceKind.PLEOS, false),
    val aaos: SourceStatus = SourceStatus(SourceKind.AAOS, false),
    val simulationPlaying: Boolean = false,
    val naviConnected: Boolean = false,
    val naviError: String? = null,
    val proximitySupported: Boolean = false,
    val proximityWatching: Int = 0,
    val proximityError: String? = null,
    val ttsBackend: String = "",
    val sttBackend: String = "",
    val uxRestrictionsConnected: Boolean = false,
    val relayUrl: String = "",
    val outboxPending: Int = 0,
    val lastRelayOk: Boolean? = null,
    val tripsStored: Int = 0,
)

data class UiState(
    val screen: Screen = Screen.CONSENT,
    val consent: ConsentState = ConsentState(),
    val vehicle: VehicleView = VehicleView(),
    val places: List<Place> = Places.defaults,
    val inTrip: Boolean = false,
    val destination: Place? = null,
    val etaText: String? = null,
    val arrival: ArrivalUi? = null,
    val checkIn: CheckInUi? = null,
    val diary: DiaryView? = null,
    val lastNote: AnbuNote? = null,
    /** 가족이 보낸 가장 최근 답장. 홈에 카드로 남긴다. */
    val lastReply: FamilyReply? = null,
    val departureSentThisTrip: Boolean = false,
    val banner: String? = null,
    val paused: Boolean = false,
    /** 쉬는 까닭이 "이번 운전만 쉬기"인가. */
    val skippingTrip: Boolean = false,
    /** 동의하지 않고 길안내만 쓰는 상태. */
    val limited: Boolean = false,
    /** 동의 직후 안내 단계. 초대 코드를 보여 준 뒤 필요한 권한을 설명한다. */
    val invite: InviteStep = InviteStep.NONE,
    /** 아직 허용받지 못한 실행 중 권한. */
    val missingSpeedPermission: Boolean = false,
    val missingLocationPermission: Boolean = false,
    /** 지금 화면에 보이는 차량 신호가 실제 차/에뮬레이터가 아니라 대본에서 온 것인가. */
    val simulated: Boolean = false,
    /** 주행 규제 상태(CarUxRestrictions). */
    val restricted: Boolean = false,
    val listening: Boolean = false,
    val heard: String = "",
    val status: StatusInfo = StatusInfo(),
)

/**
 * 화면과 엔진, SDK 사이를 잇는다. 앱 프로세스에 하나만 있고 모든 처리는 앱 스레드에서 한다.
 * 네트워크 전송만 별도 스레드에서 돈다.
 */
class AppController(private val app: Application) {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs = app.getSharedPreferences("anbu_settings", Context.MODE_PRIVATE)
    private val wall: () -> Long = { System.currentTimeMillis() }
    private val post: (() -> Unit) -> Unit = { f -> main.post(f) }

    val clock = SignalClock(wall)
    private val store = KeystoreBlobStore(app)
    private val transport = RelayTransport { relayUrl }
    private val outbox = Outbox(store, transport)
    val engine = AnbuEngine(store, outbox, wall)

    private val pleos = PleosVehicleSource(app, clock, post)
    private val aaos = AaosCarSource(app, clock, post)
    private val sim = SimulatedSource(clock)
    private val restrictions = DrivingRestrictions(app, post)
    private val navi = NaviGateway(
        app, clock, post, { onRealSignal(it, null) }, ::refresh,
        onRouteDropped = { engine.setDestination(null); refresh() },
    )
    private val proximity = ProximityGateway(app, clock, post) { onRealSignal(it, null) }
    val voice = VoiceEngine(app, ::refresh)

    private var pleosStatus = SourceStatus(SourceKind.PLEOS, false)
    private var aaosStatus = SourceStatus(SourceKind.AAOS, false)
    private var userScreen = Screen.HOME
    private var banner: String? = null
    private var bannerUntil = 0L
    private var departureSentThisTrip = false
    /** 지금 떠 있는 카운트다운이나 괜찮으세요 화면이 대본 재생에서 나온 것인가. 대본이 끝나도 표시를 유지한다. */
    private var promptFromSimulation = false
    private var lastRelayOk: Boolean? = null
    private val flushing = AtomicBoolean(false)
    private val fetchingReplies = AtomicBoolean(false)
    private var nextReplyPollMs = 0L
    private var proximitySynced: Pair<Boolean, List<Place>>? = null
    private var started = false
    private var declined = prefs.getBoolean(KEY_DECLINED, false)
    private var invite = InviteStep.NONE

    var relayUrl: String = prefs.getString(KEY_RELAY, null) ?: BuildConfig.DEFAULT_RELAY_URL
        private set

    /** 실행 중 권한을 물어야 할 때 화면(Activity)이 등록해 둔다. */
    var requestPermissions: ((List<String>) -> Unit)? = null

    var ui by mutableStateOf(UiState())
        private set

    // ---------------------------------------------------------------- 시작

    fun start() {
        if (started) return
        started = true
        engine.listener = ::onEffect
        engine.debugLog = { android.util.Log.d("AnbuEngine", it) }
        pleos.start({ onRealSignal(it, SourceKind.PLEOS) }) { pleosStatus = it; refresh() }
        aaos.start({ onRealSignal(it, SourceKind.AAOS) }) { aaosStatus = it; refresh() }
        sim.start({ engine.onSignal(it); refresh() }) { }
        restrictions.start(::refresh)
        navi.start()
        proximity.start()
        voice.start()
        refresh()
        main.postDelayed(::tick, TICK_MS)
    }

    private fun tick() {
        val wasPlaying = sim.playing
        sim.pump()
        if (wasPlaying && !sim.playing) resyncRealSignals()
        engine.onTick(clock.now())
        syncProximity()
        if (banner != null && wall() > bannerUntil) banner = null
        flushOutbox()
        pollReplies()
        refresh()
        main.postDelayed(::tick, TICK_MS)
    }

    private fun onRealSignal(e: SignalEvent, from: SourceKind?) {
        // 대본을 재생하는 동안에는 실제 신호가 섞이지 않게 한다.
        if (sim.playing) return
        engine.onSignal(e)
        refresh()
    }

    fun onPermissionsChanged() {
        aaos.registerAvailable()
        // 위치 권한 상태가 바뀌면 syncProximity가 다음 틱에 장소 알림을 걸거나 푼다.
        refresh()
    }

    // ---------------------------------------------------------------- 엔진 효과

    private fun onEffect(effect: EngineEffect) {
        when (effect) {
            // 출발 한마디는 차를 움직이기 직전에 보내는 경우가 많다. 주행이 끝날 때까지 "전했어요"를 유지한다.
            is EngineEffect.TripStarted -> Unit
            is EngineEffect.TripEnded -> {
                departureSentThisTrip = false
                if (effect.unnamedPlace) {
                    showBanner("주행을 기록했어요. 이름 붙인 곳이 아니라서 안부는 보내지 않았어요.")
                } else if (!effect.notifying) {
                    showBanner("주행을 기록했어요. 안부는 보내지 않았어요.")
                }
            }
            is EngineEffect.ArrivalCountdown -> {
                promptFromSimulation = sim.playing
                voice.speak("가족에게 도착 안부를 보낼게요. 보내지 않으려면, 보내지 마, 라고 말씀하세요.") {
                    if (engine.pendingArrival != null) {
                        voice.listen(7_000) { text, _ ->
                            when (VoiceCommands.parse(text)) {
                                VoiceCommand.CANCEL_SEND -> cancelArrival()
                                VoiceCommand.SEND_NOW -> sendArrivalNow()
                                else -> Unit
                            }
                        }
                    }
                }
            }
            is EngineEffect.ArrivalCancelled -> {
                voice.stopListening()
                voice.speak("보내지 않았어요.")
                showBanner("도착 안부를 보내지 않았어요.")
            }
            is EngineEffect.NoteQueued -> {
                voice.stopListening()
                when (effect.note.type) {
                    NoteType.ARRIVAL -> {
                        voice.speak("가족에게 도착 안부를 보냈어요.")
                        showBanner("가족에게 도착 안부를 보냈어요.")
                    }
                    NoteType.DEPARTURE -> {
                        departureSentThisTrip = true
                        voice.speak("출발한다고 전했어요.")
                        showBanner("가족에게 출발한다고 전했어요.")
                    }
                    // 일기 화면에 도장이 바로 찍히므로 따로 띄우지 않는다.
                    NoteType.WEEKLY -> voice.speak("이번 주 운전 일기를 가족과 나눴어요.")
                    NoteType.CHECK_IN -> Unit
                }
                flushOutbox(force = true)
            }
            is EngineEffect.CheckInAsked -> {
                promptFromSimulation = sim.playing
                voice.speak("괜찮으세요? 괜찮으시면, 괜찮아, 라고 말씀해 주세요.") {
                    if (engine.checkIn != null) {
                        voice.listen(20_000) { text, _ ->
                            when (VoiceCommands.parse(text)) {
                                VoiceCommand.IM_OK -> answerCheckInOk()
                                VoiceCommand.NEED_HELP -> { engine.answerCheckInNeedHelp(); refresh() }
                                else -> Unit
                            }
                        }
                    }
                }
            }
            is EngineEffect.CheckInAnswered -> {
                voice.stopListening()
                voice.speak("네, 알겠습니다.")
                showBanner("괜찮다고 답하셨어요. 가족에게는 알리지 않았어요.")
            }
            is EngineEffect.CheckInEscalated -> {
                voice.stopListening()
                if (effect.sentToFamily) {
                    voice.speak("답이 없으셔서 가족에게 알렸어요.")
                    showBanner("답이 없어서 가족에게 알렸어요.")
                } else {
                    showBanner("가족 알림이 꺼져 있어 알리지 않았어요.")
                }
            }
            is EngineEffect.TripSkipEnded -> showBanner("쉬었던 운전이 끝났어요. 다시 시작했어요.")
            is EngineEffect.RepliesReady -> {
                // 엔진은 기어가 P이고 다른 안내가 끝난 뒤에만 이 효과를 낸다.
                val texts = effect.replies.map { it.preset.text }
                val spoken = if (texts.size == 1) {
                    "가족이 답장을 보냈어요. ${texts[0]}."
                } else {
                    "가족이 답장을 ${texts.size}개 보냈어요. ${texts.joinToString(". ")}."
                }
                voice.speak(spoken)
                showBanner("가족의 답장: ${texts.last()}")
            }
            is EngineEffect.Blocked -> showBanner(
                when (effect.reason) {
                    BlockReason.NO_CONSENT -> "먼저 동의가 필요해요."
                    BlockReason.PAUSED -> "오늘은 쉬는 중이라 보내지 않았어요."
                    BlockReason.SHARING_OFF -> "안부 보내기가 꺼져 있어요. 설정에서 켤 수 있어요."
                }
            )
        }
        refresh()
    }

    // ---------------------------------------------------------------- 화면에서 부르는 동작

    fun acceptConsent(who: String, shareArrival: Boolean, useProximity: Boolean, checkInAlert: Boolean) {
        engine.grantConsent(who, shareArrival, useProximity, checkInAlert)
        setDeclined(false)
        invite = InviteStep.CODE
        userScreen = Screen.HOME
        refresh()
    }

    /** 초대 코드 안내를 읽고 "다음"을 눌렀다. 허용받을 권한이 남아 있으면 먼저 설명한다. */
    fun finishInviteCode() {
        invite = if (missingPermissions().isEmpty()) InviteStep.NONE else InviteStep.PERMISSIONS
        refresh()
    }

    /** 권한 설명을 읽고 허용 화면을 연다. */
    fun askPermissions() {
        invite = InviteStep.NONE
        val missing = missingPermissions()
        if (missing.isNotEmpty()) requestPermissions?.invoke(missing)
        refresh()
    }

    fun skipPermissions() {
        invite = InviteStep.NONE
        refresh()
    }

    /**
     * 실행 중에 허용받아야 하는 권한.
     *  - 차 속도(android.car.permission.CAR_SPEED): 급하게 멈춘 횟수를 세는 데 쓴다.
     *  - Pleos 위치(pleos.car.permission.FUSED_LOCATION): 저장 장소 도착 판정을 켠 경우에만 쓴다.
     */
    private fun missingPermissions(): List<String> = buildList {
        if (!aaos.hasSpeedPermission()) add(android.car.Car.PERMISSION_SPEED)
        if (engine.consent.useProximity && !hasLocationPermission()) add(PERMISSION_FUSED_LOCATION)
    }

    private fun hasLocationPermission(): Boolean =
        app.checkSelfPermission(PERMISSION_FUSED_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 동의하지 않고 길안내만 쓴다. 기록과 전송은 하지 않는다. */
    fun declineConsent() {
        setDeclined(true)
        userScreen = Screen.HOME
        refresh()
    }

    fun reopenConsent() {
        setDeclined(false)
        refresh()
    }

    private fun setDeclined(value: Boolean) {
        declined = value
        prefs.edit().putBoolean(KEY_DECLINED, value).apply()
    }

    fun go(screen: Screen) {
        if (screen == Screen.DIARY && !canShowDiary()) {
            showBanner("운전 일기는 차를 세우고 P에 두었을 때 볼 수 있어요.")
        } else {
            userScreen = screen
        }
        refresh()
    }

    fun tapPlace(place: Place) {
        engine.setDestination(place)
        val requested = navi.requestRoute(place)
        val to = withDirectionParticle(place.label)
        if (requested) {
            voice.speak("$to 안내할게요.")
            showBanner("$to 길안내를 요청했어요.")
        } else {
            voice.speak("$to 가시는군요. 목적지를 기억해 둘게요.")
            showBanner("내비게이션에 연결되지 않아 목적지만 기억해 두었어요.")
        }
        refresh()
    }

    fun sendDeparture() {
        engine.sendDeparture()
        refresh()
    }

    fun cancelArrival() {
        engine.cancelArrival()
        refresh()
    }

    fun sendArrivalNow() {
        engine.sendArrivalNow()
        refresh()
    }

    fun answerCheckInOk() {
        engine.answerCheckInOk()
        refresh()
    }

    fun shareWeekly() {
        engine.shareWeekly()
        refresh()
    }

    fun keepWeeklyPrivate() {
        engine.keepWeeklyPrivate()
        showBanner("이번 주 일기는 나만 보기로 했어요.")
        refresh()
    }

    fun readDiaryAloud() {
        val d = engine.diary()
        val details = engine.composer.weeklyDetails(d.week).entries.take(5)
            .joinToString(", ") { "${it.key} ${it.value}" }
        voice.speak("${d.line} $details.")
    }

    fun updateConsent(transform: (ConsentState) -> ConsentState) {
        val before = engine.consent.useProximity
        engine.updateConsent(transform)
        // 장소 도착 판정을 새로 켰는데 위치 권한이 없으면 그때 묻는다.
        if (!before && engine.consent.useProximity && !hasLocationPermission()) {
            requestPermissions?.invoke(listOf(PERMISSION_FUSED_LOCATION))
        }
        refresh()
    }

    fun pauseToday() {
        engine.pauseToday()
        voice.stopListening()
        showBanner("오늘은 쉬어요. 자정까지 기록도 전송도 하지 않아요.")
        refresh()
    }

    /** "이번 운전만 쉬기". 다른 사람이 운전할 때. */
    fun pauseThisTrip() {
        engine.pauseThisTrip()
        voice.stopListening()
        showBanner("이번 운전은 쉬어요. 주차하면 다시 시작해요.")
        refresh()
    }

    fun resume() {
        engine.resumeNow()
        showBanner("다시 시작했어요.")
        refresh()
    }

    fun deleteEverything() {
        sim.cancelPlayback()
        voice.stopListening()
        proximity.clear()
        proximitySynced = null
        val code = engine.deleteEverything()
        if (code != null) io.execute { transport.deleteFamily(code) }
        setDeclined(false)
        invite = InviteStep.NONE
        userScreen = Screen.HOME
        banner = null
        refresh()
    }

    fun savePlaceHere(place: Place) {
        proximity.readCurrentLocation(
            onResult = { lat, lon ->
                engine.updatePlaceCoordinate(place.id, lat, lon)
                proximitySynced = null
                showBanner("지금 있는 곳을 ${place.label} 위치로 저장했어요.")
                refresh()
            },
            onFail = {
                showBanner("지금 위치를 읽지 못했어요.")
                refresh()
            },
        )
    }

    fun setRelayUrl(url: String) {
        relayUrl = url.trim()
        prefs.edit().putString(KEY_RELAY, relayUrl).apply()
        refresh()
    }

    /**
     * "말로 하기" 단추. SpeechToText SDK로 6초 동안 듣고, 알아들은 명령을 실행한다.
     * 듣는 중에 다시 누르면 듣기를 멈춘다.
     */
    fun listenForCommand() {
        if (voice.listening) {
            voice.stopListening()
            refresh()
            return
        }
        voice.stopSpeaking()
        val ok = voice.listen(6_000) { text, completed ->
            val cmd = VoiceCommands.parse(text)
            if (cmd != VoiceCommand.NONE) {
                voice.stopListening()
                runVoiceCommand(cmd)
            } else if (completed) {
                showBanner("잘 듣지 못했어요. 화면 버튼을 눌러 주세요.")
            }
        }
        if (!ok) showBanner("이 환경에서는 음성 듣기를 쓸 수 없어요. 화면 버튼을 눌러 주세요.")
        else if (!isDriving()) showBanner("듣고 있어요. \"출발한다고 전해 줘\"처럼 말씀하세요.")
        refresh()
    }

    private fun runVoiceCommand(cmd: VoiceCommand) {
        when (cmd) {
            VoiceCommand.SEND_DEPARTURE -> sendDeparture()
            VoiceCommand.SEND_ARRIVAL -> { engine.requestArrivalNote(); refresh() }
            VoiceCommand.READ_DIARY -> if (canShowDiary()) { go(Screen.DIARY); readDiaryAloud() } else go(Screen.DIARY)
            VoiceCommand.CANCEL_SEND -> cancelArrival()
            VoiceCommand.SEND_NOW -> sendArrivalNow()
            VoiceCommand.IM_OK -> answerCheckInOk()
            VoiceCommand.NEED_HELP -> { engine.answerCheckInNeedHelp(); refresh() }
            VoiceCommand.NONE -> Unit
        }
    }

    // ---------------------------------------------------------------- 시연용

    /** 병원 가는 길 대본을 재생한다. 화면에 "시뮬레이션" 표시가 붙는다. */
    fun playDemoTrip() {
        val hospital = engine.places.first { it.id == Places.HOSPITAL }
        engine.setDestination(hospital)
        userScreen = Screen.HOME
        sim.play { DemoScript.hospitalTrip(it, hospital.id) }
        refresh()
    }

    fun playDemoHardStop() {
        userScreen = Screen.HOME
        sim.play { DemoScript.hardStop(it) }
        refresh()
    }

    fun stopDemo() {
        sim.cancelPlayback()
        resyncRealSignals()
        refresh()
    }

    /** 대본 재생이 끝나면 화면의 차량 상태를 실제 신호 값으로 되돌린다. */
    private fun resyncRealSignals() {
        aaos.refreshCurrent()
        pleos.refreshCurrent()
    }

    fun seedExampleHistory() {
        engine.seedExampleHistory()
        showBanner("예시 기록을 넣었어요. 실제 주행 기록이 아니에요.")
        refresh()
    }

    // ---------------------------------------------------------------- Gleo

    fun handleGleoAction(action: String, rawParam: String?): GleoActions.Reply {
        val params = GleoActions.parseParams(rawParam)
        val reply = when (action) {
            GleoActions.SEND_DEPARTURE_NOTE -> {
                params.optString("destination", "").takeIf { it.isNotEmpty() }?.let { label ->
                    engine.places.firstOrNull { it.label == label }?.let { engine.setDestination(it) }
                }
                val note = engine.sendDeparture()
                if (note != null) {
                    GleoActions.Reply(action, "success", "The departure note was sent to the family.", noteData(note))
                } else {
                    GleoActions.Reply(action, "fail", blockedMessage())
                }
            }
            GleoActions.SEND_ARRIVAL_NOTE -> {
                if (engine.requestArrivalNote()) {
                    GleoActions.Reply(
                        action, "success",
                        "The arrival note will be sent in ten seconds unless the driver cancels it.",
                    )
                } else {
                    GleoActions.Reply(action, "fail", blockedMessage())
                }
            }
            GleoActions.READ_WEEKLY_DIARY -> {
                if (!engine.consent.granted) {
                    GleoActions.Reply(action, "fail", "The driver has not agreed to keep a driving diary yet.")
                } else if (!canShowDiary()) {
                    GleoActions.Reply(action, "fail", "The weekly driving diary is only shown while the car is parked.")
                } else {
                    userScreen = Screen.DIARY
                    readDiaryAloud()
                    val d = engine.diary()
                    val data = JSONArray().put(
                        JSONObject().put("level", d.assessment.overall.label()).put("summary", d.line)
                    ).toString()
                    GleoActions.Reply(action, "success", "The weekly driving diary is being read to the driver.", data)
                }
            }
            else -> GleoActions.Reply(action, "fail", "This action is not supported.")
        }
        refresh()
        return reply
    }

    private fun noteData(note: AnbuNote): String = JSONArray().put(
        JSONObject().put("message", note.line).apply { note.etaText?.let { put("eta", it) } }
    ).toString()

    private fun blockedMessage(): String = when {
        !engine.consent.granted -> "The driver has not agreed to send notes yet."
        engine.gate.isPaused(wall()) -> "Sending is paused for today at the driver's request."
        else -> "Sending notes to the family is turned off in the settings."
    }

    // ---------------------------------------------------------------- 내부

    private fun canShowDiary(): Boolean {
        if (restrictions.requiresDistractionOptimization) return false
        val gear = engine.vehicle.gear
        return gear == Gear.PARK || gear == Gear.UNKNOWN
    }

    private fun isDriving(): Boolean {
        if (restrictions.requiresDistractionOptimization) return true
        val gear = engine.vehicle.gear
        return gear == Gear.DRIVE || gear == Gear.REVERSE || gear == Gear.NEUTRAL
    }

    private fun showBanner(text: String) {
        banner = text
        bannerUntil = wall() + 7_000
    }

    private fun flushOutbox(force: Boolean = false) {
        if (!force && outbox.pending().none { it.nextAttemptMs <= wall() }) return
        if (!flushing.compareAndSet(false, true)) return
        io.execute {
            try {
                val before = outbox.pending().size
                val sent = outbox.flush(System.currentTimeMillis())
                if (before > 0) lastRelayOk = sent > 0
            } finally {
                flushing.set(false)
            }
        }
    }

    /**
     * 가족의 답장을 릴레이에 물어본다. 동의했고 쉬는 중이 아니며 답장 받기를 켠 경우에만 묻는다.
     * 받은 답장을 언제 읽을지는 엔진이 정한다(기어 P일 때만).
     */
    private fun pollReplies() {
        val now = wall()
        if (now < nextReplyPollMs || !engine.canReceiveReplies()) return
        nextReplyPollMs = now + REPLY_POLL_MS
        if (!fetchingReplies.compareAndSet(false, true)) return
        val code = engine.consent.familyCode
        val after = engine.replyCursor
        io.execute {
            try {
                val got = transport.fetchReplies(code, after)
                if (!got.isNullOrEmpty()) main.post { engine.onRepliesFetched(got); refresh() }
            } finally {
                fetchingReplies.set(false)
            }
        }
    }

    /** 근접 알림은 본인이 켰고 쉬는 중이 아닐 때만 걸어 둔다. */
    private fun syncProximity() {
        val wanted = engine.gate.canUseProximity(wall()) && proximity.supported && hasLocationPermission()
        // 원하는 상태(걸기/풀기)나 장소 목록이 바뀐 때에만 다시 건다.
        val key = wanted to engine.places
        if (proximitySynced == key) return
        proximitySynced = key
        if (wanted) proximity.watch(engine.places) else proximity.clear()
    }

    private fun refresh() {
        val now = wall()
        val consent = engine.consent
        val limited = !consent.granted && declined
        val screen = when {
            !consent.granted && !declined -> Screen.CONSENT
            invite != InviteStep.NONE -> Screen.CONSENT
            engine.checkIn != null -> Screen.CHECK_IN
            engine.pendingArrival != null -> Screen.ARRIVAL
            isDriving() -> Screen.DRIVING
            userScreen == Screen.DIARY && !canShowDiary() -> Screen.HOME
            limited && userScreen != Screen.STATUS -> Screen.HOME
            else -> userScreen
        }
        val realAvailable = pleosStatus.available || aaosStatus.available
        ui = UiState(
            screen = screen,
            consent = consent,
            vehicle = engine.vehicle,
            places = engine.places,
            inTrip = engine.inTrip,
            destination = engine.activeDestination,
            etaText = engine.etaAtMs?.let { engine.composer.etaText(it) },
            arrival = engine.pendingArrival?.let {
                ArrivalUi(it.note, secondsLeft(it.deadlineWallMs, now), 10)
            },
            checkIn = engine.checkIn?.let {
                CheckInUi(it.reason, secondsLeft(it.deadlineWallMs, now), 30)
            },
            diary = if (screen == Screen.DIARY) engine.diary() else null,
            lastNote = engine.recentNotes.lastOrNull(),
            lastReply = engine.recentReplies.lastOrNull()?.takeIf { now - it.atMs <= 12L * 3600 * 1000 },
            departureSentThisTrip = departureSentThisTrip,
            banner = banner,
            paused = engine.gate.isPaused(now),
            skippingTrip = consent.skipTrip,
            limited = limited,
            invite = invite,
            missingSpeedPermission = !aaos.hasSpeedPermission(),
            missingLocationPermission = consent.useProximity && !hasLocationPermission(),
            simulated = sim.playing || !realAvailable ||
                (promptFromSimulation && (engine.pendingArrival != null || engine.checkIn != null)),
            restricted = restrictions.requiresDistractionOptimization,
            listening = voice.listening,
            heard = voice.lastHeard,
            status = StatusInfo(
                pleos = pleosStatus,
                aaos = aaosStatus,
                simulationPlaying = sim.playing,
                naviConnected = navi.connected,
                naviError = navi.lastError,
                proximitySupported = proximity.supported,
                proximityWatching = proximity.watching,
                proximityError = proximity.lastError,
                ttsBackend = voice.ttsBackend,
                sttBackend = voice.sttBackend,
                uxRestrictionsConnected = restrictions.connected,
                relayUrl = relayUrl,
                outboxPending = outbox.pending().size,
                lastRelayOk = lastRelayOk,
                tripsStored = engine.tripCount,
            ),
        )
    }

    private fun secondsLeft(deadline: Long, now: Long): Int =
        ceil((deadline - now).coerceAtLeast(0) / 1000.0).toInt()

    private companion object {
        const val TICK_MS = 250L
        const val REPLY_POLL_MS = 3_000L
        const val KEY_RELAY = "relay_url"
        const val KEY_DECLINED = "consent_declined"
        const val PERMISSION_FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
    }
}
