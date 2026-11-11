package app.anbu.car.signals

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltNotBuckledWarningListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.util.Log

/**
 * Pleos Vehicle SDK(ai.pleos.playground:Vehicle)로 차량 신호를 받는다.
 *
 * 도메인마다 순서는 같다.
 *  1. checkXxxCapabilities 로 이 차(또는 에뮬레이터)가 그 신호를 주는지 확인한다.
 *  2. 되면 현재 값을 한 번 읽고 리스너를 등록한다.
 *  3. 안 되면 그 신호는 "받지 못함"으로 표시하고 나머지는 계속 쓴다.
 *
 * SDK 콜백은 다른 스레드에서 올 수 있으므로 [post]로 앱 스레드에 넘긴다.
 *
 * 공개 API만 쓴다. SDK 안에 있더라도 공개 목록에 없는 것(Gear 도메인, 비공개 권한이
 * 필요한 Telltales)은 쓰지 않고, 기어와 속도는 AAOS Car API에서 받는다(AaosCarSource).
 */
class PleosVehicleSource(
    private val context: Context,
    private val clock: SignalClock,
    private val post: (() -> Unit) -> Unit,
) : VehicleSignalSource {

    override val kind = SourceKind.PLEOS

    private var vehicle: Vehicle? = null
    private var onEvent: ((SignalEvent) -> Unit)? = null
    private var onStatus: ((SourceStatus) -> Unit)? = null
    private val capabilities = LinkedHashSet<Capability>()
    private val unsupported = LinkedHashSet<String>()
    // SDK 콜백 스레드에서도 추가하므로 동시 접근에 안전한 목록을 쓴다.
    private val cleanups = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    /** 현재 값을 다시 읽는 방법. 시뮬레이션이 끝난 뒤 실제 상태로 되돌릴 때 쓴다. */
    private val rereads = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    // 국내 좌핸들 기준 운전석
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)

    override fun start(onEvent: (SignalEvent) -> Unit, onStatus: (SourceStatus) -> Unit) {
        this.onEvent = onEvent
        this.onStatus = onStatus
        val v = try {
            Vehicle(context.applicationContext).also { it.initialize() }
        } catch (t: Throwable) {
            Log.w(TAG, "Vehicle SDK initialize failed", t)
            onStatus(SourceStatus(kind, false, detail = "Vehicle SDK 초기화 실패: ${t.javaClass.simpleName}"))
            return
        }
        vehicle = v
        guarded("brake") { wireBrake(v) }
        guarded("odometer") { wireOdometer(v) }
        guarded("seatBelt") { wireSeatBelt(v) }
        guarded("airbag") { wireAirbag(v) }
        guarded("wiper") { wireWiper(v) }
        guarded("lowBeam") { wireLowBeam(v) }
    }

    override fun stop() {
        cleanups.forEach { runCatching(it) }
        cleanups.clear()
        rereads.clear()
        runCatching { vehicle?.release() }
        vehicle = null
        onEvent = null
        onStatus = null
    }

    // ------------------------------------------------------------ 도메인별 연결

    private fun wireBrake(v: Vehicle) {
        val brake = v.getBrake()
        val listener = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
                pressed?.let { emit { t -> BrakeEvent(t, it) } }
            }
            override fun onFailed(e: Exception) = listenerFailed("brake", e)
        }
        brake.checkBrakePedalStatusCapabilities(
            onComplete = { _ ->
                supported(Capability.BRAKE)
                reread {
                    brake.getCurrentBrakePedalStatus(
                        onComplete = { p -> p?.let { emit { t -> BrakeEvent(t, it) } } },
                        onFailed = { readFailed("brake", it) },
                    )
                }
                brake.registerBrakePedalStatus(listener)
                cleanups += { brake.unregisterBrakePedalStatus(listener) }
            },
            onFailed = { notSupported("brake", it) },
        )
    }

    private fun wireOdometer(v: Vehicle) {
        val odo = v.getOdometer()
        val listener = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(distance: Float?) {
                distance?.let { emit { t -> OdometerEvent(t, it) } }
            }
            override fun onFailed(e: Exception) = listenerFailed("odometer", e)
        }
        odo.checkCurrentDistanceDrivenCapabilities(
            onComplete = { _ ->
                supported(Capability.ODOMETER)
                reread {
                    odo.getCurrentDistanceDriven(
                        onComplete = { km -> km?.let { emit { t -> OdometerEvent(t, it) } } },
                        onFailed = { readFailed("odometer", it) },
                    )
                }
                odo.registerDistanceDriven(listener)
                cleanups += { odo.unregisterDistanceDriven(listener) }
            },
            onFailed = { notSupported("odometer", it) },
        )
    }

    /** 안전띠: 체결 상태(Seat)와 미체결 경고(Safety)를 모두 본다. 차종에 따라 한쪽만 줄 수 있다. */
    private fun wireSeatBelt(v: Vehicle) {
        val seat = v.getSeat()
        val buckledListener = object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) {
                buckled?.let { emit { t -> DriverBeltEvent(t, it) } }
            }
            override fun onFailed(e: Exception) = listenerFailed("seatBelt", e)
        }
        seat.checkSeatBeltBuckledCapabilities(
            onComplete = { _, areas ->
                if (areas.isNotEmpty()) {
                    supported(Capability.SEAT_BELT)
                    reread {
                        seat.getCurrentSeatBeltBuckledState(
                            driverSeat,
                            onComplete = { b -> b?.let { emit { t -> DriverBeltEvent(t, it) } } },
                            onFailed = { readFailed("seatBelt", it) },
                        )
                    }
                    seat.registerSeatBeltBuckledState(driverSeat, buckledListener)
                    cleanups += { seat.unregisterSeatBeltBuckledState(driverSeat, buckledListener) }
                }
            },
            onFailed = { notSupported("seatBelt", it) },
        )

        val safety = v.getSafety()
        val warningListener = object : SeatBeltNotBuckledWarningListener {
            override fun onSeatBeltNotBuckledWarningUpdated(warning: Boolean?) {
                warning?.let { emit { t -> DriverBeltEvent(t, !it) } }
            }
            override fun onFailed(e: Exception) = listenerFailed("seatBeltWarning", e)
        }
        safety.checkSeatNotBuckledWarningCapabilities(
            onComplete = { _, areas ->
                if (areas.isNotEmpty()) {
                    supported(Capability.SEAT_BELT)
                    safety.registerSeatBeltNotBuckledWarning(driverSeat, warningListener)
                    cleanups += { safety.unregisterSeatBeltNotBuckledWarning(driverSeat, warningListener) }
                }
            },
            onFailed = { notSupported("seatBeltWarning", it) },
        )
    }

    private fun wireAirbag(v: Vehicle) {
        val safety = v.getSafety()
        val listener = object : SeatAirbagDeployedListener {
            override fun onSeatAirbagDeployUpdated(deployed: Boolean?) {
                deployed?.let { emit { t -> AirbagEvent(t, it) } }
            }
            override fun onFailed(e: Exception) = listenerFailed("airbag", e)
        }
        safety.checkAirbagDeployedCapabilities(
            onComplete = { _, areas ->
                if (areas.isNotEmpty()) {
                    supported(Capability.AIRBAG)
                    reread {
                        safety.hasSeatAirbagBeenDeployed(
                            driverSeat,
                            onComplete = { d -> d?.let { emit { t -> AirbagEvent(t, it) } } },
                            onFailed = { readFailed("airbag", it) },
                        )
                    }
                    safety.registerSeatAirbagDeployed(driverSeat, listener)
                    cleanups += { safety.unregisterSeatAirbagDeployed(driverSeat, listener) }
                }
            },
            onFailed = { notSupported("airbag", it) },
        )
    }

    private fun wireWiper(v: Vehicle) {
        val wiper = v.getWiper()
        val listener = object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) {
                state?.let { s -> emit { t -> WiperEvent(t, s == WiperState.ON) } }
            }
            override fun onFailed(e: Exception) = listenerFailed("wiper", e)
        }
        wiper.checkWiperStateCapabilities(
            onComplete = { _, _ ->
                supported(Capability.WIPER)
                reread {
                    wiper.getCurrentWiperState(
                        windshield,
                        onComplete = { s -> s?.let { emit { t -> WiperEvent(t, it == WiperState.ON) } } },
                        onFailed = { readFailed("wiper", it) },
                    )
                }
                wiper.registerWiperState(windshield, listener)
                cleanups += { wiper.unregisterWiperState(windshield, listener) }
            },
            onFailed = { notSupported("wiper", it) },
        )
    }

    private fun wireLowBeam(v: Vehicle) {
        val light = v.getLight()
        val listener = object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) {
                state?.let { s -> emit { t -> LowBeamEvent(t, s == LightState.ON) } }
            }
            override fun onFailed(e: Exception) = listenerFailed("lowBeam", e)
        }
        light.checkLowBeamLightStateCapabilities(
            onComplete = { _, _ ->
                supported(Capability.LOW_BEAM)
                reread {
                    light.getCurrentLowBeamLightState(
                        onComplete = { s -> s?.let { emit { t -> LowBeamEvent(t, it == LightState.ON) } } },
                        onFailed = { readFailed("lowBeam", it) },
                    )
                }
                light.registerLowBeamLightState(listener)
                cleanups += { light.unregisterLowBeamLightState(listener) }
            },
            onFailed = { notSupported("lowBeam", it) },
        )
    }

    // ------------------------------------------------------------ 공통

    /** 지금 한 번 읽고, 나중에 다시 읽을 수 있게 기억해 둔다. */
    private fun reread(read: () -> Unit) {
        rereads += read
        runCatching(read)
    }

    /** 등록해 둔 신호의 현재 값을 모두 다시 읽어 내보낸다. */
    fun refreshCurrent() {
        rereads.toList().forEach { runCatching(it) }
    }

    private inline fun guarded(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "$name wiring threw", t)
            post { unsupported += name; publish() }
        }
    }

    private fun emit(make: (Long) -> SignalEvent) {
        post { onEvent?.invoke(make(clock.now())) }
    }

    private fun supported(c: Capability) {
        post {
            if (capabilities.add(c)) publish()
        }
    }

    private fun notSupported(name: String, e: Exception) {
        Log.i(TAG, "$name not available: ${e.javaClass.simpleName}: ${e.message}")
        post { unsupported += name; publish() }
    }

    private fun readFailed(name: String, e: Exception) {
        Log.i(TAG, "$name read failed: ${e.javaClass.simpleName}: ${e.message}")
    }

    private fun listenerFailed(name: String, e: Exception) {
        Log.i(TAG, "$name listener failed: ${e.javaClass.simpleName}: ${e.message}")
    }

    private fun publish() {
        val detail = if (unsupported.isEmpty()) "" else "이 차에서 받지 못하는 신호: ${unsupported.joinToString { koreanName(it) }}"
        onStatus?.invoke(SourceStatus(kind, capabilities.isNotEmpty(), capabilities.toSet(), detail))
    }

    private fun koreanName(key: String): String = when (key) {
        "brake" -> "브레이크"
        "odometer" -> "주행거리"
        "seatBelt" -> "안전띠 체결"
        "seatBeltWarning" -> "안전띠 경고"
        "airbag" -> "에어백"
        "wiper" -> "와이퍼"
        "lowBeam" -> "전조등"
        else -> key
    }

    private companion object {
        const val TAG = "AnbuPleosVehicle"
    }
}
