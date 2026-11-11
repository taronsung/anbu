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
import app.anbu.car.Screen
import app.anbu.car.UiState
import app.anbu.car.anbu.label
import app.anbu.car.baseline.Level
import app.anbu.car.baseline.Metric
import app.anbu.car.baseline.Weeks
import app.anbu.car.core.DiaryView
import app.anbu.car.core.WeeklyDecision
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 주간 운전 일기. 본인이 먼저 보고, 나눌지 말지는 본인이 정한다.
 * 정차(P) 중에만 열린다.
 */
@Composable
fun DiaryScreen(ui: UiState, controller: AppController) {
    val d = ui.diary ?: return
    val wide = LocalWide.current
    val c = LocalAnbuColors.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("이번 주 운전 일기", screenTitle(), maxLines = 1)
                if (wide) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(rangeText(d), AnbuType.body, color = c.inkSoft, maxLines = 1)
                        if (d.hasSynthetic) {
                            Gap(16.dp)
                            Tag("예시 기록 포함")
                        }
                    }
                } else {
                    T(rangeText(d), AnbuType.body, color = c.inkSoft, maxLines = 1)
                    if (d.hasSynthetic) {
                        Spacer(Modifier.height(6.dp))
                        Tag("예시 기록 포함")
                    }
                }
            }
            Gap(12.dp)
            BigButton("돌아가기", { controller.go(Screen.HOME) }, kind = ButtonKind.SECONDARY, minHeight = 84.dp)
        }
        Spacer(Modifier.height(22.dp))
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.5f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Verdict(d)
                    DiaryActions(ui, d, controller)
                }
                Spacer(Modifier.width(36.dp))
                MetricList(d, Modifier.weight(0.5f).fillMaxHeight().verticalScroll(rememberScrollState()))
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Verdict(d)
                Spacer(Modifier.height(22.dp))
                DiaryActions(ui, d, controller)
                Spacer(Modifier.height(22.dp))
                MetricList(d, Modifier.fillMaxWidth())
                Spacer(Modifier.height(120.dp))
            }
        }
    }
}

@Composable
private fun Verdict(d: DiaryView) {
    val c = LocalAnbuColors.current
    val a = d.assessment
    Column {
        if (a.baselineReady) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelMark(a.overall, size = 64.dp)
                Gap(20.dp)
                T(a.overall.label(), AnbuType.display.copy(fontSize = AnbuType.display.fontSize * 0.82f), color = a.overall.colorIn(c))
            }
            Spacer(Modifier.height(12.dp))
        }
        T(d.line, AnbuType.headline)
        Spacer(Modifier.height(8.dp))
        T(
            if (a.baselineReady) "지난 ${a.weeksUsed}주의 내 운전과 견준 결과예요. 건강이나 운전 능력을 판단하는 것이 아니에요."
            else "지난 기록이 4주 쌓이면 평소와 견주어 알려 드려요.",
            AnbuType.caption, color = c.inkSoft,
        )
    }
}

@Composable
private fun DiaryActions(ui: UiState, d: DiaryView, controller: AppController) {
    val c = LocalAnbuColors.current
    Column {
        when (d.decision) {
            WeeklyDecision.SHARED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Seal("전함", size = 118.dp)
                Gap(24.dp)
                T("가족과 나눴어요.", AnbuType.headline)
            }
            WeeklyDecision.PRIVATE -> T("이번 주는 나만 보기로 했어요.", AnbuType.headline)
            null -> T(
                if (ui.consent.shareWeeklyDetails) "나누면 단계와 한 줄, 아래 수치가 가족에게 갑니다."
                else "나누면 \"${d.assessment.overall.label()}\"과 위 한 줄만 가족에게 갑니다. 수치는 가지 않아요.",
                AnbuType.body, color = c.inkSoft,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            BigButton(
                "가족과 나누기", { controller.shareWeekly() }, Modifier.weight(1f),
                enabled = d.decision != WeeklyDecision.SHARED && !ui.paused,
            )
            BigButton("나만 보기", { controller.keepWeeklyPrivate() }, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
        }
        Spacer(Modifier.height(18.dp))
        BigButton("읽어 주기", { controller.readDiaryAloud() }, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, minHeight = 96.dp)
    }
}

@Composable
private fun MetricList(d: DiaryView, modifier: Modifier) {
    val w = d.week
    val levels = d.assessment.metrics.associate { it.metric to it.level }
    val rows = listOf(
        Triple("운전한 횟수", "${w.trips}번", levels[Metric.TRIPS]),
        Triple("운전한 거리", "${w.distanceKm.roundToInt()}km", levels[Metric.DISTANCE_KM]),
        Triple("밤 운전", "${(w.nightShare * 100).roundToInt()}%", levels[Metric.NIGHT_SHARE]),
        Triple("비 오는 날 운전", "${(w.rainShare * 100).roundToInt()}%", null),
        Triple("급하게 멈춤", "${w.hardBrakes}번", levels[Metric.HARD_BRAKES_PER_100KM]),
        Triple("안전띠 늦게 맴", "${w.lateBeltTrips}번", levels[Metric.LATE_BELT_TRIPS]),
        Triple("길 다시 찾음", "${w.reroutes}번", levels[Metric.REROUTES]),
        Triple("보호구역·역주행 주의", "${w.schoolZoneAlerts + w.wrongWayAlerts}번", levels[Metric.CAUTION_ALERTS]),
    )
    val c = LocalAnbuColors.current
    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(c.card)
            .padding(horizontal = 30.dp, vertical = 14.dp)
    ) {
        rows.forEachIndexed { i, (name, value, level) ->
            if (i > 0) Rule()
            Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                T(name, AnbuType.body, modifier = Modifier.weight(1f))
                if (d.assessment.baselineReady && level != null && level != Level.SIMILAR) {
                    LevelMark(level, size = 30.dp)
                    Gap(10.dp)
                    T(level.label(), AnbuType.caption, color = level.colorIn(c))
                    Gap(18.dp)
                }
                T(value, AnbuType.headline)
            }
        }
    }
}

private fun rangeText(d: DiaryView): String {
    val zone = ZoneId.systemDefault()
    val from = Instant.ofEpochMilli(d.week.weekStartMs).atZone(zone).plusDays(1)
    val to = Instant.ofEpochMilli(d.week.weekStartMs + Weeks.WEEK_MS).atZone(zone)
    return "${from.monthValue}월 ${from.dayOfMonth}일부터 ${to.monthValue}월 ${to.dayOfMonth}일까지"
}
