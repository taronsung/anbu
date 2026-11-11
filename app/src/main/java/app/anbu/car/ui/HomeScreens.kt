package app.anbu.car.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.anbu.car.AppController
import app.anbu.car.Screen
import app.anbu.car.UiState
import app.anbu.car.anbu.AnbuNote
import app.anbu.car.anbu.NoteType
import app.anbu.car.core.CheckInReason
import app.anbu.car.navi.Place
import app.anbu.car.navi.withDirectionParticle
import app.anbu.car.signals.Gear
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

// ---------------------------------------------------------------- 홈

@Composable
fun HomeScreen(ui: UiState, controller: AppController) {
    val wide = LocalWide.current
    Column(Modifier.fillMaxSize()) {
        HomeHeader(ui, controller)
        Spacer(Modifier.height(if (wide) 26.dp else 20.dp))
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.4f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        HomeNoteCard(ui, controller, Modifier.fillMaxWidth())
                        HomeReplyCard(ui, Modifier.fillMaxWidth())
                    }
                    HomeActions(ui, controller)
                }
                Spacer(Modifier.width(36.dp))
                Column(Modifier.weight(0.6f).fillMaxHeight()) {
                    T("어디로 가세요?", AnbuType.title)
                    Spacer(Modifier.height(14.dp))
                    PlaceGrid(ui, controller, Modifier.fillMaxWidth().weight(1f))
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                T("어디로 가세요?", AnbuType.title)
                Spacer(Modifier.height(16.dp))
                PlaceGrid(ui, controller, Modifier.fillMaxWidth().height(430.dp))
                Spacer(Modifier.height(20.dp))
                HomeActions(ui, controller)
                Spacer(Modifier.height(20.dp))
                HomeNoteCard(ui, controller, Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                HomeReplyCard(ui, Modifier.fillMaxWidth())
                Spacer(Modifier.height(120.dp))
            }
        }
    }
}

@Composable
private fun HomeHeader(ui: UiState, controller: AppController) {
    val c = LocalAnbuColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        T("안부", AnbuType.title)
        Gap(24.dp)
        Column(Modifier.weight(1f)) {
            T(todayText(), AnbuType.body, color = c.inkSoft, maxLines = 1)
        }
        if (ui.simulated) {
            Tag("시뮬레이션")
            Gap(16.dp)
        }
        T(
            when {
                ui.limited -> "길안내만 쓰는 중"
                ui.skippingTrip -> "이번 운전은 쉬는 중"
                ui.paused -> "오늘은 쉬는 중"
                ui.vehicle.gear == Gear.PARK -> "주차 중"
                else -> ""
            },
            AnbuType.bodyStrong, color = if (ui.paused) c.slightly else c.inkSoft,
        )
        Gap(20.dp)
        if (!ui.limited) {
            // SpeechToText SDK로 듣는다. 듣는 동안에는 단추가 채워지고 글이 바뀐다.
            BigButton(
                if (ui.listening) "듣는 중" else "말로 하기", { controller.listenForCommand() },
                kind = if (ui.listening) ButtonKind.PRIMARY else ButtonKind.SECONDARY, minHeight = 84.dp,
            )
            Gap(14.dp)
        }
        BigButton(
            "설정", { controller.go(if (ui.limited) Screen.STATUS else Screen.SETTINGS) },
            kind = ButtonKind.SECONDARY, minHeight = 84.dp,
        )
    }
}

@Composable
private fun PlaceGrid(ui: UiState, controller: AppController, modifier: Modifier) {
    val places = ui.places.take(4)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        for (row in places.chunked(2)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                for (p in row) {
                    val selected = ui.destination?.id == p.id
                    PlaceTile(p, selected, if (selected) ui.etaText else null, Modifier.weight(1f).fillMaxHeight()) {
                        controller.tapPlace(p)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PlaceTile(place: Place, selected: Boolean, eta: String?, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalAnbuColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val filled = selected || pressed
    val bg = if (filled) c.ink else c.card
    val fg = if (filled) c.paper else c.ink
    val shape = RoundedCornerShape(36.dp)
    Column(
        modifier
            .clip(shape)
            .background(bg)
            .border(4.dp, c.ink, shape)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 34.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        PlaceGlyph(place.id, fg, size = if (LocalWide.current) 96.dp else 72.dp)
        Column {
            T(place.label, AnbuType.display.copy(fontSize = AnbuType.display.fontSize * 0.86f), color = fg, maxLines = 1)
            if (selected) T(eta ?: "안내 중", AnbuType.caption, color = fg, maxLines = 1)
        }
    }
}

@Composable
private fun HomeActions(ui: UiState, controller: AppController) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (ui.limited) {
            BigButton(
                "동의하고 안부 쓰기", { controller.reopenConsent() },
                Modifier.fillMaxWidth(), sub = "지금은 길안내만 쓰고 있어요",
            )
        } else {
            BigButton(
                "가족에게 한마디", { controller.sendDeparture() },
                Modifier.fillMaxWidth(), sub = "지금 출발한다고 전해요", enabled = !ui.paused,
            )
            BigButton(
                "이번 주 운전 일기", { controller.go(Screen.DIARY) },
                Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, sub = "나에게 먼저 보여 줘요",
            )
        }
    }
}

/** 가족이 보낸 답장. 정해 둔 문장 가운데 하나이고, 차가 서 있을 때 읽어 준 뒤 여기에 남는다. */
@Composable
private fun HomeReplyCard(ui: UiState, modifier: Modifier) {
    val reply = ui.lastReply ?: return
    if (ui.limited || ui.paused) return
    val c = LocalAnbuColors.current
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(c.card)
            .border(3.dp, c.ink, RoundedCornerShape(28.dp))
            .padding(horizontal = 30.dp, vertical = 20.dp),
    ) {
        T("가족의 답장 · ${clockText(reply.atMs)}", AnbuType.caption, color = c.inkSoft)
        T(reply.preset.text, AnbuType.headline)
    }
}

@Composable
private fun HomeNoteCard(ui: UiState, controller: AppController, modifier: Modifier) {
    val c = LocalAnbuColors.current
    val note = ui.lastNote
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(c.card)
            .padding(horizontal = 30.dp, vertical = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        when {
            ui.limited -> T(
                "동의하지 않으셔서 기록도 전송도 하지 않아요. 자주 가는 곳 길안내는 그대로 쓸 수 있어요.",
                AnbuType.body, color = c.inkSoft,
            )
            ui.paused -> Column {
                T(if (ui.skippingTrip) "이번 운전은 쉬는 중이에요." else "오늘은 쉬는 중이에요.", AnbuType.headline)
                T(
                    if (ui.skippingTrip) "주차할 때까지 기록도 전송도 하지 않아요." else "자정까지 기록도 전송도 하지 않아요.",
                    AnbuType.body, color = c.inkSoft,
                )
                Spacer(Modifier.height(14.dp))
                BigButton("다시 시작", { controller.resume() }, kind = ButtonKind.SECONDARY, minHeight = 88.dp)
            }
            note == null -> T(
                "아직 전한 안부가 없어요. 이름 붙인 곳에 도착하면 가족에게 대신 전해 드려요.",
                AnbuType.body, color = c.inkSoft,
            )
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Seal("전함", size = 118.dp)
                Gap(26.dp)
                Column {
                    T(noteSummary(note), AnbuType.bodyStrong)
                    T("${clockText(note.atMs)}에 전했어요", AnbuType.caption, color = c.inkSoft)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 주행 중

/** 주행 중에는 글 두 줄과 버튼 하나만 둔다. 움직이는 요소는 없다. */
@Composable
fun DrivingScreen(ui: UiState, controller: AppController) {
    val c = LocalAnbuColors.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("주행 중", AnbuType.headline, color = c.inkSoft, modifier = Modifier.weight(1f))
            if (ui.simulated) Tag("시뮬레이션")
        }
        Column {
            val dest = ui.destination
            T(
                if (dest != null) "${withDirectionParticle(dest.label)} 가는 중" else "안전하게 다녀오세요",
                AnbuType.display,
            )
            if (ui.etaText != null) {
                Spacer(Modifier.height(10.dp))
                T(ui.etaText, AnbuType.title, color = c.inkSoft)
            }
        }
        Column {
            when {
                ui.limited -> T("기록도 전송도 하지 않아요.", AnbuType.body, color = c.inkSoft)
                ui.paused -> T(
                    if (ui.skippingTrip) "이번 운전은 쉬는 중이에요. 기록도 전송도 하지 않아요."
                    else "오늘은 쉬는 중이에요. 기록도 전송도 하지 않아요.",
                    AnbuType.headline, color = c.slightly,
                )
                ui.departureSentThisTrip -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Seal("전함", size = 132.dp)
                    Gap(28.dp)
                    T("가족에게 출발한다고 전했어요.", AnbuType.headline)
                }
                else -> {
                    BigButton(
                        "출발한다고 전하기", { controller.sendDeparture() },
                        Modifier.fillMaxWidth(), minHeight = 150.dp,
                    )
                    Spacer(Modifier.height(16.dp))
                    // 주행 중에는 한 번에 눌리는 큰 단추 하나로 듣기를 켠다(SpeechToText SDK).
                    BigButton(
                        if (ui.listening) "듣고 있어요" else "말로 하기", { controller.listenForCommand() },
                        Modifier.fillMaxWidth(),
                        kind = if (ui.listening) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                        sub = if (ui.listening) "\"출발한다고 전해 줘\"라고 말씀하세요" else "누른 뒤 \"출발한다고 전해 줘\"",
                        minHeight = 120.dp,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 도착 안부 카운트다운

@Composable
fun ArrivalScreen(ui: UiState, controller: AppController) {
    val a = ui.arrival ?: return
    val c = LocalAnbuColors.current
    val wide = LocalWide.current
    val ring: @Composable () -> Unit = {
        CountRing(a.secondsLeft, a.totalSeconds, size = if (wide) 380.dp else 300.dp)
    }
    val text: @Composable () -> Unit = {
        Column {
            T("가족에게 도착 안부를 보낼게요", AnbuType.title)
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(c.card)
                    .padding(horizontal = 28.dp, vertical = 22.dp)
            ) {
                Column {
                    T(a.note.line, AnbuType.headline)
                    a.note.durationNote?.let { T(it, AnbuType.body, color = c.inkSoft) }
                    T("도착 시각과 장소 이름만 보냅니다. 위치는 보내지 않아요.", AnbuType.caption, color = c.inkSoft)
                }
            }
            Spacer(Modifier.height(26.dp))
            BigButton("보내지 않기", { controller.cancelArrival() }, Modifier.fillMaxWidth(), minHeight = 150.dp)
            Spacer(Modifier.height(18.dp))
            BigButton(
                "지금 보내기", { controller.sendArrivalNow() }, Modifier.fillMaxWidth(),
                kind = ButtonKind.SECONDARY,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(
                    if (ui.listening) "듣고 있어요. \"보내지 마\"라고 말씀하세요" else "안내가 끝나면 \"보내지 마\"를 듣습니다",
                    AnbuType.body, color = c.inkSoft, modifier = Modifier.weight(1f),
                )
                if (ui.simulated) Tag("시뮬레이션")
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(0.4f), contentAlignment = Alignment.Center) { ring() }
            Spacer(Modifier.width(40.dp))
            Box(Modifier.weight(0.6f)) { text() }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ring()
            Spacer(Modifier.height(24.dp))
            text()
        }
    }
}

// ---------------------------------------------------------------- 괜찮으세요

@Composable
fun CheckInScreen(ui: UiState, controller: AppController) {
    val k = ui.checkIn ?: return
    val c = LocalAnbuColors.current
    val context = LocalContext.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(
                when (k.reason) {
                    CheckInReason.AIRBAG -> "에어백이 작동했어요"
                    CheckInReason.HARD_STOP -> "급하게 멈춘 뒤 서 있어요"
                },
                AnbuType.headline, color = c.inkSoft, modifier = Modifier.weight(1f),
            )
            if (ui.simulated) Tag("시뮬레이션")
        }
        Column {
            T("괜찮으세요?", AnbuType.display.copy(fontSize = AnbuType.display.fontSize * 1.3f, lineHeight = AnbuType.display.lineHeight * 1.3f))
            Spacer(Modifier.height(8.dp))
            T(
                if (ui.consent.checkInAlert) "${k.secondsLeft}초 안에 답이 없으면 가족에게 알려요."
                else "${k.secondsLeft}초 뒤에 이 화면을 닫아요.",
                AnbuType.headline, color = c.inkSoft,
            )
            T(
                if (ui.listening) "듣고 있어요. \"괜찮아\"라고 말씀하세요" else "물음이 끝나면 \"괜찮아\"를 듣습니다",
                AnbuType.body, color = c.inkSoft,
            )
        }
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                BigButton(
                    "괜찮아요", { controller.answerCheckInOk() },
                    Modifier.weight(0.64f), minHeight = 170.dp,
                )
                BigButton(
                    "119 전화", {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_DIAL, Uri.parse("tel:119")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    Modifier.weight(0.36f), kind = ButtonKind.DANGER, minHeight = 170.dp,
                )
            }
            Spacer(Modifier.height(14.dp))
            T("119에 자동으로 신고하지는 않아요. 필요하면 직접 눌러 주세요.", AnbuType.caption, color = c.inkSoft)
        }
    }
}

// ---------------------------------------------------------------- 공통 글

private val zone: ZoneId get() = ZoneId.systemDefault()

fun todayText(): String {
    val d = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone)
    val dow = d.dayOfWeek.getDisplayName(JTextStyle.FULL, Locale.KOREAN)
    return "${d.monthValue}월 ${d.dayOfMonth}일 $dow"
}

fun clockText(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
    return "${if (t.hour < 12) "오전" else "오후"} $h12:${"%02d".format(t.minute)}"
}

fun noteSummary(note: AnbuNote): String = when (note.type) {
    NoteType.ARRIVAL -> "${note.placeLabel ?: "외출지"} 도착을 전했어요"
    NoteType.DEPARTURE -> if (note.placeLabel != null) "${withDirectionParticle(note.placeLabel)} 출발한다고 전했어요" else "출발한다고 전했어요"
    NoteType.WEEKLY -> "이번 주 운전 일기를 나눴어요"
    NoteType.CHECK_IN -> "답이 없어 가족에게 알렸어요"
}
