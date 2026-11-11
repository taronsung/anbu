package app.anbu.car.trip

import app.anbu.car.signals.AirbagEvent
import app.anbu.car.signals.BrakeEvent
import app.anbu.car.signals.CautionKind
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
import java.time.Instant
import java.time.ZoneId

data class TripConfig(
    /** 기어 P를 이만큼 유지하면 주행이 끝난 것으로 본다(SPEC F1: 20초). */
    val parkHoldMs: Long = 20_000,
    /** 급감속으로 세는 감속도. 3.5 m/s²는 약 0.36g다. */
    val hardBrakeMps2: Double = 3.5,
    /** 이 속도보다 느릴 때의 감속은 세지 않는다(주차장 저속 정지 제외). */
    val hardBrakeMinSpeedMps: Double = 5.0,
    /** 급감속 한 번을 센 뒤 다시 세기까지의 간격. */
    val hardBrakeRefractoryMs: Long = 5_000,
    /** 브레이크 신호가 속도 신호보다 이만큼 늦게 와도 같은 급감속으로 본다. */
    val brakeSignalLagMs: Long = 1_000,
    /** 이 속도를 넘을 때 안전띠가 풀려 있으면 "늦게 맴"으로 본다(시속 약 10km). */
    val lateBeltSpeedMps: Double = 2.8,
    /** 시계 기준 야간 시간대. 전조등이 켜져 있어도 야간으로 본다. */
    val nightStartHour: Int = 19,
    val nightEndHour: Int = 6,
    /** 이보다 짧은 이동(주차 자리 옮기기 등)은 주행으로 남기지 않는다. */
    val minTripKm: Double = 0.3,
    val minTripMovingMs: Long = 60_000,
)

sealed interface TripOutput {
    data class Started(val atMs: Long) : TripOutput
    data class HardBrake(val atMs: Long, val fromMps: Double, val toMps: Double) : TripOutput
    data class Ended(val summary: TripSummary) : TripOutput
    /** 너무 짧아서 기록하지 않고 버린 이동. */
    data class Discarded(val atMs: Long) : TripOutput
}

/**
 * 신호를 받아 주행 단위로 끊고 요약을 만든다.
 *
 * 안드로이드에 의존하지 않는 순수 코틀린이라 JVM 단위 테스트로 검증한다.
 * 시간 계산은 이벤트의 [SignalEvent.atMs]만 쓴다.
 */
class TripRecorder(
    private val config: TripConfig = TripConfig(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val idFactory: (Long) -> String = { "trip-$it" },
) {
    // 최신 차량 상태
    var gear: Gear = Gear.UNKNOWN; private set
    var speedMps: Double? = null; private set
    var brakePressed: Boolean? = null; private set
    var beltBuckled: Boolean? = null; private set
    var wiperOn: Boolean = false; private set
    var lowBeamOn: Boolean = false; private set
    var odometerKm: Double? = null; private set

    val inTrip: Boolean get() = trip != null

    private class Running(val startMs: Long, val startOdoKm: Double?) {
        var movingMs = 0L
        var nightMs = 0L
        var rainMs = 0L
        var integratedM = 0.0
        var hardBrakes = 0
        var lateBelt = false
        var reroutes = 0
        var schoolZones = 0
        var wrongWay = 0
        var arrivedPlaceId: String? = null
        var lastHardBrakeMs = Long.MIN_VALUE
        /** 감속은 컸지만 그 순간 브레이크 신호가 아직 "뗌"이던 경우. (시각, 전 속도, 후 속도) */
        var unconfirmedDecel: Triple<Long, Double, Double>? = null
        var passedBeltSpeed = false
    }

    private var trip: Running? = null
    private var lastAdvanceMs: Long? = null
    private var parkSinceMs: Long? = null
    private val speedWindow = ArrayDeque<Pair<Long, Double>>()

    fun onEvent(e: SignalEvent): List<TripOutput> {
        val out = ArrayList<TripOutput>(2)
        advance(e.atMs, out)
        when (e) {
            is GearEvent -> onGear(e, out)
            is SpeedEvent -> onSpeed(e, out)
            is BrakeEvent -> onBrake(e, out)
            is OdometerEvent -> odometerKm = e.km.toDouble()
            is DriverBeltEvent -> onBelt(e)
            is WiperEvent -> wiperOn = e.on
            is LowBeamEvent -> lowBeamOn = e.on
            is NaviRerouteEvent -> trip?.let { it.reroutes++ }
            is NaviCautionEvent -> trip?.let {
                when (e.kind) {
                    CautionKind.SCHOOL_ZONE -> it.schoolZones++
                    CautionKind.WRONG_WAY -> it.wrongWay++
                    CautionKind.OTHER -> Unit
                }
            }
            is NaviArrivedEvent -> trip?.let { if (e.placeId != null) it.arrivedPlaceId = e.placeId }
            is PlaceProximityEvent -> trip?.let {
                if (e.entered) it.arrivedPlaceId = e.placeId
                else if (it.arrivedPlaceId == e.placeId) it.arrivedPlaceId = null
            }
            is NaviEtaEvent, is AirbagEvent -> Unit
        }
        return out
    }

    /** 신호가 없어도 시간이 흐르면 P 유지 시간을 채울 수 있으므로 주기적으로 부른다. */
    fun onTick(nowMs: Long): List<TripOutput> {
        val out = ArrayList<TripOutput>(1)
        advance(nowMs, out)
        return out
    }

    /**
     * 기록을 (다시) 시작할 때 현재 차 상태를 알려 준다. 주행 중이 아닐 때만 받는다.
     * 기어는 받지 않는다. 다음 기어 신호나 속도 신호가 와야 주행이 시작된다.
     */
    fun prime(beltBuckled: Boolean?, brakePressed: Boolean?, wiperOn: Boolean?, lowBeamOn: Boolean?, odometerKm: Double?) {
        if (trip != null) return
        beltBuckled?.let { this.beltBuckled = it }
        brakePressed?.let { this.brakePressed = it }
        wiperOn?.let { this.wiperOn = it }
        lowBeamOn?.let { this.lowBeamOn = it }
        odometerKm?.let { this.odometerKm = it }
    }

    /** 쉬기나 동의 철회로 기록을 멈출 때. 진행 중이던 주행은 남기지 않는다. */
    fun abort() {
        trip = null
        parkSinceMs = null
        speedWindow.clear()
    }

    private fun isMoving(): Boolean {
        val v = speedMps
        return if (v != null) v > 0.5 else (gear == Gear.DRIVE || gear == Gear.REVERSE)
    }

    private fun isNight(atMs: Long): Boolean {
        if (lowBeamOn) return true
        val hour = Instant.ofEpochMilli(atMs).atZone(zone).hour
        return hour >= config.nightStartHour || hour < config.nightEndHour
    }

    private fun advance(nowMs: Long, out: MutableList<TripOutput>) {
        val last = lastAdvanceMs
        if (last != null && nowMs > last) {
            val dt = nowMs - last
            val t = trip
            // 신호가 오래 끊겼다가 돌아온 구간(1분 초과)은 적산하지 않는다.
            if (t != null && dt <= 60_000 && isMoving()) {
                t.movingMs += dt
                if (isNight(last)) t.nightMs += dt
                if (wiperOn) t.rainMs += dt
                t.integratedM += (speedMps ?: 0.0) * dt / 1000.0
            }
        }
        if (last == null || nowMs > last) lastAdvanceMs = nowMs

        val since = parkSinceMs
        if (trip != null && since != null && nowMs - since >= config.parkHoldMs) {
            finish(nowMs, out)
        }
    }

    private fun onGear(e: GearEvent, out: MutableList<TripOutput>) {
        gear = e.gear
        if (e.gear == Gear.PARK) {
            if (parkSinceMs == null) parkSinceMs = e.atMs
        } else {
            parkSinceMs = null
            if (trip == null && (e.gear == Gear.DRIVE || e.gear == Gear.REVERSE)) begin(e.atMs, out)
        }
    }

    private fun onSpeed(e: SpeedEvent, out: MutableList<TripOutput>) {
        val v = e.mps.toDouble().coerceAtLeast(0.0)
        if (trip == null && v > 1.0 && gear != Gear.PARK) begin(e.atMs, out)
        val t = trip
        if (t != null) {
            detectHardBrake(t, e.atMs, v, out)
            if (!t.passedBeltSpeed && v >= config.lateBeltSpeedMps) {
                t.passedBeltSpeed = true
                if (beltBuckled == false) t.lateBelt = true
            }
        }
        speedMps = v
        speedWindow.addLast(e.atMs to v)
        while (speedWindow.isNotEmpty() && e.atMs - speedWindow.first().first > 2_000) {
            speedWindow.removeFirst()
        }
    }

    private fun onBelt(e: DriverBeltEvent) {
        val t = trip
        // 이미 시속 10km를 넘겨 달리는 중에 띠를 맸다면 늦게 맨 것이다.
        if (t != null && e.buckled && beltBuckled == false && t.passedBeltSpeed) t.lateBelt = true
        beltBuckled = e.buckled
    }

    /**
     * 약 1초 구간의 속도 변화로 감속도를 구한다.
     * 브레이크 신호가 있으면 밟은 상태일 때만 세고, 신호가 없으면 감속도만으로 센다.
     */
    private fun detectHardBrake(t: Running, atMs: Long, v: Double, out: MutableList<TripOutput>) {
        val ref = speedWindow.lastOrNull { atMs - it.first in 500..1_500 }
            ?: speedWindow.lastOrNull()?.takeIf { atMs - it.first in 200..3_000 }
            ?: return
        val dt = (atMs - ref.first) / 1000.0
        val decel = (ref.second - v) / dt
        if (decel < config.hardBrakeMps2) return
        if (ref.second < config.hardBrakeMinSpeedMps) return
        if (brakePressed == false) {
            // 속도와 브레이크는 서로 다른 경로(AAOS Car API, Pleos Vehicle SDK)로 와서 순서가 뒤바뀔 수 있다.
            // 곧이어 브레이크 신호가 오면 그때 센다.
            if (t.unconfirmedDecel == null) t.unconfirmedDecel = Triple(atMs, ref.second, v)
            return
        }
        countHardBrake(t, atMs, ref.second, v, out)
    }

    private fun onBrake(e: BrakeEvent, out: MutableList<TripOutput>) {
        brakePressed = e.pressed
        val t = trip ?: return
        val pending = t.unconfirmedDecel ?: return
        t.unconfirmedDecel = null
        if (e.pressed && e.atMs - pending.first <= config.brakeSignalLagMs) {
            countHardBrake(t, e.atMs, pending.second, pending.third, out)
        }
    }

    private fun countHardBrake(t: Running, atMs: Long, from: Double, to: Double, out: MutableList<TripOutput>) {
        t.unconfirmedDecel = null
        if (t.lastHardBrakeMs != Long.MIN_VALUE && atMs - t.lastHardBrakeMs < config.hardBrakeRefractoryMs) return
        t.lastHardBrakeMs = atMs
        t.hardBrakes++
        out += TripOutput.HardBrake(atMs, from, to)
    }

    private fun begin(atMs: Long, out: MutableList<TripOutput>) {
        trip = Running(atMs, odometerKm)
        speedWindow.clear()
        out += TripOutput.Started(atMs)
    }

    private fun finish(nowMs: Long, out: MutableList<TripOutput>) {
        val t = trip ?: return
        val endMs = parkSinceMs ?: nowMs
        trip = null
        parkSinceMs = if (gear == Gear.PARK) endMs else null
        speedWindow.clear()

        val odoStart = t.startOdoKm
        val odoNow = odometerKm
        val odoDelta = if (odoStart != null && odoNow != null && odoNow >= odoStart) odoNow - odoStart else null
        // 주행거리계는 1km 단위로만 바뀌는 차가 있어, 0이면 속도 적산값을 쓴다.
        val distanceKm = if (odoDelta != null && odoDelta > 0.0) odoDelta else t.integratedM / 1000.0

        if (distanceKm < config.minTripKm && t.movingMs < config.minTripMovingMs) {
            out += TripOutput.Discarded(endMs)
            return
        }
        out += TripOutput.Ended(
            TripSummary(
                id = idFactory(t.startMs),
                startMs = t.startMs,
                endMs = endMs,
                distanceKm = distanceKm,
                movingMs = t.movingMs,
                nightMs = t.nightMs,
                rainMs = t.rainMs,
                hardBrakes = t.hardBrakes,
                lateBelt = t.lateBelt,
                reroutes = t.reroutes,
                schoolZoneAlerts = t.schoolZones,
                wrongWayAlerts = t.wrongWay,
                arrivedPlaceId = t.arrivedPlaceId,
            )
        )
    }
}
