package lux.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import lux.app.Nav
import lux.app.Screen
import lux.audio.SfxId
import lux.board.paintBoard
import lux.board.starPath
import lux.core.Catalog
import lux.core.net.GroupSummary
import lux.net.NetError
import lux.theme.BoardStyles

private val Gold = Color(0xFFFFC94A)
private val GoldDeep = Color(0xFFE08A12)
private val GoldInk = Color(0xFF3A1F00)

/**
 * The shop. Everything here is looks only - no item ever changes the dice or anyone's chances, and the game itself is
 * always free. The Family Pass leads: one purchase, the whole family covered.
 */
@Composable
fun ShopScreen(nav: Nav) {
    val app = LocalApp.current
    val shop = app.shop
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var groups by remember { mutableStateOf<List<GroupSummary>>(emptyList()) }
    nav.onBack = { nav.screen = Screen.Menu }

    LaunchedEffect(Unit) {
        shop.refresh()
        runCatching { shop.prices = app.platform.prices() }
        if (app.net.signedIn) runCatching { groups = app.net.groups() }
    }

    fun buy(p: Catalog.Product) {
        if (busy.isNotEmpty()) return
        busy = p.id; error = null; message = null
        scope.launch {
            try {
                if (shop.buy(p, app.platform)) {
                    app.sfx(SfxId.HomeEnter, 0.8f)
                    message = if (p.id == Catalog.FAMILY_PASS) "Family Pass is active! Choose the family group it covers below." else "${p.title} unlocked. Enjoy!"
                }
            } catch (e: NetError) { error = e.message }
            busy = ""
        }
    }

    OnlinePage("Shop", { nav.screen = Screen.Menu }) {
        Label("Looks only - nothing here changes the dice or anyone's chances.", 14.sp, Palette.textDim)
        if (!app.net.google) {
            if (app.net.signedIn) SaveAccountBanner()
            else NameStep("Buying needs an account, so your purchases are never lost.", "What name should others see?")
        }
        ErrorNote(error)
        message?.let {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xCC14532D)).padding(12.dp)) {
                Label(it, 16.sp, Color.White, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        if (shop.coveredBy.isNotEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                Label("Everything is unlocked - covered by ${shop.coveredBy}'s Family Pass.", 18.sp, Gold, display = true)
            }
        }

        // ---- the Family Pass, first and biggest
        val pass = Catalog.product(Catalog.FAMILY_PASS)!!
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF7C2D12), Color(0xFFB45309), Color(0xFF92400E))))
                .border(2.dp, Gold, RoundedCornerShape(24.dp)).padding(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(44.dp)) { familyIcon(Color.White) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Label("Family Pass", 30.sp, Color.White, display = true, shadow = true)
                        Label("${shop.price(pass)} a year", 16.sp, Color(0xFFFDE68A), display = true)
                    }
                }
                for (line in listOf(
                    "Everything unlocked for up to ${Catalog.FAMILY_PASS_SEATS} people in your family group",
                    "Every new board, piece and effect we add",
                    "One purchase - the whole family enjoys it",
                )) Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(14.dp)) { drawPath(starPath(center.x, center.y, size.minDimension / 2, size.minDimension / 4.5f, 5), Gold) }
                    Spacer(Modifier.width(8.dp))
                    Label(line, 15.sp, Color.White)
                }
                if (shop.passActive) {
                    Label("Your pass is active.", 17.sp, Color(0xFFBBF7D0), display = true)
                    Label(if (groups.isEmpty()) "Create or join a family group to share it." else "It covers this group:", 14.sp, Color(0xFFFDE68A))
                    for (g in groups) {
                        val chosen = g.id == shop.passGroupId
                        val click = tap {
                            scope.launch { try { shop.apply(app.net.setFamilyPassGroup(g.id)) } catch (e: NetError) { error = e.message } }
                        }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (chosen) Color(0x55FFFFFF) else Color(0x22000000))
                                .border(if (chosen) 2.dp else 1.dp, if (chosen) Color.White else Color(0x55FFFFFF), RoundedCornerShape(14.dp))
                                .clickable(onClick = click).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Label(g.name, 18.sp, Color.White, display = true, modifier = Modifier.weight(1f))
                            Label(if (chosen) "Covered" else "Choose", 14.sp, if (chosen) Color(0xFFBBF7D0) else Color.White, display = true)
                        }
                    }
                } else {
                    BigButton(if (busy == pass.id) "One moment..." else "Get Family Pass", { buy(pass) }, Modifier.fillMaxWidth(), 64.dp, Color.White, Color(0xFFFDE68A), GoldInk, 26.sp)
                }
            }
        }

        // ---- unlock everything (for one person)
        val all = Catalog.product(Catalog.UNLOCK_ALL)!!
        val ownsAll = Catalog.UNLOCK_ALL in shop.owned || shop.passActive || shop.coveredBy.isNotEmpty()
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(all.title, 22.sp, Color.White, display = true)
                    Label("Pay once: ${all.description.lowercase()}", 14.sp, Palette.textDim)
                }
                Spacer(Modifier.width(10.dp))
                if (ownsAll) Label("Owned", 18.sp, Color(0xFF86EFAC), display = true)
                else BigButton(if (busy == all.id) "..." else shop.price(all), { buy(all) }, Modifier.width(120.dp), 54.dp, Gold, GoldDeep, GoldInk, 20.sp)
            }
        }

        // ---- single items
        Section("Boards and effects") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (p in Catalog.products.filter { it.unlocks.isNotEmpty() }) {
                    val owned = p.unlocks.all { shop.unlocked(it) }
                    Panel(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ItemPreview(p)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Label(p.title, 20.sp, Color.White, display = true)
                                Label(p.description, 14.sp, Palette.textDim)
                            }
                            Spacer(Modifier.width(10.dp))
                            if (owned) Label("Owned", 18.sp, Color(0xFF86EFAC), display = true)
                            else BigButton(if (busy == p.id) "..." else shop.price(p), { buy(p) }, Modifier.width(110.dp), 52.dp, Gold, GoldDeep, GoldInk, 19.sp)
                        }
                    }
                }
            }
        }
        Label("Purchases are made through Google Play and stay with your Google account on every phone.", 12.sp, Palette.textDim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ItemPreview(p: Catalog.Product) {
    val px = with(LocalDensity.current) { 72.dp.roundToPx() }
    val board = p.unlocks.firstOrNull { it.startsWith("board:") }?.removePrefix("board:")
    if (board != null) {
        val img = remember(board, px) { paintBoard(BoardStyles.get(board), px) }
        Image(img, p.title, Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)), filterQuality = FilterQuality.High)
    } else {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF1E3A8A), Color(0xFF0B1E4A)))), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(52.dp)) { swordIcon() }
        }
    }
}

/** A small sword for the action-captures pack. */
private fun DrawScope.swordIcon() {
    val w = size.width; val h = size.height
    val blade = Path().apply { moveTo(w * 0.78f, h * 0.08f); lineTo(w * 0.9f, h * 0.1f); lineTo(w * 0.42f, h * 0.6f); lineTo(w * 0.36f, h * 0.54f); close() }
    drawPath(blade, Brush.linearGradient(listOf(Color.White, Color(0xFF9CA3AF)), Offset(w * 0.4f, h * 0.6f), Offset(w * 0.9f, h * 0.1f)))
    drawLine(Color(0xFFFFC94A), Offset(w * 0.26f, h * 0.5f), Offset(w * 0.5f, h * 0.74f), w * 0.09f, StrokeCap.Round)
    drawLine(Color(0xFF7C2D12), Offset(w * 0.38f, h * 0.62f), Offset(w * 0.2f, h * 0.8f), w * 0.08f, StrokeCap.Round)
    drawCircle(Color(0xFFFFC94A), w * 0.06f, Offset(w * 0.17f, h * 0.83f))
}

/** Three people (the family). */
private fun DrawScope.familyIcon(c: Color) {
    val w = size.width; val h = size.height
    for ((x, s) in listOf(0.25f to 0.8f, 0.75f to 0.8f, 0.5f to 1f)) {
        drawCircle(c, w * 0.1f * s, Offset(w * x, h * (0.32f + (1 - s) * 0.3f)))
        drawArc(c, 180f, 180f, true, Offset(w * x - w * 0.18f * s, h * (0.5f + (1 - s) * 0.3f)), androidx.compose.ui.geometry.Size(w * 0.36f * s, h * 0.5f * s))
    }
}
