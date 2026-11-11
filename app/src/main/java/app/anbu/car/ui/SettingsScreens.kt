package app.anbu.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.anbu.car.AppController
import app.anbu.car.BuildConfig
import app.anbu.car.Screen
import app.anbu.car.UiState
import app.anbu.car.signals.Capability
import app.anbu.car.signals.Gear
import app.anbu.car.signals.SourceStatus
import kotlin.math.roundToInt

// ---------------------------------------------------------------- 공유 설정

@Composable
fun SettingsScreen(ui: UiState, controller: AppController) {
    val wide = LocalWide.current
    val c = LocalAnbuColors.current
    val s = ui.consent

    val toggles: @Composable () -> Unit = {
        ToggleRow(
            "도착·출발 안부 보내기", "도착 시각과 장소 이름만 보냅니다",
            s.shareArrival, { v -> controller.updateConsent { it.copy(shareArrival = v) } },
        )
        ToggleRow(
            "이름 없는 곳도 알리기", "끄면 집, 병원처럼 이름을 붙인 곳에 도착했을 때만 알립니다",
            s.shareUnnamedArrival, { v -> controller.updateConsent { it.copy(shareUnnamedArrival = v) } },
        )
        ToggleRow(
            "가족의 답장 받기", "정해 둔 답장 네 가지만 받고, 차가 서 있을 때 읽어 드립니다",
            s.receiveReplies, { v -> controller.updateConsent { it.copy(receiveReplies = v) } },
        )
        ToggleRow(
            "평소보다 오래 걸렸는지도 알리기", "도착 안부에 한 줄을 붙입니다",
            s.shareTripDuration, { v -> controller.updateConsent { it.copy(shareTripDuration = v) } },
        )
        ToggleRow(
            "운전 일기를 나눌 때 수치도 보내기", "끄면 단계와 한 줄만 갑니다",
            s.shareWeeklyDetails, { v -> controller.updateConsent { it.copy(shareWeeklyDetails = v) } },
        )
        ToggleRow(
            "저장한 장소 도착 알아채기", "좌표는 차 밖으로 나가지 않습니다",
            s.useProximity, { v -> controller.updateConsent { it.copy(useProximity = v) } },
        )
        ToggleRow(
            "괜찮으세요 확인", "30초 동안 답이 없으면 가족에게 시각을 알립니다",
            s.checkInAlert, { v -> controller.updateConsent { it.copy(checkInAlert = v) } },
        )
        ToggleRow(
            "알림에 떠나온 곳 이름 붙이기", "좌표가 아니라 \"병원을 떠난 뒤\" 같은 글입니다",
            s.checkInPlaceLabel, { v -> controller.updateConsent { it.copy(checkInPlaceLabel = v) } },
        )
    }
    val side: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(c.card)
                .padding(horizontal = 28.dp, vertical = 20.dp)
        ) {
            Column {
                T("가족 초대 코드", AnbuType.caption, color = c.inkSoft)
                T(s.familyCode.chunked(3).joinToString(" "), AnbuType.title)
                T("가족에게는 \"${s.who}\"로 보입니다", AnbuType.caption, color = c.inkSoft)
            }
        }
        Spacer(Modifier.height(18.dp))
        if (ui.paused) {
            BigButton("다시 시작", { controller.resume() }, Modifier.fillMaxWidth(), sub = "지금은 쉬는 중이에요")
        } else {
            BigButton(
                "오늘은 쉬기", { controller.pauseToday() }, Modifier.fillMaxWidth(),
                sub = "자정까지 기록도 전송도 하지 않아요",
            )
            Spacer(Modifier.height(16.dp))
            BigButton(
                "이번 운전만 쉬기", { controller.pauseThisTrip() }, Modifier.fillMaxWidth(),
                kind = ButtonKind.SECONDARY, sub = "다른 사람이 운전할 때. 주차하면 다시 시작해요",
            )
        }
        Spacer(Modifier.height(16.dp))
        BigButton("자주 가는 곳 위치", { controller.go(Screen.PLACES) }, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, minHeight = 88.dp)
        Spacer(Modifier.height(16.dp))
        BigButton("연결 상태와 시연", { controller.go(Screen.STATUS) }, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, minHeight = 88.dp)
        Spacer(Modifier.height(16.dp))
        BigButton("데이터 삭제", { controller.go(Screen.DELETE) }, Modifier.fillMaxWidth(), kind = ButtonKind.DANGER, minHeight = 88.dp)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("공유 설정", screenTitle(), modifier = Modifier.weight(1f))
            BigButton("돌아가기", { controller.go(Screen.HOME) }, kind = ButtonKind.SECONDARY, minHeight = 84.dp)
        }
        Spacer(Modifier.height(20.dp))
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.4f).fillMaxHeight().verticalScroll(rememberScrollState())) { side() }
                Spacer(Modifier.width(36.dp))
                Column(
                    Modifier.weight(0.6f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    toggles()
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column { side() }
                Spacer(Modifier.height(8.dp))
                toggles()
                Spacer(Modifier.height(120.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- 자주 가는 곳 위치

/**
 * 자주 가는 곳의 위치를 지금 있는 곳으로 바꾼다(FusedLocation getLastKnownLocation).
 * 좌표는 화면에 보여 주지 않고 차 안 저장소에만 둔다.
 */
@Composable
fun PlacesScreen(ui: UiState, controller: AppController) {
    val c = LocalAnbuColors.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("자주 가는 곳 위치", screenTitle(), modifier = Modifier.weight(1f), maxLines = 1)
            BigButton("돌아가기", { controller.go(Screen.SETTINGS) }, kind = ButtonKind.SECONDARY, minHeight = 84.dp)
        }
        T(
            "그 장소에 차를 세운 뒤 누르면 지금 있는 곳이 그 장소의 위치가 됩니다. 위치는 차 안에만 저장하고 가족에게는 장소 이름만 갑니다.",
            AnbuType.body, color = c.inkSoft,
        )
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            for (place in ui.places) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(c.card)
                        .padding(horizontal = 26.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaceGlyph(place.id, c.ink, size = 56.dp)
                    Gap(20.dp)
                    Column(Modifier.weight(1f)) {
                        T(place.label, AnbuType.headline)
                        T(
                            if (place.demoCoordinate) "처음 넣어 둔 시연용 위치입니다" else "내가 저장한 위치입니다",
                            AnbuType.caption, color = c.inkSoft,
                        )
                    }
                    BigButton(
                        "지금 있는 곳으로", { controller.savePlaceHere(place) },
                        kind = ButtonKind.SECONDARY, minHeight = 88.dp,
                        enabled = ui.status.proximitySupported && !ui.missingLocationPermission,
                    )
                }
            }
            if (!ui.status.proximitySupported || ui.missingLocationPermission) {
                T(
                    "위치를 읽으려면 공유 설정에서 \"저장한 장소 도착 알아채기\"를 켜고 허용해 주세요.",
                    AnbuType.body, color = c.inkSoft,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 데이터 삭제

@Composable
fun DeleteScreen(ui: UiState, controller: AppController) {
    val c = LocalAnbuColors.current
    val gone = listOf(
        "차 안에 있는 주행 요약 ${ui.status.tripsStored}건",
        "보낸 안부 기록과 아직 못 보낸 안부",
        "저장한 장소의 좌표",
        "동의 기록과 가족 초대 코드",
        "가족 화면에 있는 안부(릴레이 서버에도 삭제를 요청합니다)",
    )
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            T("모두 지울까요?", AnbuType.title)
            T("한 번 누르면 바로 지우고, 되돌릴 수 없습니다.", AnbuType.body, color = c.inkSoft)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(vertical = 20.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(c.card)
                .padding(horizontal = 30.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            gone.forEachIndexed { i, line ->
                if (i > 0) Rule()
                T(line, AnbuType.body, modifier = Modifier.padding(vertical = 14.dp))
            }
        }
        Column {
            T(
                "암호화 키도 함께 없애므로, 남은 파일 조각이 있어도 다시 읽을 수 없습니다. 지운 뒤에는 처음 화면으로 돌아갑니다.",
                AnbuType.caption, color = c.inkSoft,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BigButton("돌아가기", { controller.go(Screen.SETTINGS) }, Modifier.weight(0.45f), kind = ButtonKind.SECONDARY, minHeight = 130.dp)
                BigButton("모두 지우기", { controller.deleteEverything() }, Modifier.weight(0.55f), kind = ButtonKind.DANGER, minHeight = 130.dp)
            }
        }
    }
}

// ---------------------------------------------------------------- 연결 상태와 시연

/**
 * 지금 무엇이 실제로 연결되어 있고 무엇이 시뮬레이션인지 그대로 보여 준다.
 * 심사나 시연 자리에서 차량 신호가 앱까지 들어오는지 확인하는 용도다.
 */
@Composable
fun StatusScreen(ui: UiState, controller: AppController) {
    val wide = LocalWide.current
    val st = ui.status
    val v = ui.vehicle

    val connections: @Composable () -> Unit = {
        Panel("연결") {
            Line("Vehicle SDK ${BuildConfig.PLEOS_VEHICLE_VERSION}", describe(st.pleos))
            Line("AAOS Car API", describe(st.aaos))
            Line(
                "NaviHelper ${BuildConfig.PLEOS_NAVI_VERSION}",
                if (st.naviConnected) "연결됨" else "연결 안 됨" + (st.naviError?.let { " ($it)" } ?: ""),
            )
            Line(
                "FusedLocation ${BuildConfig.PLEOS_FUSED_VERSION}",
                when {
                    !st.proximitySupported -> "지원 안 됨"
                    ui.missingLocationPermission -> "지원됨, 위치 허용을 아직 받지 않음"
                    else -> "지원됨, 장소 알림 ${st.proximityWatching}개" + (st.proximityError?.let { " ($it)" } ?: "")
                },
            )
            Line("말하기", st.ttsBackend)
            Line("듣기", st.sttBackend)
            Line("주행 규제", if (st.uxRestrictionsConnected) (if (ui.restricted) "연결됨, 규제 중" else "연결됨, 규제 없음") else "연결 안 됨")
            Line(
                "릴레이", st.relayUrl + when (st.lastRelayOk) {
                    true -> " (전송 성공)"
                    false -> " (전송 실패, 다시 시도 중)"
                    null -> ""
                } + if (st.outboxPending > 0) ", 대기 ${st.outboxPending}건" else "",
            )
        }
    }
    val signals: @Composable () -> Unit = {
        Panel(if (st.simulationPlaying) "지금 신호 (대본 재생 중)" else "지금 신호") {
            Line(
                "기어", when (v.gear) {
                    Gear.PARK -> "P"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.DRIVE -> "D"; Gear.UNKNOWN -> "모름"
                },
            )
            Line("속도", v.speedMps?.let { "시속 ${(it * 3.6).roundToInt()}km" } ?: "모름")
            Line("브레이크", onOff(v.brakePressed, "밟음", "뗌"))
            Line("안전띠", onOff(v.beltBuckled, "맴", "안 맴"))
            Line("와이퍼", onOff(v.wiperOn, "켜짐", "꺼짐"))
            Line("전조등", onOff(v.lowBeamOn, "켜짐", "꺼짐"))
            Line("주행거리계", v.odometerKm?.let { "${"%.1f".format(it)}km" } ?: "모름")
            Line("받은 신호 수", "${v.signalCount}건, 저장된 주행 ${st.tripsStored}건")
        }
    }
    val demo: @Composable () -> Unit = {
        Panel("시연") {
            T(
                "정해 둔 대본을 재생하거나 예시 기록을 넣습니다. 실제 차량 신호가 아니며 화면에 표시가 붙습니다.",
                AnbuType.caption, color = LocalAnbuColors.current.inkSoft,
            )
            Spacer(Modifier.height(10.dp))
            if (ui.limited) {
                T("동의한 뒤에 쓸 수 있어요.", AnbuType.body)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BigButton("병원 가는 길 재생", { controller.playDemoTrip() }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, minHeight = 84.dp)
                    BigButton("급정지 상황 재생", { controller.playDemoHardStop() }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, minHeight = 96.dp)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BigButton("예시 기록 넣기", { controller.seedExampleHistory() }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, minHeight = 96.dp)
                    BigButton("대본 멈추기", { controller.stopDemo() }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, minHeight = 96.dp, enabled = st.simulationPlaying)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("연결 상태와 시연", screenTitle(), maxLines = 1, modifier = Modifier.weight(1f))
            if (ui.simulated) {
                Tag("시뮬레이션")
                Gap(16.dp)
            }
            BigButton(
                "돌아가기", { controller.go(if (ui.limited) Screen.HOME else Screen.SETTINGS) },
                kind = ButtonKind.SECONDARY, minHeight = 84.dp,
            )
        }
        Spacer(Modifier.height(18.dp))
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(0.56f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    connections()
                    demo()
                }
                Spacer(Modifier.width(30.dp))
                Column(
                    Modifier.weight(0.44f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    signals()
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                connections()
                signals()
                demo()
                Spacer(Modifier.height(120.dp))
            }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    val c = LocalAnbuColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(c.card)
            .padding(horizontal = 28.dp, vertical = 16.dp)
    ) {
        T(title, AnbuType.headline)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun Line(name: String, value: String) {
    val c = LocalAnbuColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        T(name, AnbuType.caption, color = c.inkSoft, modifier = Modifier.width(250.dp))
        T(value, AnbuType.caption, modifier = Modifier.weight(1f))
    }
}

private fun onOff(value: Boolean?, on: String, off: String): String = when (value) {
    true -> on
    false -> off
    null -> "모름"
}

private fun describe(s: SourceStatus): String {
    if (!s.available) return "신호 없음" + if (s.detail.isNotEmpty()) " (${s.detail})" else ""
    val names = s.capabilities.joinToString(", ") {
        when (it) {
            Capability.SPEED -> "속도"
            Capability.GEAR -> "기어"
            Capability.BRAKE -> "브레이크"
            Capability.ODOMETER -> "주행거리"
            Capability.SEAT_BELT -> "안전띠"
            Capability.WIPER -> "와이퍼"
            Capability.LOW_BEAM -> "전조등"
            Capability.AIRBAG -> "에어백"
        }
    }
    return "받는 중: $names" + if (s.detail.isNotEmpty()) " / ${s.detail}" else ""
}
