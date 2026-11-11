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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.anbu.car.AppController
import app.anbu.car.InviteStep
import app.anbu.car.UiState
import app.anbu.car.privacy.ConsentItem
import app.anbu.car.privacy.WHO_CHOICES

/**
 * 첫 실행 동의. 세 걸음으로 나눠 한 화면에 누를 것이 서너 개를 넘지 않게 했다.
 *  1. 안부가 하는 일과 하지 않는 일
 *  2. 항목별 동의(수집 항목, 목적, 보유 기간, 거부할 권리)
 *  3. 가족에게 보일 호칭
 * 동의한 뒤에는 가족 초대 코드를 보여 준다.
 */
@Composable
fun ConsentScreen(ui: UiState, controller: AppController) {
    when (ui.invite) {
        InviteStep.CODE -> { InvitePage(ui, controller); return }
        InviteStep.PERMISSIONS -> { PermissionPage(ui, controller); return }
        InviteStep.NONE -> Unit
    }
    var step by rememberSaveable { mutableStateOf(0) }
    // 선택 항목은 꺼진 채로 보여 주고, 본인이 켠 것만 동의로 받는다.
    var shareArrival by rememberSaveable { mutableStateOf(false) }
    var useProximity by rememberSaveable { mutableStateOf(false) }
    var checkInAlert by rememberSaveable { mutableStateOf(false) }
    var who by rememberSaveable { mutableStateOf(WHO_CHOICES[0]) }

    when (step) {
        0 -> IntroPage(onNext = { step = 1 }, onDecline = { controller.declineConsent() })
        1 -> ItemsPage(
            shareArrival, useProximity, checkInAlert,
            onShareArrival = { shareArrival = it },
            onUseProximity = { useProximity = it },
            onCheckInAlert = { checkInAlert = it },
            onAgree = { step = 2 },
            onDecline = { controller.declineConsent() },
        )
        else -> WhoPage(
            who, onWho = { who = it },
            onStart = { controller.acceptConsent(who, shareArrival, useProximity, checkInAlert) },
            onBack = { step = 1 },
        )
    }
}

@Composable
private fun IntroPage(onNext: () -> Unit, onDecline: () -> Unit) {
    val c = LocalAnbuColors.current
    val wide = LocalWide.current
    val promises = listOf(
        "집, 병원처럼 이름을 붙인 곳에 도착하면 가족에게 \"잘 도착했어요\"를 대신 전합니다.",
        "운전이 평소와 달라지면 가족보다 먼저 본인에게 알려 드립니다.",
        "위치를 따라다니지 않습니다. 좌표와 경로, 속도는 차 밖으로 나가지 않습니다.",
        "오늘은 쉬기 한 번이면 그날 기록과 전송을 멈춥니다. 전체 삭제도 한 번입니다.",
    )
    Column(Modifier.fillMaxSize()) {
        T("안부", AnbuType.display)
        T("감시가 아니라 안부를 전하는 앱입니다.", AnbuType.headline, color = c.inkSoft)
        Spacer(Modifier.height(24.dp))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            for (p in promises) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(c.card)
                        .padding(horizontal = 30.dp, vertical = 22.dp)
                ) { T(p, AnbuType.body) }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BigButton("동의하지 않고 길안내만 쓰기", onDecline, Modifier.weight(0.45f), kind = ButtonKind.SECONDARY)
                BigButton("무엇을 쓰는지 보기", onNext, Modifier.weight(0.55f))
            }
        } else {
            BigButton("무엇을 쓰는지 보기", onNext, Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            BigButton("동의하지 않고 길안내만 쓰기", onDecline, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
        }
    }
}

@Composable
private fun ItemsPage(
    shareArrival: Boolean,
    useProximity: Boolean,
    checkInAlert: Boolean,
    onShareArrival: (Boolean) -> Unit,
    onUseProximity: (Boolean) -> Unit,
    onCheckInAlert: (Boolean) -> Unit,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
) {
    val c = LocalAnbuColors.current
    val wide = LocalWide.current
    val items: @Composable () -> Unit = {
        ConsentCard(ConsentItem.DRIVING_SUMMARY, true, {}, locked = true)
        ConsentCard(ConsentItem.ARRIVAL_NOTE, shareArrival, onShareArrival)
        ConsentCard(ConsentItem.PLACE_PROXIMITY, useProximity, onUseProximity)
        ConsentCard(ConsentItem.CHECK_IN_ALERT, checkInAlert, onCheckInAlert)
    }
    val side: @Composable () -> Unit = {
        T("거부할 수 있습니다", AnbuType.headline)
        Spacer(Modifier.height(8.dp))
        T(
            "동의하지 않아도 자주 가는 곳 길안내는 쓸 수 있습니다. 이 경우 기록과 전송은 하지 않습니다.",
            AnbuType.body, color = c.inkSoft,
        )
        Spacer(Modifier.height(12.dp))
        T(
            "동의는 설정에서 언제든 거둘 수 있고, 거두면 차 안 기록과 가족 화면의 안부를 바로 지웁니다.",
            AnbuType.body, color = c.inkSoft,
        )
        Spacer(Modifier.height(12.dp))
        T(
            "주행 기록은 광고나 마케팅에 쓰지 않고, 보험사를 포함한 제3자에게 주지 않습니다.",
            AnbuType.body, color = c.inkSoft,
        )
    }
    Column(Modifier.fillMaxSize()) {
        T("무엇을, 왜, 얼마나 쓰는지", screenTitle())
        Spacer(Modifier.height(18.dp))
        if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.weight(0.62f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) { items() }
                Spacer(Modifier.width(36.dp))
                Column(Modifier.weight(0.38f).fillMaxHeight().verticalScroll(rememberScrollState())) { side() }
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items()
                Column { side() }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BigButton("동의하지 않고 길안내만 쓰기", onDecline, Modifier.weight(0.45f), kind = ButtonKind.SECONDARY)
                BigButton("동의하고 계속", onAgree, Modifier.weight(0.55f))
            }
        } else {
            BigButton("동의하고 계속", onAgree, Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            BigButton("동의하지 않고 길안내만 쓰기", onDecline, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
        }
    }
}

@Composable
private fun ConsentCard(item: ConsentItem, checked: Boolean, onChange: (Boolean) -> Unit, locked: Boolean = false) {
    val c = LocalAnbuColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(c.card)
    ) {
        ToggleRow(
            title = item.title,
            sub = if (item.required) "주간 운전 일기에 꼭 필요합니다" else "선택 항목입니다",
            checked = checked, onChange = onChange, locked = locked,
        )
        Column(Modifier.padding(start = 28.dp, end = 28.dp, bottom = 22.dp)) {
            Rule()
            Spacer(Modifier.height(12.dp))
            Fact("쓰는 정보", item.collected)
            Fact("쓰는 이유", item.purpose)
            Fact("보관 기간", item.retention)
        }
    }
}

@Composable
private fun Fact(name: String, value: String) {
    val c = LocalAnbuColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        T(name, AnbuType.caption, color = c.inkSoft, modifier = Modifier.width(140.dp))
        T(value, AnbuType.caption, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WhoPage(who: String, onWho: (String) -> Unit, onStart: () -> Unit, onBack: () -> Unit) {
    val c = LocalAnbuColors.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            T("가족에게 어떻게 보일까요?", screenTitle())
            T("이름은 받지 않습니다. 호칭만 고르세요.", AnbuType.body, color = c.inkSoft)
        }
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            for (row in WHO_CHOICES.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    for (choice in row) {
                        BigButton(
                            choice, { onWho(choice) }, Modifier.weight(1f),
                            kind = if (choice == who) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                            sub = if (choice == who) "선택함" else null,
                            minHeight = 150.dp,
                        )
                    }
                }
            }
            T("가족 화면에는 \"${who}께서 병원에 잘 도착하셨어요.\"처럼 보입니다.", AnbuType.body, color = c.inkSoft)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigButton("이전", onBack, Modifier.weight(0.3f), kind = ButtonKind.SECONDARY)
            BigButton("이 호칭으로 시작", onStart, Modifier.weight(0.7f))
        }
    }
}

@Composable
private fun InvitePage(ui: UiState, controller: AppController) {
    val c = LocalAnbuColors.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            T("가족 초대 코드", AnbuType.title)
            T("안부를 받을 가족에게 이 코드를 알려 주세요.", AnbuType.body, color = c.inkSoft)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(30.dp))
                    .background(c.card)
                    .padding(horizontal = 60.dp, vertical = 30.dp)
            ) {
                T(
                    ui.consent.familyCode.chunked(3).joinToString(" "),
                    AnbuType.display.copy(fontSize = AnbuType.display.fontSize * 1.25f, lineHeight = AnbuType.display.lineHeight * 1.25f),
                )
            }
            Spacer(Modifier.height(20.dp))
            T(
                "가족이 가족 화면에서 이 코드를 넣으면 ${ui.consent.who}의 안부가 보입니다. 코드는 설정에서 다시 볼 수 있어요.",
                AnbuType.body, color = c.inkSoft,
            )
        }
        BigButton("다음", { controller.finishInviteCode() }, Modifier.fillMaxWidth(), minHeight = 130.dp)
    }
}

/**
 * 시스템 허용 창을 띄우기 전에 무엇을 왜 묻는지 먼저 알린다.
 * 차 속도 권한은 시스템 창에 "위치 정보"라고 나오므로, 오해하지 않도록 미리 설명한다.
 */
@Composable
private fun PermissionPage(ui: UiState, controller: AppController) {
    val c = LocalAnbuColors.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            T(
                if (ui.missingSpeedPermission && ui.missingLocationPermission) "허용을 두 가지 여쭤볼게요"
                else "허용을 한 가지 여쭤볼게요",
                AnbuType.title,
            )
            T("다음에 뜨는 창에서 고르시면 됩니다. 허용하지 않아도 안부는 쓸 수 있어요.", AnbuType.body, color = c.inkSoft)
        }
        Column(
            Modifier.weight(1f, fill = false).padding(vertical = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (ui.missingSpeedPermission) {
                PermissionCard(
                    "차 속도 읽기",
                    "급하게 멈춘 횟수를 세는 데 씁니다. 창에는 \"위치 정보\"라고 나오는데, 자동차용 안드로이드가 속도를 그렇게 분류하기 때문입니다. 안부는 여기서 속도만 읽습니다.",
                    "허용하지 않으면 운전 일기에서 급하게 멈춤 항목만 빠집니다.",
                )
            }
            if (ui.missingLocationPermission) {
                PermissionCard(
                    "저장한 장소 근처인지 알기",
                    "집이나 병원처럼 저장한 장소에 들어왔는지만 차가 알려 줍니다. 좌표는 차 밖으로 나가지 않습니다. 창에는 \"알 수 없는 작업\"이라고 나올 수 있는데, 차의 위치 서비스를 쓰겠다는 허용입니다.",
                    "허용하지 않으면 내비게이션으로 안내받아 도착한 곳만 알립니다.",
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigButton("나중에 하기", { controller.skipPermissions() }, Modifier.weight(0.38f), kind = ButtonKind.SECONDARY, minHeight = 130.dp)
            BigButton("허용 창 열기", { controller.askPermissions() }, Modifier.weight(0.62f), minHeight = 130.dp)
        }
    }
}

@Composable
private fun PermissionCard(title: String, why: String, ifNot: String) {
    val c = LocalAnbuColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(c.card)
            .padding(horizontal = 30.dp, vertical = 22.dp)
    ) {
        T(title, AnbuType.headline)
        Spacer(Modifier.height(6.dp))
        T(why, AnbuType.body)
        Spacer(Modifier.height(6.dp))
        T(ifNot, AnbuType.body, color = c.inkSoft)
    }
}
