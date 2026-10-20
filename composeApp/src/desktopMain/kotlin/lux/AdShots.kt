package lux

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import lux.app.AppContext
import lux.app.Nav
import lux.app.Screen
import lux.core.Ads
import lux.core.net.AdBooking
import lux.core.net.AdDecision
import lux.ui.AdDraft
import lux.ui.AdvertiseScreen
import lux.ui.BreakCard
import lux.ui.LuxApp
import lux.ui.ProvideLux
import lux.ui.SettingsOverlay
import org.jetbrains.skia.EncodedImageFormat
import java.awt.BasicStroke
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** A sample advert, drawn here so the screenshots don't depend on any real business's artwork. */
fun sampleAd(w: Int, h: Int, title: String, line: String, a: java.awt.Color, b: java.awt.Color): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    img.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        paint = GradientPaint(0f, 0f, a, w.toFloat(), h.toFloat(), b); fillRect(0, 0, w, h)
        color = java.awt.Color(255, 255, 255, 40); for (i in 0 until 6) fillOval(w - h * (i + 1) / 2, -h / 3 + i * h / 5, h, h)
        color = java.awt.Color(255, 214, 102); stroke = BasicStroke(h / 40f); drawRoundRect(h / 20, h / 20, w - h / 10, h - h / 10, h / 6, h / 6)
        val big = if (w > h * 2) h / 3 else h / 9
        font = Font(Font.SANS_SERIF, Font.BOLD, big); color = java.awt.Color.WHITE
        val fm = fontMetrics
        val ty = if (w > h * 2) h / 2 else h * 2 / 5
        drawString(title, (w - fm.stringWidth(title)) / 2, ty)
        font = Font(Font.SANS_SERIF, Font.PLAIN, big * 9 / 20); color = java.awt.Color(255, 236, 179)
        drawString(line, (w - fontMetrics.stringWidth(line)) / 2, ty + big * 3 / 4 + fm.descent)
        dispose()
    }
    return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
}

/**
 * The ad platform end to end against a running server (AUTH_MODE=test, ADMIN_TOKEN set). Two runs:
 *  - "book": a business books two ads through the real screens; the owner approves and marks them paid.
 *  - "play": with the server's day moved to the start day (ADS_TEST_DAY), a player in Karnataka sees the banners
 *    in a game and the break card, and the business sees "Running now".
 * Usage: shots -PshotArgs="ads book|play <url> <adminToken>"
 */
@OptIn(ExperimentalComposeUiApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun adShots(phase: String, server: String, adminToken: String) {
    val ui = newSingleThreadContext("ui")
    val dir = File("shots/ads").apply { if (phase == "book") deleteRecursively(); mkdirs() }
    val log = StringBuilder()
    runBlocking(ui) {
        suspend fun shot(name: String, seconds: Float = 2.5f, h: Int = 1170, content: @Composable () -> Unit) {
            ImageComposeScene(540, h, Density(1.375f), coroutineContext = ui, content = content).use { scene ->
                val start = System.nanoTime()
                while ((System.nanoTime() - start) / 1e9f < seconds) { scene.render(System.nanoTime() - start); delay(16) }
                File(dir, "$name.png").writeBytes(scene.render(System.nanoTime() - start).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
        // The business (a hotel owner) on their phone.
        val shopPlatform = TestPlatform().also { it.prefs.putString("userName", "annapoorna") }
        val shop = AppContext(shopPlatform).apply { net.changeServer(server); ads.chooseArea("IN-KA") }
        shop.net.signInWithGoogle(shopPlatform.googleIdToken()!!)
        val nav = Nav().apply { screen = Screen.Advertise }

        if (phase == "book") {
            val d = AdDraft()
            shot("1_form", h = 3400) { ProvideLux(shop) { AdvertiseScreen(nav, d) } }
            log.appendLine("today=${d.today} area=${d.area} start=${d.start} quote=${d.quote?.total}")
            d.spot = Ads.Spot.Top; d.days = 7
            shopPlatform.picture = sampleAd(1600, 300, "Hotel Annapoorna", "South Indian meals · MG Road, Bengaluru", java.awt.Color(180, 30, 40), java.awt.Color(120, 10, 60))
            d.usePicture(shopPlatform.pickImage()!!)
            d.business = "Hotel Annapoorna"; d.contact = "98450 12345"; d.category = Ads.Category.Food
            shot("2_form_filled", h = 3400) { ProvideLux(shop) { AdvertiseScreen(nav, d) } }
            d.send(shop.net)
            log.appendLine("sent=${d.sent?.id} status=${d.sent?.status} error=${d.error}")
            shot("3_sent") { ProvideLux(shop) { AdvertiseScreen(nav, d) } }
            // A second booking: the between-games card, same days.
            d.spot = Ads.Spot.Break
            d.usePicture(sampleAd(1080, 1080, "Diwali Dhamaka!", "Sharma Electronics · 20% off TVs", java.awt.Color(20, 60, 160), java.awt.Color(110, 20, 140)))
            d.quote = shop.net.adQuote(lux.core.net.AdQuoteRequest(d.spot.name, d.area, d.start, d.days))
            d.business = "Sharma Electronics"; d.category = Ads.Category.Shop
            d.send(shop.net)
            log.appendLine("sent2=${d.sent?.id} error=${d.error}")

            // The owner reviews both (the same calls the review page makes), then the payments arrive.
            val http = shop.net.http
            val all: List<AdBooking> = http.get("$server/v1/admin/ads") { bearerAuth(adminToken) }.body()
            for (b in all.filter { it.status == "Review" }) {
                http.post("$server/v1/admin/ads/${b.id}/approve") { bearerAuth(adminToken); contentType(ContentType.Application.Json); setBody(AdDecision()) }
                http.post("$server/v1/admin/ads/${b.id}/paid") { bearerAuth(adminToken); contentType(ContentType.Application.Json); setBody(AdDecision(ref = "UPI 4417")) }
            }
            d.mine = shop.net.myAds(); d.tab = 1
            log.appendLine("mine=" + d.mine.joinToString { "${it.business}:${it.status}/${it.phase} ${it.startDay}" })
            shot("4_my_ads") { ProvideLux(shop) { AdvertiseScreen(nav, d) } }
        } else {
            // A player in Karnataka.
            val player = AppContext(TestPlatform().also { it.prefs.putString("userName", "ravi") }).apply { net.changeServer(server); ads.chooseArea("IN-KA") }
            player.ads.refresh()
            log.appendLine("ads now: " + player.ads.now)
            shot("5_settings_area") { ProvideLux(player) { SettingsOverlay {} } }
            val pnav = Nav().apply { screen = Screen.Match }
            shot("6_game_with_banners", seconds = 4f) { LuxApp(player, pnav) }
            val brk = player.ads.ad(Ads.Spot.Break)
            log.appendLine("break ad: ${brk?.first?.sponsor}")
            if (brk != null) shot("7_break_card", seconds = 1.5f) { ProvideLux(player) { BreakCard(brk.first, brk.second) {} } }
            delay(500)
            val d = AdDraft().apply { tab = 1 }
            shot("8_my_ads_live") { ProvideLux(shop) { AdvertiseScreen(nav, d) } }
            log.appendLine("mine=" + d.mine.joinToString { "${it.business}:${it.phase} seen=${it.reached}" })
        }
    }
    print(log)
    println("shots/ads ($phase): done")
}
