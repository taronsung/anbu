package app.anbu.car.signals

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * AAOS 표준 Car API로 속도와 기어를 받는다.
 *
 * Pleos Vehicle SDK에는 차속 도메인이 없고, 문서가 속도·기어는 표준 데이터
 * (CarPropertyManager의 PERF_VEHICLE_SPEED, GEAR_SELECTION)를 쓰라고 안내한다.
 *  - 기어: android.car.permission.CAR_POWERTRAIN (일반 권한)
 *  - 속도: android.car.permission.CAR_SPEED (실행 중 허용이 필요한 권한)
 */
class AaosCarSource(
    private val context: Context,
    private val clock: SignalClock,
    private val post: (() -> Unit) -> Unit,
) : VehicleSignalSource {

    override val kind = SourceKind.AAOS

    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var onEvent: ((SignalEvent) -> Unit)? = null
    private var onStatus: ((SourceStatus) -> Unit)? = null
    private val capabilities = LinkedHashSet<Capability>()
    private var speedRegistered = false
    private var gearRegistered = false

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> (value.value as? Float)?.let { v ->
                    // 후진은 음수로 오므로 크기만 쓴다.
                    emit { t -> SpeedEvent(t, kotlin.math.abs(v)) }
                }
                VehiclePropertyIds.GEAR_SELECTION -> (value.value as? Int)?.let { g ->
                    emit { t -> GearEvent(t, mapGear(g)) }
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.i(TAG, "property error: $propertyId")
        }
    }

    override fun start(onEvent: (SignalEvent) -> Unit, onStatus: (SourceStatus) -> Unit) {
        this.onEvent = onEvent
        this.onStatus = onStatus
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            onStatus(SourceStatus(kind, false, detail = "자동차용 안드로이드가 아닙니다"))
            return
        }
        try {
            val c = Car.createCar(context.applicationContext)
            car = c
            properties = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        } catch (t: Throwable) {
            Log.w(TAG, "Car API connect failed", t)
            onStatus(SourceStatus(kind, false, detail = "Car API 연결 실패: ${t.javaClass.simpleName}"))
            return
        }
        registerAvailable()
    }

    /** 속도 권한을 나중에 허용받았을 때 다시 부른다. */
    fun registerAvailable() {
        val pm = properties ?: return
        if (!gearRegistered) {
            gearRegistered = tryRegister(pm, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            if (gearRegistered) capabilities += Capability.GEAR
        }
        if (!speedRegistered && hasSpeedPermission()) {
            speedRegistered = tryRegister(pm, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_UI)
            if (speedRegistered) capabilities += Capability.SPEED
        }
        val detail = if (!speedRegistered) "속도 권한(CAR_SPEED)이 아직 없습니다" else ""
        onStatus?.invoke(SourceStatus(kind, capabilities.isNotEmpty(), capabilities.toSet(), detail))
    }

    /** 기어와 속도의 현재 값을 다시 읽어 내보낸다. 시뮬레이션이 끝난 뒤 실제 상태로 되돌릴 때 쓴다. */
    fun refreshCurrent() {
        val pm = properties ?: return
        if (gearRegistered) runCatching {
            val g = pm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0)
            emit { t -> GearEvent(t, mapGear(g)) }
        }
        if (speedRegistered) runCatching {
            val v = pm.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
            emit { t -> SpeedEvent(t, kotlin.math.abs(v)) }
        }
    }

    fun hasSpeedPermission(): Boolean =
        context.checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED

    override fun stop() {
        runCatching { properties?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
        car = null
        properties = null
        onEvent = null
        onStatus = null
        speedRegistered = false
        gearRegistered = false
    }

    private fun tryRegister(pm: CarPropertyManager, propertyId: Int, rateHz: Float): Boolean = try {
        pm.getCarPropertyConfig(propertyId) != null && pm.registerCallback(callback, propertyId, rateHz)
    } catch (t: Throwable) {
        Log.i(TAG, "register $propertyId failed: ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    private fun emit(make: (Long) -> SignalEvent) {
        post { onEvent?.invoke(make(clock.now())) }
    }

    private fun mapGear(g: Int): Gear = when (g) {
        GEAR_PARK -> Gear.PARK
        GEAR_REVERSE -> Gear.REVERSE
        GEAR_NEUTRAL -> Gear.NEUTRAL
        GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE
    }

    private companion object {
        const val TAG = "AnbuAaosCar"
        // android.car.VehicleGear 값
        const val GEAR_UNKNOWN = 0x0000
        const val GEAR_NEUTRAL = 0x0001
        const val GEAR_REVERSE = 0x0002
        const val GEAR_PARK = 0x0004
    }
}

/**
 * 주행 규제 상태(CarUxRestrictionsManager).
 * 규제가 걸리면 앱은 글과 버튼을 최소로 줄인 주행 화면만 보여 준다.
 */
class DrivingRestrictions(
    private val context: Context,
    private val post: (() -> Unit) -> Unit,
) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    var requiresDistractionOptimization: Boolean = false; private set
    var activeRestrictions: Int = 0; private set
    var connected: Boolean = false; private set

    fun start(onChanged: () -> Unit) {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        try {
            val c = Car.createCar(context.applicationContext)
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
            manager = m
            connected = true
            apply(m.currentCarUxRestrictions)
            m.registerListener { r -> post { apply(r); onChanged() } }
            onChanged()
        } catch (t: Throwable) {
            Log.w("AnbuUxRestrictions", "connect failed", t)
        }
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null
        car = null
        connected = false
    }

    private fun apply(r: CarUxRestrictions?) {
        requiresDistractionOptimization = r?.isRequiresDistractionOptimization ?: false
        activeRestrictions = r?.activeRestrictions ?: 0
    }
}
