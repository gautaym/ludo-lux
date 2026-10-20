package lux.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lux.app.Nav
import lux.app.Screen
import lux.board.paintBoard
import lux.core.Ads
import lux.core.net.AdBooking
import lux.core.net.AdBookingRequest
import lux.core.net.AdCreative
import lux.core.net.AdQuote
import lux.core.net.AdQuoteRequest
import lux.net.NetError
import lux.theme.BoardStyles
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// ====================================================================== showing ads (players)

/** The small "Ad" tag every ad carries, so nobody mistakes it for part of the game. */
@Composable
private fun AdTag(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Label("Ad", 12.sp, Color.White)
    }
}

/** A banner above or below the board: a picture only, never clickable. */
@Composable
fun AdBanner(c: AdCreative, img: ImageBitmap, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    LaunchedEffect(c.booking) { app.ads.shown(c) }
    Box(modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp))) {
        Image(img, "Ad: ${c.sponsor}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        AdTag(Modifier.align(Alignment.TopStart).padding(4.dp))
    }
}

/**
 * The between-games card: like a short TV break, for [Ads.BREAK_SECONDS] seconds, then the next game starts by
 * itself. It can't be tapped through to anywhere.
 */
@Composable
fun BreakCard(c: AdCreative, img: ImageBitmap, onDone: () -> Unit) {
    val app = LocalApp.current
    var left by remember { mutableIntStateOf(Ads.BREAK_SECONDS) }
    val show = remember { Animatable(0f) }
    LaunchedEffect(c.booking) {
        app.ads.shown(c)
        show.animateTo(1f, tween(300))
        // Counted in frames, so it pauses with the app and works in headless renders.
        var start = -1L
        while (left > 0) withFrameNanos { n -> if (start < 0) start = n; left = Ads.BREAK_SECONDS - ((n - start) / 1_000_000_000L).toInt() }
        onDone()
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xF2051419)).clickable(remember { MutableInteractionSource() }, null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.alpha(show.value).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(20.dp))) {
                Image(img, "Ad: ${c.sponsor}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
                AdTag(Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            Label("Next game in $left", 26.sp, Palette.gold, display = true, shadow = true)
        }
    }
}

// ====================================================================== dates (India days as "yyyy-MM-dd")

private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
    val y = if (m <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468
}

private fun civil(z0: Long): Triple<Int, Int, Int> {
    val z = z0 + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = (z - era * 146097).toInt()
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return Triple((yoe + era * 400).toInt() + (if (m <= 2) 1 else 0), m, d)
}

private fun dayNumber(s: String): Long? = runCatching { s.split('-').let { daysFromCivil(it[0].toInt(), it[1].toInt(), it[2].toInt()) } }.getOrNull()
private fun dayString(n: Long) = civil(n).let { (y, m, d) -> "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}" }
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private fun niceDay(s: String): String {
    val n = dayNumber(s) ?: return s
    val (_, m, d) = civil(n)
    return "${WEEKDAYS[((n % 7 + 7 + 3) % 7).toInt()]} $d ${MONTHS[m - 1]}"
}
private fun shortDay(s: String) = dayNumber(s)?.let { civil(it).let { (_, m, d) -> "$d ${MONTHS[m - 1]}" } } ?: s

// ====================================================================== advertising (businesses)

/** A text box for the form (wider limits than names, a keyboard to suit the field). */
@Composable
private fun FormField(value: String, onChange: (String) -> Unit, placeholder: String, max: Int, keyboard: KeyboardType = KeyboardType.Text, caps: KeyboardCapitalization = KeyboardCapitalization.Words) {
    val f = LocalFonts.current
    Box(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFFDF8EC))
            .border(2.dp, Palette.gold, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Label(placeholder, 18.sp, Color(0xFF8A9499))
        BasicTextField(
            value, { onChange(it.take(max)) }, Modifier.fillMaxWidth(), singleLine = true,
            textStyle = TextStyle(color = Color(0xFF0B1B24), fontSize = 20.sp, fontFamily = f.display), cursorBrush = SolidColor(Palette.goldDeep),
            keyboardOptions = KeyboardOptions(capitalization = caps, keyboardType = keyboard),
        )
    }
}

/** A tappable row that looks like a choice box. */
@Composable
private fun ChoiceBox(text: String, onClick: () -> Unit) {
    val click = tap(onClick)
    Row(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x55000000))
            .border(2.dp, Palette.gold.copy(alpha = 0.7f), RoundedCornerShape(14.dp)).clickable(onClick = click).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(text, 20.sp, Color.White, display = true, modifier = Modifier.weight(1f))
        Label("Change", 15.sp, Palette.gold, display = true)
    }
}

/** Pick All India or a state (the list is long, so it opens over the page). [automatic] adds an "Automatic" choice. */
@Composable
fun AreaPicker(selected: String, automatic: Boolean, onPick: (String) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(remember { MutableInteractionSource() }, null, onClick = onClose), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 460.dp).padding(16.dp).clickable(remember { MutableInteractionSource() }, null) {}) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Choose area", 28.sp, Palette.gold, display = true, modifier = Modifier.weight(1f))
                    RoundIcon(onClose, size = 44.dp) { closeIcon(it) }
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val options = (if (automatic) listOf("" to "Automatic (from your internet)") else emptyList()) + Ads.areas.map { it.code to it.name }
                    for ((code, name) in options) {
                        val on = code == selected
                        val click = tap { onPick(code); onClose() }
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) Palette.gold else Color(0x33000000))
                                .clickable(onClick = click).padding(horizontal = 14.dp, vertical = 12.dp),
                        ) { Label(name, 19.sp, if (on) Color(0xFF3A1F00) else Color.White, display = true) }
                    }
                }
            }
        }
    }
}

private fun statusColor(b: AdBooking) = when {
    b.phase == "live" -> Color(0xFF15803D)
    b.status == "Review" -> Color(0xFFB45309)
    b.status == "Payment" -> Color(0xFF1D4ED8)
    b.status == "Scheduled" -> Color(0xFF0F766E)
    else -> Color(0xFF475569)
}

/** Everything typed into the booking form, kept outside the screen so it survives a rotation (and can be filled in tests). */
@OptIn(ExperimentalEncodingApi::class)
class AdDraft {
    var tab by mutableIntStateOf(0)
    var today by mutableStateOf("")
    var area by mutableStateOf(Ads.NATIONAL)
    var spot by mutableStateOf(Ads.Spot.Top)
    var start by mutableStateOf("")
    var days by mutableIntStateOf(7)
    var picture by mutableStateOf<ByteArray?>(null)
    var preview by mutableStateOf<ImageBitmap?>(null)
    var business by mutableStateOf("")
    var contact by mutableStateOf("")
    var gstin by mutableStateOf("")
    var category by mutableStateOf<Ads.Category?>(null)
    var quote by mutableStateOf<AdQuote?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var sent by mutableStateOf<AdBooking?>(null)
    var mine by mutableStateOf<List<AdBooking>>(emptyList())

    val firstDay get() = dayNumber(today)?.plus(Ads.MIN_LEAD_DAYS)

    fun usePicture(bytes: ByteArray) { picture = bytes; preview = bytes.decodeToImageBitmap() }

    val ready get() = picture != null && category != null && business.isNotBlank() && contact.isNotBlank() &&
        quote?.let { it.taken.isEmpty() && it.problem.isEmpty() } == true

    /** Sends the booking for review. */
    suspend fun send(net: lux.net.LuxClient) {
        val pic = picture ?: run { error = "Please choose a picture."; return }
        val cat = category ?: run { error = "Please choose what the ad is for."; return }
        if (business.trim().length < 2) { error = "Please enter your business name."; return }
        if (contact.trim().length < 6) { error = "Please enter a phone number or email."; return }
        busy = true; error = null
        try {
            sent = net.bookAd(AdBookingRequest(spot.name, area, start, days, business.trim(), cat.name, contact.trim(), gstin.trim(), Base64.encode(pic)))
            mine = runCatching { net.myAds() }.getOrDefault(mine)
            picture = null; preview = null
        } catch (e: NetError) { error = e.message }
        busy = false
    }
}

/**
 * For businesses: show your picture to Ludo Lux players in a state or across India, for the days you choose, at a
 * fixed price. Every ad is checked by a person first; payment is arranged after approval.
 */
@Composable
fun AdvertiseScreen(nav: Nav, d: AdDraft = remember { AdDraft() }) {
    val app = LocalApp.current
    val net = app.net
    val scope = rememberCoroutineScope()
    nav.onBack = { nav.screen = Screen.Menu }
    var picking by remember { mutableStateOf(false) }
    var areaOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (d.today.isEmpty()) {
            if (app.ads.now?.today.isNullOrEmpty()) app.ads.refresh()
            d.today = app.ads.now?.today.orEmpty()
            app.ads.now?.area?.let { if (Ads.area(it) != null) d.area = it }
        }
        if (d.today.isEmpty()) d.error = "Can't reach the Ludo Lux server. Check the internet and try again."
        if (net.signedIn) runCatching { d.mine = net.myAds() }
    }
    val firstDay = d.firstDay
    if (d.start.isEmpty() && firstDay != null) d.start = dayString(firstDay)
    LaunchedEffect(d.spot, d.area, d.start, d.days) {
        d.quote = null
        if (d.start.isEmpty()) return@LaunchedEffect
        delay(150)
        d.quote = runCatching { net.adQuote(AdQuoteRequest(d.spot.name, d.area, d.start, d.days)) }.getOrNull()
    }

    Box(Modifier.fillMaxSize()) {
        OnlinePage("Advertise", { nav.screen = Screen.Menu }) {
            Segmented(listOf(0, 1), d.tab, { if (it == 0) "New ad" else "My ads" + if (d.mine.isNotEmpty()) " (${d.mine.size})" else "" }, { d.tab = it })
            ErrorNote(d.error)
            if (!net.google) {
                if (net.signedIn) SaveAccountBanner()
                else NameStep("Advertising needs an account, so we can always reach you about your ad.", "Your name")
            }

            if (d.tab == 1) {
                if (d.mine.isEmpty()) Panel(Modifier.fillMaxWidth()) { Label("No ads yet. Book your first one in \"New ad\".", 17.sp, Palette.textDim) }
                for (b in d.mine) MyAdCard(b, onCancel = {
                    scope.launch { try { net.cancelAd(b.id); d.mine = net.myAds() } catch (e: NetError) { d.error = e.message } }
                })
                return@OnlinePage
            }

            d.sent?.let { b ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xCC14532D)).padding(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Label("Thank you! Your ad is booked for review.", 20.sp, Color.White, display = true)
                        Label("We check every ad within a day. Once it's approved we'll contact you at ${d.contact.ifEmpty { "your number" }} to pay ₹${b.total} by UPI or bank transfer (with a GST invoice). It runs only after payment. Follow it under \"My ads\".", 15.sp, Color(0xFFDCFCE7))
                    }
                }
            }

            Panel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("Show your business to Ludo Lux players", 21.sp, Palette.gold, display = true)
                    Label("Like a TV ad or a hoarding: everyone playing in the area you choose sees your picture, for the days you choose, at a fixed price. Pictures only - no links, no tracking of anyone.", 15.sp, Palette.text)
                }
            }

            Section("1. Where") { ChoiceBox(Ads.area(d.area)?.name ?: d.area) { areaOpen = true } }

            Section("2. Which spot") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in Ads.Spot.entries) {
                        val on = s == d.spot
                        val click = tap { d.spot = s }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) Color(0x55FFC94A) else Color(0x33000000))
                                .border(if (on) 2.dp else 1.dp, if (on) Palette.gold else Palette.panelEdge, RoundedCornerShape(14.dp)).clickable(onClick = click).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SpotSketch(s, 46.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Label(s.title, 19.sp, Color.White, display = true)
                                Label(s.description, 13.sp, Palette.textDim)
                            }
                            Label("₹${Ads.dayPrice(s, Ads.area(d.area) ?: Ads.areas[0])}/day", 15.sp, Palette.gold, display = true)
                        }
                    }
                }
            }

            Section("3. When") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (firstDay != null) {
                        val choices = (0 until 12).map { dayString(firstDay + it) }
                        for (row in choices.chunked(4)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (day in row) Chip(niceDay(day), day == d.start, { d.start = day })
                        }
                    }
                    Label("For how many days", 14.sp, Palette.textDim)
                    Segmented(Ads.DAY_CHOICES, d.days, { if (it == 1) "1 day" else "$it" }, { d.days = it })
                }
            }

            Section("4. Your picture") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("Best size: ${d.spot.w} x ${d.spot.h} pixels. Any picture works - we keep the middle part, exactly as shown here.", 14.sp, Palette.textDim)
                    AdPreview(d.spot, d.preview)
                    BigButton(if (picking) "One moment..." else if (d.picture == null) "Choose picture" else "Change picture", {
                        if (picking) return@BigButton
                        picking = true; d.error = null
                        scope.launch {
                            try { app.platform.pickImage()?.let { d.usePicture(it) } }
                            catch (e: NetError) { d.error = e.message } catch (e: Exception) { d.error = "That picture could not be read. Please try another." }
                            picking = false
                        }
                    }, Modifier.fillMaxWidth(), 56.dp, Color(0xFF7DD3FC), Color(0xFF2563EB), Color(0xFF0B1E4A), 21.sp)
                }
            }

            Section("5. Your business") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormField(d.business, { d.business = it }, "Business name", 60)
                    FormField(d.contact, { d.contact = it }, "Phone or email (for payment)", 80, KeyboardType.Email, KeyboardCapitalization.None)
                    FormField(d.gstin, { d.gstin = it.uppercase() }, "GSTIN (if you have one)", 15, caps = KeyboardCapitalization.Characters)
                    Label("What is the ad for?", 14.sp, Palette.textDim)
                    for (row in Ads.allowedCategories.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in row) Chip(c.title, c == d.category, { d.category = c })
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Label("Not accepted: " + Ads.Category.entries.filter { it.blocked }.joinToString(", ") { it.title.lowercase() } + ".", 13.sp, Palette.textDim)
                }
            }

            PricePanel(d.quote, d.days)

            val ready = d.ready && net.google
            BigButton(if (d.busy) "Sending..." else "Send for review", { if (!d.busy) scope.launch { d.send(net) } }, Modifier.fillMaxWidth().alpha(if (ready) 1f else 0.55f), 66.dp, textSize = 28.sp)
            Label("Payment only after we approve your ad. Ads that are misleading or not suitable for families are refused.", 12.sp, Palette.textDim, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (areaOpen) AreaPicker(d.area, automatic = false, onPick = { d.area = it }, onClose = { areaOpen = false })
    }
}

/** A tiny phone sketch with the spot highlighted. */
@Composable
private fun SpotSketch(s: Ads.Spot, size: Dp) {
    Canvas(Modifier.size(size * 0.6f, size)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(Color(0xFF0B2129), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.15f))
        drawRect(Color(0xFF475569), androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.3f), androidx.compose.ui.geometry.Size(w * 0.8f, w * 0.8f))
        val gold = Palette.gold
        when (s) {
            Ads.Spot.Top -> drawRect(gold, androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.1f), androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.12f))
            Ads.Spot.Bottom -> drawRect(gold, androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.78f), androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.12f))
            Ads.Spot.Break -> drawRect(gold, androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.25f), androidx.compose.ui.geometry.Size(w * 0.8f, w * 0.8f))
        }
    }
}

/** How the ad will look in the game (cut exactly like the server cuts it: the middle part). */
@Composable
private fun AdPreview(spot: Ads.Spot, img: ImageBitmap?) {
    val app = LocalApp.current
    val boardPx = with(LocalDensity.current) { 170.dp.roundToPx() }
    val board = remember(boardPx) { paintBoard(BoardStyles.get(app.settings.board), boardPx) }
    val pic: @Composable (Modifier) -> Unit = { m ->
        Box(m.clip(RoundedCornerShape(6.dp)).background(Color(0x33FFFFFF)).border(1.dp, Palette.gold, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            if (img != null) Image(img, "Your ad", Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
            else Label("Your picture", 12.sp, Palette.gold)
            AdTag(Modifier.align(Alignment.TopStart).padding(2.dp))
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(200.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF06161B)).border(3.dp, Color(0xFF334155), RoundedCornerShape(22.dp)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (spot == Ads.Spot.Break) {
                pic(Modifier.fillMaxWidth().aspectRatio(1f))
                Label("Next game in 4", 14.sp, Palette.gold, display = true)
            } else {
                if (spot == Ads.Spot.Top) pic(Modifier.fillMaxWidth().aspectRatio(spot.ratio))
                Image(board, null, Modifier.size(170.dp), filterQuality = FilterQuality.High)
                if (spot == Ads.Spot.Bottom) pic(Modifier.fillMaxWidth().aspectRatio(spot.ratio))
            }
        }
    }
}

@Composable
private fun PricePanel(q: AdQuote?, days: Int) {
    Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (q == null) { Label("Working out the price...", 16.sp, Palette.textDim); return@Column }
            if (q.problem.isNotEmpty()) { Label(q.problem, 16.sp, Color(0xFFFCA5A5)); return@Column }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("₹${q.perDay} a day x $days ${if (days == 1) "day" else "days"}" + if (q.price < q.perDay * days) "  (longer-booking discount)" else "", 14.sp, Palette.textDim)
                    Label("₹${q.price} + GST ₹${q.gst}", 16.sp, Palette.text)
                }
                Label("₹${q.total}", 34.sp, Palette.gold, display = true)
            }
            if (q.playersPerDay > 0) Label("About ${q.playersPerDay} players a day play in this area.", 14.sp, Palette.text)
            if (q.taken.isNotEmpty()) Label("Already booked by someone else: " + q.taken.joinToString(", ") { shortDay(it) } + ". Please pick other days.", 15.sp, Color(0xFFFCA5A5))
        }
    }
}

@Composable
private fun MyAdCard(b: AdBooking, onCancel: () -> Unit) {
    val app = LocalApp.current
    var img by remember(b.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(b.id) { img = runCatching { app.net.bytes(b.image).decodeToImageBitmap() }.getOrNull() }
    Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(b.business, 20.sp, Color.White, display = true, modifier = Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(50)).background(statusColor(b)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Label(when (b.phase) { "live" -> "Running now"; "upcoming" -> "Paid - starts soon"; "ended" -> "Finished"; else -> b.statusTitle }, 13.sp, Color.White)
                }
            }
            img?.let { Image(it, null, Modifier.fillMaxWidth().aspectRatio(if (b.spot == "Break") 1.6f else Ads.Spot.Top.ratio).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
            val spotTitle = Ads.Spot.entries.firstOrNull { it.name == b.spot }?.title ?: b.spot
            Label("$spotTitle  ·  ${b.areaName}", 15.sp, Palette.text)
            Label("${niceDay(b.startDay)} for ${b.days} ${if (b.days == 1) "day" else "days"}  ·  ₹${b.total} incl. GST", 14.sp, Palette.textDim)
            if (b.status == "Scheduled") Label("Seen by ${b.reached} players so far", 16.sp, Palette.gold, display = true)
            if (b.note.isNotEmpty()) Label(b.note, 14.sp, if (b.status == "Rejected") Color(0xFFFCA5A5) else Palette.textDim)
            if (b.status == "Review" || b.status == "Payment") {
                BigButton("Cancel booking", onCancel, Modifier.fillMaxWidth(0.6f), 46.dp, Color(0xFFE2E8F0), Color(0xFF94A3B8), Color(0xFF1E293B), 17.sp)
            }
        }
    }
}
