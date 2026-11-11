package app.anbu.car.signals

/** 기어 위치. 차종마다 값이 달라 네 가지로만 줄여서 쓴다. */
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 내비게이션이 알려 주는 주의 구간 가운데 일기에 세는 것. */
enum class CautionKind { SCHOOL_ZONE, WRONG_WAY, OTHER }

/**
 * 차에서 올라오는 신호 한 건.
 *
 * 모든 소스(Pleos Vehicle SDK, AAOS Car API, 시뮬레이션)가 같은 형태로 내보낸다.
 * [atMs]는 신호 시계 기준 시각이다. 시뮬레이션은 가상 시계를 쓰므로
 * 주행 계산은 벽시계가 아니라 이 값만 본다.
 */
sealed interface SignalEvent {
    val atMs: Long
}

data class SpeedEvent(override val atMs: Long, val mps: Float) : SignalEvent
data class GearEvent(override val atMs: Long, val gear: Gear) : SignalEvent
data class BrakeEvent(override val atMs: Long, val pressed: Boolean) : SignalEvent
data class OdometerEvent(override val atMs: Long, val km: Float) : SignalEvent
data class DriverBeltEvent(override val atMs: Long, val buckled: Boolean) : SignalEvent
data class WiperEvent(override val atMs: Long, val on: Boolean) : SignalEvent
data class LowBeamEvent(override val atMs: Long, val on: Boolean) : SignalEvent
data class AirbagEvent(override val atMs: Long, val deployed: Boolean) : SignalEvent

/** 내비게이션이 목적지 도착을 알렸다. [placeId]는 앱이 길안내를 요청한 저장 장소다. */
data class NaviArrivedEvent(override val atMs: Long, val placeId: String?) : SignalEvent

/** 남은 시간과 거리. 좌표는 담지 않는다. */
data class NaviEtaEvent(
    override val atMs: Long,
    val remainSeconds: Int,
    val remainMeters: Int,
) : SignalEvent

data class NaviCautionEvent(override val atMs: Long, val kind: CautionKind) : SignalEvent
data class NaviRerouteEvent(override val atMs: Long) : SignalEvent

/** 저장 장소 반경에 들어오거나 나갔다. 어느 장소인지만 전달한다. */
data class PlaceProximityEvent(
    override val atMs: Long,
    val placeId: String,
    val entered: Boolean,
) : SignalEvent
