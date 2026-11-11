package app.anbu.car.signals

import app.anbu.car.navi.Places

/**
 * 시연과 테스트용으로 정해 둔 주행 대본.
 *
 * 실제 차나 에뮬레이터 신호가 없을 때 앱 전체 흐름을 보여 주기 위한 것이다.
 * 대본이 내보내는 값은 측정값이 아니며, 화면에는 "시뮬레이션" 표시가 붙는다.
 */
object DemoScript {

    /**
     * 병원까지 약 2분 반 주행. 비가 잠깐 오고, 어린이 보호구역을 지나고,
     * 급하게 한 번 멈추고, 길을 한 번 다시 찾은 뒤 병원에 도착해 P에 둔다.
     */
    fun hospitalTrip(startMs: Long, destinationId: String = Places.HOSPITAL): List<SignalEvent> {
        val ev = ArrayList<SignalEvent>()
        fun at(sec: Double) = startMs + (sec * 1000).toLong()

        ev += GearEvent(at(0.0), Gear.PARK)
        ev += SpeedEvent(at(0.0), 0f)
        ev += BrakeEvent(at(0.0), true)
        ev += DriverBeltEvent(at(0.0), false)
        ev += LowBeamEvent(at(0.0), false)
        ev += WiperEvent(at(0.0), false)
        ev += OdometerEvent(at(0.0), 15_230.0f)

        ev += DriverBeltEvent(at(2.0), true)
        ev += GearEvent(at(4.0), Gear.DRIVE)
        ev += BrakeEvent(at(4.5), false)
        ev += NaviEtaEvent(at(6.0), remainSeconds = 9 * 60, remainMeters = 4_200)

        // 속도 곡선: (시각 초, 속도 m/s). 사이 구간은 직선으로 잇는다.
        val curve = listOf(
            5.0 to 0.0, 20.0 to 13.9, 40.0 to 13.9, 46.0 to 8.3, 60.0 to 8.3, 66.0 to 13.9,
            75.0 to 13.9, 77.0 to 3.0, 80.0 to 3.0, 90.0 to 13.9, 130.0 to 13.9, 140.0 to 0.0,
        )
        var odo = 15_230.0
        var prevV = 0.0
        var t = 5.0
        while (t <= 140.0) {
            val v = speedAt(curve, t)
            odo += (prevV + v) / 2.0 * 0.5 / 1000.0
            ev += SpeedEvent(at(t), v.toFloat())
            if ((t * 2).toInt() % 20 == 0) ev += OdometerEvent(at(t), odo.toFloat())
            prevV = v
            t += 0.5
        }

        ev += WiperEvent(at(30.0), true)
        ev += NaviCautionEvent(at(40.0), CautionKind.SCHOOL_ZONE)
        ev += BrakeEvent(at(40.0), true)
        ev += BrakeEvent(at(46.0), false)
        ev += WiperEvent(at(70.0), false)
        ev += BrakeEvent(at(74.9), true)   // 급하게 멈춤
        ev += BrakeEvent(at(80.0), false)
        ev += NaviRerouteEvent(at(95.0))
        ev += NaviEtaEvent(at(96.0), remainSeconds = 60, remainMeters = 600)
        ev += BrakeEvent(at(130.0), true)
        ev += OdometerEvent(at(140.5), odo.toFloat())
        ev += NaviArrivedEvent(at(141.0), destinationId)
        ev += GearEvent(at(143.0), Gear.PARK)
        // P를 20초 유지하면 도착으로 본다. 그 뒤까지 대본 시간을 둔다.
        ev += SpeedEvent(at(166.0), 0f)
        return ev.sortedBy { it.atMs }
    }

    /** 급하게 멈춘 뒤 D에 둔 채 1분 넘게 서 있는 상황. "괜찮으세요?" 확인을 부른다. */
    fun hardStop(startMs: Long): List<SignalEvent> {
        val ev = ArrayList<SignalEvent>()
        fun at(sec: Double) = startMs + (sec * 1000).toLong()
        ev += GearEvent(at(0.0), Gear.DRIVE)
        ev += DriverBeltEvent(at(0.0), true)
        ev += BrakeEvent(at(0.0), false)
        var t = 0.0
        while (t <= 6.0) { ev += SpeedEvent(at(t), 13.9f); t += 0.5 }
        ev += BrakeEvent(at(6.2), true)
        ev += SpeedEvent(at(6.5), 11.0f)
        ev += SpeedEvent(at(7.0), 7.5f)
        ev += SpeedEvent(at(7.5), 4.0f)
        ev += SpeedEvent(at(8.0), 1.0f)
        ev += SpeedEvent(at(8.5), 0f)
        t = 9.0
        while (t <= 75.0) { ev += SpeedEvent(at(t), 0f); t += 3.0 }
        // 대본 끝: 차를 P에 둔다.
        ev += GearEvent(at(78.0), Gear.PARK)
        return ev.sortedBy { it.atMs }
    }

    private fun speedAt(curve: List<Pair<Double, Double>>, t: Double): Double {
        if (t <= curve.first().first) return curve.first().second
        for (i in 1 until curve.size) {
            val (t1, v1) = curve[i]
            val (t0, v0) = curve[i - 1]
            if (t <= t1) return v0 + (v1 - v0) * (t - t0) / (t1 - t0)
        }
        return curve.last().second
    }
}

/**
 * 신호 시계. 평소에는 벽시계와 같이 가고, 대본을 재생하는 동안만 빨리 간다.
 *
 * 모든 소스가 이 시계로 시각을 찍으므로, 시뮬레이션이 끝난 뒤 실제 신호가 들어와도
 * 시각이 거꾸로 가지 않는다. 가족에게 가는 도착 시각에는 쓰지 않는다(그건 벽시계다).
 */
class SignalClock(private val wallClock: () -> Long) {
    private var leadMs = 0L
    private var fastSinceWall = 0L
    private var fastSpeed = 1.0

    fun now(): Long {
        val wall = wallClock()
        val extra = if (fastSinceWall != 0L) ((wall - fastSinceWall) * (fastSpeed - 1.0)).toLong() else 0L
        return wall + leadMs + extra
    }

    fun runFast(speed: Double) {
        settle()
        fastSinceWall = wallClock()
        fastSpeed = speed
    }

    /** 빨리 가기를 멈춘다. 그동안 앞서간 만큼은 그대로 둔다. */
    fun settle() {
        if (fastSinceWall != 0L) {
            leadMs += ((wallClock() - fastSinceWall) * (fastSpeed - 1.0)).toLong()
            fastSinceWall = 0L
            fastSpeed = 1.0
        }
    }
}

/**
 * 대본을 신호 시계에 맞춰 재생한다.
 *
 * 스레드를 만들지 않는다. 호출하는 쪽이 [pump]를 주기적으로 부르면,
 * 그때까지 시계가 지나온 이벤트를 한꺼번에 내보낸다.
 * [speed]가 4이면 대본의 20초가 실제 5초에 지나간다.
 */
class SimulatedSource(
    private val clock: SignalClock,
    var speed: Double = 4.0,
) : VehicleSignalSource {

    override val kind = SourceKind.SIMULATED

    private var onEvent: ((SignalEvent) -> Unit)? = null
    private var queue: ArrayDeque<SignalEvent> = ArrayDeque()
    /** 대본의 마지막 이벤트 뒤에도 잠시 빨리 가게 둔다(P 유지 시간을 채우기 위해). */
    private var fastUntilMs = 0L

    val playing: Boolean get() = queue.isNotEmpty() || fastUntilMs != 0L

    override fun start(onEvent: (SignalEvent) -> Unit, onStatus: (SourceStatus) -> Unit) {
        this.onEvent = onEvent
        onStatus(SourceStatus(SourceKind.SIMULATED, true, Capability.values().toSet(), "정해 둔 주행 대본"))
    }

    override fun stop() {
        cancelPlayback()
        onEvent = null
    }

    /** 대본 재생을 시작한다. [build]는 시작 시각을 받아 이벤트 목록을 만든다. */
    fun play(build: (Long) -> List<SignalEvent>) {
        clock.settle()
        val events = build(clock.now())
        queue = ArrayDeque(events)
        fastUntilMs = (events.lastOrNull()?.atMs ?: clock.now()) + 2_000
        clock.runFast(speed)
    }

    fun cancelPlayback() {
        queue.clear()
        fastUntilMs = 0L
        clock.settle()
    }

    fun pump() {
        val now = clock.now()
        val sink = onEvent
        while (sink != null && queue.isNotEmpty() && queue.first().atMs <= now) sink(queue.removeFirst())
        if (queue.isEmpty() && fastUntilMs != 0L && now >= fastUntilMs) {
            fastUntilMs = 0L
            clock.settle()
        }
    }
}
