package lux.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import lux.app.AppContext
import lux.audio.SfxId
import lux.res.Res
import lux.res.lilita_one
import lux.res.poppins_bold
import lux.res.poppins_extrabold
import lux.res.poppins_semibold
import org.jetbrains.compose.resources.Font

/** Fonts: Lilita One for big friendly display text, Poppins for everything else (supports Devanagari too). */
class Fonts(val display: FontFamily, val body: FontFamily)

val LocalFonts = staticCompositionLocalOf<Fonts> { error("no fonts") }
val LocalApp = staticCompositionLocalOf<AppContext> { error("no app") }

@Composable
fun ProvideLux(app: AppContext, content: @Composable () -> Unit) {
    val display = FontFamily(Font(Res.font.lilita_one))
    val body = FontFamily(Font(Res.font.poppins_semibold, FontWeight.SemiBold), Font(Res.font.poppins_bold, FontWeight.Bold), Font(Res.font.poppins_extrabold, FontWeight.ExtraBold))
    val fonts = remember(display, body) { Fonts(display, body) }
    CompositionLocalProvider(LocalFonts provides fonts, LocalApp provides app, content = content)
}

object Palette {
    val ink = Color(0xFF0B1B24)
    val panel = Color(0xCC0E2730)
    val panelEdge = Color(0x33FFFFFF)
    val gold = Color(0xFFFFC94A)
    val goldDeep = Color(0xFFE08A12)
    val teal = Color(0xFF2EC4B6)
    val text = Color(0xFFFDF8EC)
    val textDim = Color(0xFFB9C9CC)
}

@Composable
fun Label(text: String, size: TextUnit = 18.sp, color: Color = Palette.text, display: Boolean = false, weight: FontWeight = FontWeight.Bold, align: TextAlign? = null, modifier: Modifier = Modifier, shadow: Boolean = false) {
    val f = LocalFonts.current
    Text(
        text, modifier,
        style = TextStyle(
            color = color, fontSize = size, fontFamily = if (display) f.display else f.body, fontWeight = if (display) FontWeight.Normal else weight,
            textAlign = align ?: TextAlign.Start,
            shadow = if (shadow) Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 3f), 6f) else null,
        ),
    )
}

/** Plays the tap sound, then runs [onClick]. */
@Composable
fun tap(onClick: () -> Unit): () -> Unit {
    val app = LocalApp.current
    return { app.sfx(SfxId.UiTap, 0.7f); onClick() }
}

/** The big chunky button: a gradient face on a darker "edge", which sinks when pressed. */
@Composable
fun BigButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 64.dp,
    top: Color = Palette.gold, bottom: Color = Palette.goldDeep, textColor: Color = Color(0xFF3A1F00), textSize: TextUnit = 28.sp,
    icon: (DrawScope.(Color) -> Unit)? = null,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = 900f))
    val edge = lerp(bottom, Color.Black, 0.35f)
    val click = tap(onClick)
    Box(
        modifier.height(height).scale(1f - 0.02f * sink)
            .drawBehind {
                val r = CornerRadius(size.height / 2.4f)
                drawRoundRect(Color.Black.copy(alpha = 0.3f), Offset(0f, 8.dp.toPx()), size, r)
                drawRoundRect(edge, Offset(0f, 5.dp.toPx()), size, r)
                val y = 5.dp.toPx() * sink
                drawRoundRect(Brush.verticalGradient(listOf(lerp(top, Color.White, 0.25f), top, bottom), y, y + size.height), Offset(0f, y), size, r)
                drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(size.width * 0.06f, y + size.height * 0.1f), Size(size.width * 0.88f, size.height * 0.28f), CornerRadius(size.height / 4))
                drawRoundRect(edge.copy(alpha = 0.6f), Offset(0f, y), size, r, style = Stroke(1.5.dp.toPx()))
            }
            .clickable(src, null, onClick = click),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(top = (5 * sink).dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Canvas(Modifier.size(height * 0.42f)) { icon(textColor) }
                Spacer(Modifier.width(10.dp))
            }
            Label(text, textSize, textColor, display = true)
        }
    }
}

/** A translucent rounded panel. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.shadow(10.dp, RoundedCornerShape(22.dp), clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(Color(0xE6143642), Color(0xE60B2129))))
            .border(1.dp, Palette.panelEdge, RoundedCornerShape(22.dp))
            .padding(14.dp),
    ) { content() }
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Label(title.uppercase(), 13.sp, Palette.textDim, weight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
        content()
    }
}

/** Big segmented choice (elder-friendly: every option is a large target). */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, height: Dp = 56.dp, accent: Color = Palette.gold) {
    Row(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(18.dp)).background(Color(0x66000000)).border(1.dp, Palette.panelEdge, RoundedCornerShape(18.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (o in options) {
            val on = o == selected
            val k by animateFloatAsState(if (on) 1f else 0f, spring(stiffness = 600f))
            val click = tap { onSelect(o) }
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp))
                    .drawBehind {
                        if (k > 0.01f) {
                            drawRoundRect(Brush.verticalGradient(listOf(lerp(accent, Color.White, 0.25f).copy(alpha = k), accent.copy(alpha = k), lerp(accent, Palette.goldDeep, 0.6f).copy(alpha = k))), cornerRadius = CornerRadius(14.dp.toPx()))
                        }
                    }
                    .clickable(onClick = click),
                contentAlignment = Alignment.Center,
            ) {
                Label(label(o), 20.sp, lerp(Palette.text, Color(0xFF3A1F00), k), display = true)
            }
        }
    }
}

@Composable
fun RowScope.Chip(text: String, on: Boolean, onClick: () -> Unit, accent: Color = Palette.gold) {
    val click = tap(onClick)
    Box(
        Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(16.dp))
            .background(if (on) Brush.verticalGradient(listOf(lerp(accent, Color.White, 0.2f), accent)) else Brush.verticalGradient(listOf(Color(0x55000000), Color(0x55000000))))
            .border(if (on) 2.dp else 1.dp, if (on) Color.White.copy(alpha = 0.8f) else Palette.panelEdge, RoundedCornerShape(16.dp))
            .clickable(onClick = click),
        contentAlignment = Alignment.Center,
    ) { Label(text, 16.sp, if (on) Color(0xFF3A1F00) else Palette.text, display = true) }
}

/** On/off row with a large switch. */
@Composable
fun ToggleRow(title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    val k by animateFloatAsState(if (on) 1f else 0f, spring(stiffness = 500f))
    val click = tap { onChange(!on) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = click).padding(vertical = 10.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Label(title, 20.sp)
            if (sub != null) Label(sub, 14.sp, Palette.textDim, weight = FontWeight.SemiBold)
        }
        Canvas(Modifier.size(68.dp, 38.dp)) {
            val r = size.height / 2
            drawRoundRect(lerp(Color(0xFF55636A), Palette.teal, k), cornerRadius = CornerRadius(r))
            drawCircle(Color.Black.copy(alpha = 0.25f), r * 0.8f, Offset(r + (size.width - 2 * r) * k, r + 2.dp.toPx()))
            drawCircle(Color.White, r * 0.8f, Offset(r + (size.width - 2 * r) * k, r))
        }
    }
}

/** Round icon button (pause, settings, close). */
@Composable
fun RoundIcon(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 52.dp, draw: DrawScope.(Color) -> Unit) {
    val click = tap(onClick)
    Box(
        modifier.size(size).shadow(6.dp, CircleShape).clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF1E4A57), Color(0xFF0E2A33))))
            .border(1.5.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .clickable(onClick = click),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(size * 0.46f)) { draw(Color.White) } }
}

// ---- icons, drawn in code

fun DrawScope.pauseIcon(c: Color) {
    val w = size.width * 0.28f
    drawRoundRect(c, Offset(size.width * 0.12f, 0f), Size(w, size.height), CornerRadius(w / 3))
    drawRoundRect(c, Offset(size.width * 0.6f, 0f), Size(w, size.height), CornerRadius(w / 3))
}

fun DrawScope.gearIcon(c: Color) {
    val ctr = center
    val r = size.minDimension / 2
    for (i in 0 until 8) {
        val a = i * kotlin.math.PI.toFloat() / 4
        drawLine(c, ctr, Offset(ctr.x + kotlin.math.cos(a) * r, ctr.y + kotlin.math.sin(a) * r), r * 0.42f)
    }
    drawCircle(c, r * 0.72f, ctr)
    drawCircle(Color(0xFF14394A), r * 0.32f, ctr)
}

fun DrawScope.closeIcon(c: Color) {
    drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), size.width * 0.16f, androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), size.width * 0.16f, androidx.compose.ui.graphics.StrokeCap.Round)
}

fun DrawScope.playIcon(c: Color) {
    val p = androidx.compose.ui.graphics.Path().apply { moveTo(size.width * 0.15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.15f, size.height); close() }
    drawPath(p, c)
}

fun DrawScope.sparkIcon(c: Color) {
    drawPath(lux.board.starPath(center.x, center.y, size.minDimension / 2, size.minDimension / 7, 4, 0f), c)
}
