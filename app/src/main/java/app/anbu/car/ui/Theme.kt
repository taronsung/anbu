package app.anbu.car.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/**
 * 색 이름은 쓰임새가 아니라 재료에서 따왔다.
 * 한지 바탕에 쪽빛 먹으로 쓰고, 보낸 안부에는 인주색 도장을 찍는다.
 */
data class AnbuColors(
    val paper: Color,
    val card: Color,
    val cardSunken: Color,
    val ink: Color,
    val inkSoft: Color,
    val rule: Color,
    /** 주된 버튼 바탕. 낮에는 쪽빛, 밤에는 치자색. */
    val action: Color,
    val onAction: Color,
    val actionPressed: Color,
    /** 도장(인주). 보낸 안부 표시에만 쓴다. */
    val seal: Color,
    val similar: Color,
    val slightly: Color,
    val very: Color,
    val isNight: Boolean,
)

val DayColors = AnbuColors(
    paper = Color(0xFFF2EEE4),
    card = Color(0xFFFCFAF5),
    cardSunken = Color(0xFFE6E0D2),
    ink = Color(0xFF14263A),
    inkSoft = Color(0xFF465564),
    rule = Color(0xFFCBC3B1),
    action = Color(0xFF173F5F),
    onAction = Color(0xFFFFFFFF),
    actionPressed = Color(0xFF0E2A41),
    seal = Color(0xFFBE2F1D),
    similar = Color(0xFF256447),
    slightly = Color(0xFF8A5600),
    very = Color(0xFFB3261E),
    isNight = false,
)

val NightColors = AnbuColors(
    paper = Color(0xFF0B1722),
    card = Color(0xFF142535),
    cardSunken = Color(0xFF1D3246),
    ink = Color(0xFFF3EEE3),
    inkSoft = Color(0xFFB7C3CE),
    rule = Color(0xFF304659),
    action = Color(0xFFF0C04A),
    onAction = Color(0xFF14263A),
    actionPressed = Color(0xFFD3A636),
    seal = Color(0xFFEB6A52),
    similar = Color(0xFF86D0AB),
    slightly = Color(0xFFF0C04A),
    very = Color(0xFFFF9488),
    isNight = true,
)

/**
 * 글자 크기. 60~80대 운전자가 팔 길이에서 읽는 것을 기준으로 잡았다.
 * 본문은 30sp, 가장 작은 보조 글도 24sp다.
 */
object AnbuType {
    val display = TextStyle(fontSize = 84.sp, lineHeight = 96.sp, fontWeight = FontWeight.Bold)
    val title = TextStyle(fontSize = 50.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold)
    val headline = TextStyle(fontSize = 38.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold)
    val button = TextStyle(fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(fontSize = 30.sp, lineHeight = 44.sp, fontWeight = FontWeight.Normal)
    val bodyStrong = TextStyle(fontSize = 30.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold)
    val caption = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)
}

/** 화면 맨 위 제목. 세로 창은 폭이 좁아 한 줄에 들어가도록 조금 줄인다. */
@Composable
fun screenTitle(): TextStyle =
    if (LocalWide.current) AnbuType.title else AnbuType.title.copy(fontSize = 40.sp, lineHeight = 52.sp)

val LocalAnbuColors = staticCompositionLocalOf { DayColors }

/** 화면이 가로로 넓은지(landscape 창) 세로로 긴지(portrait 창). */
val LocalWide = staticCompositionLocalOf { true }

/**
 * 가로 창은 1400x900, 세로 창은 700x1000 크기의 도화지에 그린 뒤 실제 창에 맞춰 늘리거나 줄인다.
 * Pleos 에뮬레이터의 가로 창(약 1700x1200dp)에서는 1.2배가 되어 본문이 36dp쯤으로 보인다.
 * 창이 작아지면 같은 비율로 줄어들 뿐 배치는 깨지지 않는다.
 */
@Composable
fun AnbuTheme(widthDp: Dp, heightDp: Dp, content: @Composable () -> Unit) {
    val wide = widthDp > heightDp * 1.1f
    val scale = if (wide) {
        minOf(widthDp.value / 1400f, heightDp.value / 900f)
    } else {
        minOf(widthDp.value / 700f, heightDp.value / 1000f)
    }.coerceIn(0.5f, 1.35f)
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalAnbuColors provides if (isSystemInDarkTheme()) NightColors else DayColors,
        LocalWide provides wide,
        LocalDensity provides Density(base.density * scale, base.fontScale),
        content = content,
    )
}
