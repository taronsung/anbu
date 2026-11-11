package app.anbu.car.navi

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.content.Context
import android.util.Log
import app.anbu.car.signals.PlaceProximityEvent
import app.anbu.car.signals.SignalClock
import app.anbu.car.signals.SignalEvent

/**
 * Pleos FusedLocation SDK 연결.
 *  - addProximityAlert: 저장 장소 반경에 들어오고 나가는 것만 알림으로 받는다.
 *  - getLastKnownLocation: "지금 위치로 저장"을 누른 순간에만 한 번 읽는다.
 *
 * 위치를 계속 받아 보는 구독(registerFusedLocationCallback)은 쓰지 않는다.
 * 앱은 "어느 저장 장소에 들어왔는가"만 알면 되기 때문이다.
 */
class ProximityGateway(
    private val context: Context,
    private val clock: SignalClock,
    private val post: (() -> Unit) -> Unit,
    private val onEvent: (SignalEvent) -> Unit,
) {
    private var fused: FusedLocation? = null
    private val alertIds = ArrayList<String>()

    var supported: Boolean = false; private set
    var watching: Int = 0; private set
    var lastError: String? = null; private set

    fun start() {
        try {
            val f = FusedLocation(context.applicationContext)
            supported = f.isSupported()
            if (supported) {
                f.initialize()
                fused = f
            }
        } catch (t: Throwable) {
            Log.w(TAG, "FusedLocation initialize failed", t)
            lastError = t.javaClass.simpleName
            supported = false
        }
    }

    /** 저장 장소마다 반경 [radiusMeters] 알림을 건다. 이전에 건 것은 먼저 푼다. */
    fun watch(places: List<Place>, radiusMeters: Float = 150f) {
        clear()
        val f = fused ?: return
        for (place in places) {
            try {
                f.addProximityAlert(
                    ProximityAlertRequest(
                        latitude = place.latitude,
                        longitude = place.longitude,
                        radiusMeters = radiusMeters,
                        transitionTypes = setOf(ProximityTransition.ENTER, ProximityTransition.EXIT),
                    ),
                    object : ProximityAlertListener {
                        override fun onRegistered(alertId: String) {
                            Log.i(TAG, "proximity alert registered for ${place.id}")
                            post { alertIds += alertId; watching = alertIds.size }
                        }

                        override fun onTransition(event: ProximityAlertEvent) {
                            val entered = event.transition == ProximityTransition.ENTER
                            Log.i(TAG, "proximity ${event.transition} ${place.id}")
                            post { onEvent(PlaceProximityEvent(clock.now(), place.id, entered)) }
                        }

                        override fun onError(exception: Exception) {
                            post { lastError = exception.javaClass.simpleName }
                        }
                    },
                )
            } catch (t: Throwable) {
                Log.w(TAG, "addProximityAlert failed", t)
                lastError = t.javaClass.simpleName
            }
        }
    }

    fun clear() {
        val f = fused
        if (f != null) alertIds.forEach { id -> runCatching { f.removeProximityAlert(id) } }
        alertIds.clear()
        watching = 0
    }

    /** 현재 위치를 한 번 읽는다. 결과는 차 안 저장소의 장소 좌표로만 쓴다. */
    fun readCurrentLocation(onResult: (latitude: Double, longitude: Double) -> Unit, onFail: () -> Unit) {
        val f = fused
        if (f == null) {
            onFail()
            return
        }
        try {
            f.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    post { if (location != null) onResult(location.latitude, location.longitude) else onFail() }
                }

                override fun onError(exception: Exception) {
                    post { lastError = exception.javaClass.simpleName; onFail() }
                }
            })
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName
            onFail()
        }
    }

    fun stop() {
        clear()
        runCatching { fused?.release() }
        fused = null
    }

    private companion object {
        const val TAG = "AnbuProximity"
    }
}
