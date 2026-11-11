package app.anbu.car.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anbu.car.baseline.Level
import app.anbu.car.navi.Places

@Composable
fun T(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalAnbuColors.current.ink,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = align, lineBreak = KoreanLineBreak),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 한글을 글자 중간이 아니라 어절 단위로 줄바꿈한다("대/신"처럼 끊기지 않게). */
private val KoreanLineBreak = LineBreak(
    strategy = LineBreak.Strategy.HighQuality,
    strictness = LineBreak.Strictness.Strict,
    wordBreak = LineBreak.WordBreak.Phrase,
)

enum class ButtonKind { PRIMARY, SECONDARY, DANGER }

/**
 * 큰 버튼. 높이는 최소 112dp다.
 * 누르면 색만 바로 바뀐다. 물결 같은 움직이는 효과는 주행 중 시선을 끌 수 있어 쓰지 않는다.
 */
@Composable
fun BigButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    sub: String? = null,
    minHeight: Dp = 112.dp,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = LocalAnbuColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        !enabled -> c.cardSunken
        kind == ButtonKind.PRIMARY -> if (pressed) c.actionPressed else c.action
        else -> if (pressed) c.cardSunken else c.card
    }
    val fg = when {
        !enabled -> c.inkSoft
        kind == ButtonKind.PRIMARY -> c.onAction
        kind == ButtonKind.DANGER -> c.very
        else -> c.ink
    }
    val outline = when (kind) {
        ButtonKind.PRIMARY -> Color.Transparent
        ButtonKind.SECONDARY -> c.ink
        ButtonKind.DANGER -> c.very
    }
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .clip(shape)
            .background(bg)
            .border(3.dp, if (enabled) outline else Color.Transparent, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 32.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(22.dp))
        }
        Column(horizontalAlignment = if (leading != null) Alignment.Start else Alignment.CenterHorizontally) {
            T(label, AnbuType.button, color = fg, align = if (leading != null) TextAlign.Start else TextAlign.Center)
            if (sub != null) {
                T(
                    sub, AnbuType.caption,
                    color = if (kind == ButtonKind.PRIMARY && enabled) fg.copy(alpha = 0.86f) else c.inkSoft,
                    align = if (leading != null) TextAlign.Start else TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 도장. 안부를 보냈다는 표시에만 쓴다.
 * 편지 끝에 찍는 인주 도장처럼 살짝 기울여 찍는다.
 */
@Composable
fun Seal(text: String, modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val c = LocalAnbuColors.current
    Box(
        modifier = modifier
            .size(size)
            .rotate(-9f)
            .semantics { contentDescription = "$text 도장" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val w = this.size.minDimension
            drawCircle(c.seal, radius = w * 0.47f, style = Stroke(width = w * 0.055f))
            drawCircle(c.seal, radius = w * 0.385f, style = Stroke(width = w * 0.016f))
        }
        BasicText(
            text = text,
            style = TextStyle(
                color = c.seal,
                fontSize = (size.value * 0.27f).sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                lineHeight = (size.value * 0.3f).sp,
            ),
        )
    }
}

/**
 * 단계 표시. 색을 구분하기 어려운 분도 알 수 있게 모양을 다르게 했다.
 * 비슷: 꽉 찬 동그라미 / 조금 다름: 반만 찬 동그라미 / 많이 다름: 마름모
 */
@Composable
fun LevelMark(level: Level, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val c = LocalAnbuColors.current
    val color = when (level) {
        Level.SIMILAR -> c.similar
        Level.SLIGHTLY_DIFFERENT -> c.slightly
        Level.VERY_DIFFERENT -> c.very
    }
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension
        val stroke = w * 0.12f
        when (level) {
            Level.SIMILAR -> drawCircle(color, radius = w * 0.44f)
            Level.SLIGHTLY_DIFFERENT -> {
                drawCircle(color, radius = w * 0.44f - stroke / 2, style = Stroke(stroke))
                drawArc(
                    color, startAngle = 90f, sweepAngle = 180f, useCenter = true,
                    topLeft = Offset(w * 0.06f, w * 0.06f), size = Size(w * 0.88f, w * 0.88f),
                )
            }
            Level.VERY_DIFFERENT -> {
                val p = Path().apply {
                    moveTo(w / 2, w * 0.02f); lineTo(w * 0.98f, w / 2)
                    lineTo(w / 2, w * 0.98f); lineTo(w * 0.02f, w / 2); close()
                }
                drawPath(p, color)
            }
        }
    }
}

fun Level.colorIn(c: AnbuColors): Color = when (this) {
    Level.SIMILAR -> c.similar
    Level.SLIGHTLY_DIFFERENT -> c.slightly
    Level.VERY_DIFFERENT -> c.very
}

/** 테두리만 있는 작은 표지. "시뮬레이션", "예시 기록 포함" 같은 사실 표시에 쓴다. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color = LocalAnbuColors.current.inkSoft) {
    Box(
        modifier
            .border(2.dp, color, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        T(text, AnbuType.caption, color = color)
    }
}

/** 큰 켜고 끄기. 줄 전체가 눌린다. 상태는 스위치 그림과 "켬/끔" 글자로 함께 보여 준다. */
@Composable
fun ToggleRow(
    title: String,
    sub: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
) {
    val c = LocalAnbuColors.current
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 104.dp)
            .clip(shape)
            .background(c.card)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !locked,
                role = Role.Switch,
            ) { onChange(!checked) }
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            T(title, AnbuType.bodyStrong)
            if (sub != null) T(sub, AnbuType.caption, color = c.inkSoft)
        }
        Spacer(Modifier.width(20.dp))
        if (locked) {
            Tag("필수", color = c.ink)
        } else {
            T(if (checked) "켬" else "끔", AnbuType.bodyStrong, color = if (checked) c.ink else c.inkSoft)
            Spacer(Modifier.width(14.dp))
            Canvas(Modifier.size(width = 92.dp, height = 52.dp)) {
                val h = size.height
                val track = if (checked) c.action else c.cardSunken
                drawRoundRect(track, cornerRadius = CornerRadius(h / 2, h / 2))
                if (!checked) {
                    drawRoundRect(
                        c.inkSoft, cornerRadius = CornerRadius(h / 2, h / 2),
                        style = Stroke(width = h * 0.06f),
                    )
                }
                val r = h * 0.38f
                val cx = if (checked) size.width - h / 2 else h / 2
                drawCircle(if (checked) c.onAction else c.inkSoft, radius = r, center = Offset(cx, h / 2))
            }
        }
    }
}

/** 화면 아래에 잠깐 뜨는 한 줄 안내. 움직이지 않고 제자리에 나타났다 사라진다. */
@Composable
fun Banner(text: String, modifier: Modifier = Modifier) {
    val c = LocalAnbuColors.current
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(c.ink)
            .padding(horizontal = 30.dp, vertical = 20.dp)
    ) {
        T(text, AnbuType.bodyStrong, color = c.paper)
    }
}

@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).background(LocalAnbuColors.current.rule))
}

@Composable
fun RowScope.Gap(width: Dp) = Spacer(Modifier.width(width))

/** 자주 가는 곳 그림. 선 몇 개로만 그려 한눈에 구분되게 했다. */
@Composable
fun PlaceGlyph(placeId: String, color: Color, size: Dp = 76.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val st = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (placeId) {
            Places.HOME -> house(s, color, st)
            Places.HOSPITAL -> {
                drawRoundRect(
                    color, topLeft = Offset(s * 0.12f, s * 0.12f), size = Size(s * 0.76f, s * 0.76f),
                    cornerRadius = CornerRadius(s * 0.16f, s * 0.16f), style = st,
                )
                drawLine(color, Offset(s * 0.5f, s * 0.3f), Offset(s * 0.5f, s * 0.7f), st.width, StrokeCap.Round)
                drawLine(color, Offset(s * 0.3f, s * 0.5f), Offset(s * 0.7f, s * 0.5f), st.width, StrokeCap.Round)
            }
            Places.DAUGHTER -> {
                house(s, color, st)
                // 집 안의 작은 하트
                val p = Path().apply {
                    moveTo(s * 0.5f, s * 0.78f)
                    cubicTo(s * 0.3f, s * 0.64f, s * 0.36f, s * 0.48f, s * 0.5f, s * 0.58f)
                    cubicTo(s * 0.64f, s * 0.48f, s * 0.7f, s * 0.64f, s * 0.5f, s * 0.78f)
                    close()
                }
                drawPath(p, color)
            }
            Places.MART -> {
                // 장바구니
                val p = Path().apply {
                    moveTo(s * 0.14f, s * 0.42f); lineTo(s * 0.86f, s * 0.42f)
                    lineTo(s * 0.76f, s * 0.84f); lineTo(s * 0.24f, s * 0.84f); close()
                }
                drawPath(p, color, style = st)
                drawLine(color, Offset(s * 0.34f, s * 0.42f), Offset(s * 0.44f, s * 0.16f), st.width, StrokeCap.Round)
                drawLine(color, Offset(s * 0.66f, s * 0.42f), Offset(s * 0.56f, s * 0.16f), st.width, StrokeCap.Round)
            }
            else -> drawCircle(color, radius = s * 0.36f, style = st)
        }
    }
}

private fun DrawScope.house(s: Float, color: Color, st: Stroke) {
    val p = Path().apply {
        moveTo(s * 0.1f, s * 0.5f); lineTo(s * 0.5f, s * 0.14f); lineTo(s * 0.9f, s * 0.5f)
    }
    drawPath(p, color, style = st)
    val body = Path().apply {
        moveTo(s * 0.22f, s * 0.44f); lineTo(s * 0.22f, s * 0.88f)
        lineTo(s * 0.78f, s * 0.88f); lineTo(s * 0.78f, s * 0.44f)
    }
    drawPath(body, color, style = st)
}

/** 남은 시간을 보여 주는 고리. 1초에 한 칸씩만 바뀐다(부드럽게 움직이지 않는다). */
@Composable
fun CountRing(secondsLeft: Int, total: Int, size: Dp, modifier: Modifier = Modifier) {
    val c = LocalAnbuColors.current
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.minDimension
            val stroke = w * 0.07f
            val inset = stroke / 2
            drawArc(
                c.cardSunken, 0f, 360f, false,
                topLeft = Offset(inset, inset), size = Size(w - stroke, w - stroke), style = Stroke(stroke),
            )
            val sweep = 360f * secondsLeft.coerceIn(0, total) / total
            drawArc(
                c.ink, -90f, sweep, false,
                topLeft = Offset(inset, inset), size = Size(w - stroke, w - stroke),
                style = Stroke(stroke, cap = StrokeCap.Butt),
            )
        }
        BasicText(
            text = secondsLeft.toString(),
            style = TextStyle(
                color = c.ink, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
