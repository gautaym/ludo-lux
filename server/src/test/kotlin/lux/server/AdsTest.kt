package lux.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import lux.core.Ads
import lux.core.Catalog
import lux.core.net.AdBooking
import lux.core.net.AdBookingRequest
import lux.core.net.AdDecision
import lux.core.net.AdQuote
import lux.core.net.AdQuoteRequest
import lux.core.net.AdsNow
import lux.core.net.AuthResponse
import lux.core.net.GoogleSignInRequest
import lux.core.net.GuestRequest
import lux.core.net.PurchaseRequest
import lux.core.net.Wire
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdsTest {
    /** A clock the test can move forward (India time). */
    private class TestClock(var day: LocalDate) : Clock() {
        override fun getZone(): ZoneId = INDIA
        override fun withZone(zone: ZoneId?) = this
        override fun instant(): Instant = day.atTime(12, 0).atZone(INDIA).toInstant()
    }

    private val admin = "admin-token-for-tests-only-123"
    private val clock = TestClock(LocalDate.parse("2026-11-01"))

    private fun ads(block: suspend (HttpClient) -> Unit) = testApplication {
        val db = File.createTempFile("lux-ads", ".db").apply { deleteOnExit() }
        val images = Files.createTempDirectory("lux-ad-images")
        // Every test player is in Karnataka unless the app says otherwise.
        val cfg = AdsConfig(admin, images, AreaLocator { "IN-KA" }, clock = clock)
        val google = TokenVerifier { t -> if (t.startsWith("test:")) t.split(':').let { GoogleIdentity(it[1], it[2], "") } else null }
        application { luxServer(Store(db.path), Config(pace = 0.0, tickMs = 5), google, TestBilling, cfg) }
        block(createClient { install(ContentNegotiation) { json(Wire.json) } })
    }

    private suspend fun HttpClient.google(sub: String, name: String): AuthResponse =
        post("/v1/auth/google") { contentType(ContentType.Application.Json); setBody(GoogleSignInRequest("test:$sub:$name")) }.body()

    private fun picture(w: Int, h: Int): String {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { color = java.awt.Color(200, 30, 30); fillRect(0, 0, w, h); color = java.awt.Color.WHITE; fillRect(w / 3, h / 3, w / 3, h / 3); dispose() }
        return Base64.getEncoder().encodeToString(ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray())
    }

    private fun booking(spot: String = "Top", area: String = "IN-KA", start: String = "2026-11-05", days: Int = 3, category: String = "Food", image: String = picture(1500, 300)) =
        AdBookingRequest(spot, area, start, days, "Hotel Annapoorna", category, "98450 12345", "", image)

    private suspend fun HttpClient.book(token: String, req: AdBookingRequest): HttpResponse =
        post("/v1/ads/bookings") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(req) }

    private suspend fun HttpClient.decide(id: String, action: String, d: AdDecision = AdDecision(), token: String = admin): HttpResponse =
        post("/v1/admin/ads/$id/$action") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(d) }

    private suspend fun HttpClient.now(token: String? = null, area: String? = null): AdsNow =
        get("/v1/ads" + (area?.let { "?area=$it" } ?: "")) { token?.let { bearerAuth(it) } }.body()

    @Test fun aBookingIsReviewedPaidAndThenShownOnlyInItsArea() = ads { http ->
        val shop = http.google("g-annapoorna", "Ravi")
        val q: AdQuote = http.post("/v1/ads/quote") { contentType(ContentType.Application.Json); setBody(AdQuoteRequest("Top", "IN-KA", "2026-11-05", 3)) }.body()
        assertEquals(Ads.quote(Ads.Spot.Top, Ads.area("IN-KA")!!, 3).total, q.total)
        assertTrue(q.taken.isEmpty())

        val b: AdBooking = http.book(shop.token, booking()).body()
        assertEquals("Review", b.status)

        // The same days can't be sold twice: not in Karnataka, and not All India (which covers Karnataka).
        val rival = http.google("g-rival", "Rival")
        assertEquals(HttpStatusCode.Conflict, http.book(rival.token, booking(start = "2026-11-07")).status)
        assertEquals(HttpStatusCode.Conflict, http.book(rival.token, booking(area = Ads.NATIONAL, start = "2026-11-06")).status)
        val taken: AdQuote = http.post("/v1/ads/quote") { contentType(ContentType.Application.Json); setBody(AdQuoteRequest("Top", Ads.NATIONAL, "2026-11-06", 3)) }.body()
        assertEquals(listOf("2026-11-06", "2026-11-07"), taken.taken)
        // Another state, or another spot, is free.
        assertEquals(HttpStatusCode.OK, http.book(rival.token, booking(area = "IN-MH")).status)
        assertEquals(HttpStatusCode.OK, http.book(rival.token, booking(spot = "Bottom")).status)

        // Only the owner can review; nothing shows before approval and payment.
        assertEquals(HttpStatusCode.NotFound, http.decide(b.id, "approve", token = "wrong-token-wrong-token-1").status)
        clock.day = LocalDate.parse("2026-11-03")
        assertEquals(HttpStatusCode.BadRequest, http.decide(b.id, "reject").status, "a rejection needs a reason")
        assertEquals(HttpStatusCode.OK, http.decide(b.id, "approve").status)
        assertEquals(HttpStatusCode.BadRequest, http.decide(b.id, "paid").status, "payment needs a reference")
        assertEquals("Scheduled", http.decide(b.id, "paid", AdDecision(ref = "UPI 4321")).body<AdBooking>().status)
        clock.day = LocalDate.parse("2026-11-04")
        assertTrue(http.now().ads.none { it.booking == b.id }, "not before its first day")

        clock.day = LocalDate.parse("2026-11-05")
        val here = http.now()
        assertEquals("IN-KA", here.area)
        val ad = here.ads.single { it.spot == "Top" }
        assertEquals(b.id, ad.booking)
        assertEquals("2026-11-07", ad.until)
        assertTrue(http.now(area = "IN-MH").ads.none { it.booking == b.id }, "Maharashtra doesn't see a Karnataka ad")

        // The picture was cut to the banner's shape.
        val img = ImageIO.read(http.get(ad.image).readRawBytes().inputStream())
        assertEquals(Ads.Spot.Top.w to Ads.Spot.Top.h, img.width to img.height)

        // Shown to two different players (one twice): seen by 2.
        val p1 = http.google("g-p1", "P1")
        repeat(2) { http.post("/v1/ads/seen") { bearerAuth(p1.token); contentType(ContentType.Application.Json); setBody(listOf(b.id)) } }
        http.post("/v1/ads/seen") { header("X-Lux-Install", "install-abcdef-12"); contentType(ContentType.Application.Json); setBody(listOf(b.id)) }
        val mine: List<AdBooking> = http.get("/v1/ads/bookings") { bearerAuth(shop.token) }.body()
        assertEquals(2, mine.single { it.id == b.id }.reached)
        assertEquals("live", mine.single { it.id == b.id }.phase)
        assertEquals("", mine.single().contact, "contact details are only for the reviewer")

        clock.day = LocalDate.parse("2026-11-08")
        assertTrue(http.now().ads.none { it.booking == b.id }, "gone after its last day")
    }

    @Test fun bookingsAreChecked() = ads { http ->
        val guest: AuthResponse = http.post("/v1/auth/guest") { contentType(ContentType.Application.Json); setBody(GuestRequest("Guest")) }.body()
        assertEquals(HttpStatusCode.Forbidden, http.book(guest.token, booking()).status, "advertisers save their account first")
        val u = http.google("g-shop", "Shop")
        val blocked = http.book(u.token, booking(category = "Alcohol"))
        assertEquals(HttpStatusCode.BadRequest, blocked.status)
        assertTrue("banned" in blocked.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, http.book(u.token, booking(category = "Political")).status)
        assertEquals(HttpStatusCode.BadRequest, http.book(u.token, booking(image = Base64.getEncoder().encodeToString("not a picture".toByteArray()))).status)
        assertEquals(HttpStatusCode.BadRequest, http.book(u.token, booking(image = picture(300, 50))).status, "too small")
        assertEquals(HttpStatusCode.BadRequest, http.book(u.token, booking(start = "2026-11-01")).status, "not today: there is no time to review")
        assertEquals(HttpStatusCode.BadRequest, http.book(u.token, booking(days = 31)).status)
        assertEquals(HttpStatusCode.BadRequest, http.book(u.token, booking().copy(gstin = "12345")).status)
        // A square picture is fine for the between-games card, and a wide one is cropped to fit.
        assertEquals(HttpStatusCode.OK, http.book(u.token, booking(spot = "Break", image = picture(1600, 1100))).status)
    }

    @Test fun familyPassPlayersSeeNoAds() = ads { http ->
        val shop = http.google("g-shop", "Shop")
        val b: AdBooking = http.book(shop.token, booking(area = Ads.NATIONAL)).body()
        http.decide(b.id, "approve")
        val listed: List<AdBooking> = http.get("/v1/admin/ads") { bearerAuth(admin) }.body()
        assertEquals("98450 12345", listed.single().contact, "the reviewer sees the contact details")

        val mom = http.google("g-mom", "Mom")
        http.post("/v1/purchases") { bearerAuth(mom.token); contentType(ContentType.Application.Json); setBody(PurchaseRequest(Catalog.FAMILY_PASS, "test:pass")) }
        assertTrue(http.now(mom.token).adFree)
        assertTrue(!http.now().adFree)
    }

    @Test fun theSweepCancelsBookingsNotReadyByTheirStart() {
        val db = File.createTempFile("lux-sweep", ".db").apply { deleteOnExit() }
        val store = Store(db.path)
        val (u, _) = store.createUser("Shop", "g-shop")
        val start = LocalDate.parse("2026-11-05")
        val review = store.createBooking(AdRow("", u.id, Ads.Spot.Top, "IN-KA", start, 2, "A", "Food", "x", "", "a.jpg", 100, 18, Ads.Status.Review))!!
        val unpaid = store.createBooking(AdRow("", u.id, Ads.Spot.Bottom, "IN-KA", start, 2, "B", "Food", "x", "", "b.jpg", 100, 18, Ads.Status.Payment))!!
        val paid = store.createBooking(AdRow("", u.id, Ads.Spot.Break, "IN-KA", start, 2, "C", "Food", "x", "", "c.jpg", 100, 18, Ads.Status.Scheduled))!!
        val cfg = AdsConfig(clock = TestClock(LocalDate.parse("2026-11-04")))
        adSweep(store, cfg)
        assertEquals(Ads.Status.Review, store.booking(review.id)!!.status, "still in time")
        adSweep(store, AdsConfig(clock = TestClock(start)))
        assertEquals(Ads.Status.Cancelled, store.booking(review.id)!!.status)
        assertEquals(Ads.Status.Cancelled, store.booking(unpaid.id)!!.status)
        assertEquals(Ads.Status.Scheduled, store.booking(paid.id)!!.status)
        assertTrue(store.takenDays(Ads.Spot.Top, "IN-KA", start, 2).isEmpty(), "the cancelled days can be sold again")
    }

    @Test fun statesAreFoundByName() {
        assertEquals("IN-KA", GeoDbLocator.stateByName("Karnataka"))
        assertEquals("IN-DL", GeoDbLocator.stateByName("National Capital Territory of Delhi"))
        assertEquals("IN-OR", GeoDbLocator.stateByName("Orissa"))
        assertEquals("IN-JK", GeoDbLocator.stateByName("Jammu & Kashmir"))
        assertEquals(null, GeoDbLocator.stateByName("Bavaria"))
    }
}
