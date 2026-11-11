package app.anbu.car.navi

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import app.anbu.car.signals.CautionKind
import app.anbu.car.signals.NaviArrivedEvent
import app.anbu.car.signals.NaviCautionEvent
import app.anbu.car.signals.NaviEtaEvent
import app.anbu.car.signals.NaviRerouteEvent
import app.anbu.car.signals.SignalClock
import app.anbu.car.signals.SignalEvent

/**
 * Pleos NaviHelper SDK 연결.
 *  - 자주 가는 곳 버튼 → requestRoute
 *  - onDestinationArrived → 도착 판정
 *  - onTripInfo → 도착 예정 시각
 *  - onCautionPointInfo → 어린이 보호구역, 역주행 주의 알림 횟수
 *  - onReRouteEnded → 경로 재탐색 횟수
 */
class NaviGateway(
    private val context: Context,
    private val clock: SignalClock,
    private val post: (() -> Unit) -> Unit,
    private val onEvent: (SignalEvent) -> Unit,
    private val onChanged: () -> Unit,
    /** 내비게이션 쪽에서 안내가 끝났거나 취소됐다. */
    private val onRouteDropped: () -> Unit,
) {
    private var helper: NaviHelper? = null

    var connected: Boolean = false; private set
    var lastError: String? = null; private set
    var speedLimitKmh: Int? = null; private set
    /** 길안내를 요청했고 아직 시작 응답을 받지 못한 장소. */
    private var pending: Place? = null
    /** 지금 안내 중인 장소. */
    private var active: Place? = null
    private val seenCautionIds = LinkedHashSet<String>()

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            // 에뮬레이터에서 확인한 단위: duration은 초, distance는 미터.
            Log.i(TAG, "onRouteStarted duration=${routeStartInfo.duration} distance=${routeStartInfo.distance}")
            post {
                pending?.let { active = it }
                pending = null
                onEvent(NaviEtaEvent(clock.now(), routeStartInfo.duration, routeStartInfo.distance))
                onChanged()
            }
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            Log.i(TAG, "onDestinationArrived duration=${destinationArrivedInfo.duration} distance=${destinationArrivedInfo.distance}")
            post {
                onEvent(NaviArrivedEvent(clock.now(), active?.id))
                active = null
            }
        }

        override fun onTripInfo(tripInfo: TripInfo) {
            // eta는 남은 시간(초), distance는 남은 거리(m)로 다룬다.
            Log.i(TAG, "onTripInfo eta=${tripInfo.eta} distance=${tripInfo.distance}")
            post { onEvent(NaviEtaEvent(clock.now(), tripInfo.eta, tripInfo.distance)) }
        }

        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            Log.i(TAG, "onCautionPointInfo " + cautionPointList.joinToString { "${it.type}@${it.remainDistance}m" })
            post {
                for (p in cautionPointList) {
                    // 같은 지점이 다가오는 동안 여러 번 오므로 처음 한 번만 센다.
                    if (!seenCautionIds.add(p.id)) continue
                    val kind = when (p.type) {
                        CautionPointType.SCHOOL_ZONE -> CautionKind.SCHOOL_ZONE
                        CautionPointType.WRONG_WAY_DRIVING_ALERT -> CautionKind.WRONG_WAY
                        else -> CautionKind.OTHER
                    }
                    onEvent(NaviCautionEvent(clock.now(), kind))
                }
                while (seenCautionIds.size > 300) seenCautionIds.remove(seenCautionIds.first())
            }
        }

        override fun onReRouteEnded() {
            Log.i(TAG, "onReRouteEnded")
            post { onEvent(NaviRerouteEvent(clock.now())) }
        }

        override fun onRouteCancelled() {
            Log.i(TAG, "onRouteCancelled")
            post {
                // 새 목적지를 요청하면 이전 안내가 취소되면서 이 콜백이 먼저 온다. 그 경우는 넘긴다.
                if (pending == null) {
                    active = null
                    onRouteDropped()
                }
                onChanged()
            }
        }

        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            post { speedLimitKmh = speedLimit }
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.i(TAG, "onError $errorCode")
            post { lastError = errorCode.name; onChanged() }
        }
    }

    fun start() {
        try {
            val h = NaviHelper(context.applicationContext)
            helper = h
            h.initialize(
                onConnected = {
                    post {
                        connected = true
                        runCatching { h.addListener(listener) }
                        onChanged()
                    }
                },
                onFailed = { t ->
                    post {
                        connected = false
                        lastError = t.javaClass.simpleName
                        onChanged()
                    }
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "NaviHelper initialize failed", t)
            lastError = t.javaClass.simpleName
        }
    }

    /** @return 내비게이션 앱에 길안내를 요청했으면 true */
    fun requestRoute(place: Place): Boolean {
        val h = helper
        if (h == null || !connected) return false
        return try {
            h.requestRoute(
                RouteInfo(
                    latitude = place.latitude,
                    longitude = place.longitude,
                    poiId = "",
                    poiName = place.poiName.ifEmpty { place.label },
                    poiSubId = "0",
                    address = place.address,
                )
            )
            pending = place
            true
        } catch (t: Throwable) {
            Log.w(TAG, "requestRoute failed", t)
            lastError = t.javaClass.simpleName
            false
        }
    }

    fun stop() {
        runCatching { helper?.removeListener(listener) }
        runCatching { helper?.release() }
        helper = null
        connected = false
    }

    private companion object {
        const val TAG = "AnbuNavi"
    }
}
