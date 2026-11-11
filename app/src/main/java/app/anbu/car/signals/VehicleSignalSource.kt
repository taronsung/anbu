package app.anbu.car.signals

enum class SourceKind { PLEOS, AAOS, SIMULATED }

/** 소스가 실제로 받을 수 있다고 확인한 신호 묶음. 화면과 README의 "실제/시뮬레이션" 구분에 쓴다. */
enum class Capability {
    SPEED, GEAR, BRAKE, ODOMETER, SEAT_BELT, WIPER, LOW_BEAM, AIRBAG
}

data class SourceStatus(
    val kind: SourceKind,
    val available: Boolean,
    val capabilities: Set<Capability> = emptySet(),
    val detail: String = "",
)

/**
 * 차량 신호 공급원.
 *
 * 구현은 세 가지다.
 *  - PleosVehicleSource: Pleos Vehicle SDK 리스너
 *  - AaosCarSource: android.car의 속도·기어
 *  - SimulatedSource: 시연과 테스트용으로 정해 둔 주행을 재생
 *
 * 이벤트 시각은 모두 같은 SignalClock으로 찍는다.
 */
interface VehicleSignalSource {
    val kind: SourceKind

    fun start(onEvent: (SignalEvent) -> Unit, onStatus: (SourceStatus) -> Unit)

    fun stop()
}
