package lux.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import lux.core.Ads
import lux.core.net.AdBooking
import lux.core.net.AdBookingRequest
import lux.core.net.AdCreative
import lux.core.net.AdDecision
import lux.core.net.AdQuote
import lux.core.net.AdQuoteRequest
import lux.core.net.AdsNow
import org.slf4j.LoggerFactory
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")

/**
 * Finds the rough area of an internet address: "IN-KA" (a state), "IN" (somewhere in India), "" (outside India),
 * or null (unknown). Only the area is used, never stored with the address.
 */
fun interface AreaLocator { fun areaOf(ip: String): String? }

/**
 * Area from a free IP-location database file (.mmdb): DB-IP "IP to City Lite" (CC BY 4.0, monthly updates) or
 * MaxMind GeoLite2 City. Read on this machine - no address is ever sent to anyone.
 */
class GeoDbLocator(file: Path) : AreaLocator {
    private val reader = com.maxmind.db.Reader(file.toFile())

    @Suppress("UNCHECKED_CAST")
    override fun areaOf(ip: String): String? = runCatching {
        val rec = reader.get(InetAddress.getByName(ip), Map::class.java) as? Map<String, Any?> ?: return null
        val country = (rec["country"] as? Map<String, Any?>)?.get("iso_code") as? String ?: return null
        if (country != "IN") return ""
        val sub = (rec["subdivisions"] as? List<Map<String, Any?>>)?.firstOrNull() ?: return Ads.NATIONAL
        val iso = sub["iso_code"] as? String
        if (iso != null) Ads.area("IN-$iso")?.code?.let { return it }
        val name = ((sub["names"] as? Map<String, Any?>)?.get("en") as? String).orEmpty()
        stateByName(name) ?: Ads.NATIONAL
    }.getOrNull()

    companion object {
        private fun norm(s: String) = s.lowercase().replace("&", "and").filter { it.isLetter() }
        private val aliases = mapOf(
            "nationalcapitalterritoryofdelhi" to "IN-DL", "orissa" to "IN-OR", "pondicherry" to "IN-PY", "uttaranchal" to "IN-UT",
            "dadraandnagarhaveli" to "IN-DH", "damananddiu" to "IN-DH", "dadraandnagarhavelianddamananddiu" to "IN-DH",
            "andamanandnicobarislands" to "IN-AN", "jammuandkashmir" to "IN-JK",
        )
        fun stateByName(name: String): String? {
            val n = norm(name)
            if (n.isEmpty()) return null
            return aliases[n] ?: Ads.areas.firstOrNull { !it.national && norm(it.name) == n }?.code
        }
    }
}

/** Ad platform settings. [adminToken] empty = the review page is switched off. */
class AdsConfig(
    val adminToken: String = "",
    val imagesDir: Path = Path.of("ad-images"),
    val locator: AreaLocator = AreaLocator { null },
    /** Family Pass holders (and the family it covers) see no banners or break cards. */
    val freeWithPass: Boolean = true,
    val clock: Clock = Clock.system(INDIA),
) {
    fun today(): LocalDate = LocalDate.now(clock)
}

/** Checks an uploaded picture and cuts it to the spot's shape: a clean JPEG, so nothing hidden in the file survives. */
object AdImages {
    private const val MAX_SIDE = 8000

    /** Returns the JPEG bytes, or throws [HttpError] with a friendly reason. */
    fun prepare(base64: String, spot: Ads.Spot): ByteArray {
        val raw = runCatching { Base64.getDecoder().decode(base64.substringAfter("base64,")) }.getOrNull()
            ?: throw HttpError(HttpStatusCode.BadRequest, "That picture could not be read.")
        if (raw.size > Ads.MAX_IMAGE_BYTES) throw HttpError(HttpStatusCode.BadRequest, "That picture is too big. Please use one under 3 MB.")
        // Read the size first, so a tiny file claiming a gigantic picture cannot exhaust memory.
        val img = ImageIO.createImageInputStream(ByteArrayInputStream(raw)).use { input ->
            val reader = input?.let { ImageIO.getImageReaders(it).asSequence().firstOrNull() }
                ?: throw HttpError(HttpStatusCode.BadRequest, "Please use a JPEG or PNG picture.")
            try {
                reader.input = input
                if (reader.getWidth(0) > MAX_SIDE || reader.getHeight(0) > MAX_SIDE) throw HttpError(HttpStatusCode.BadRequest, "That picture is too large.")
                reader.read(0)
            } catch (e: HttpError) { throw e } catch (e: Exception) {
                throw HttpError(HttpStatusCode.BadRequest, "That picture could not be read.")
            } finally { reader.dispose() }
        }
        val (x, y, w, h) = Ads.crop(spot, img.width, img.height).let { listOf(it[0], it[1], it[2], it[3]) }
        if (w < spot.w / 2 || h < spot.h / 2) throw HttpError(HttpStatusCode.BadRequest, "That picture is too small. Please use one at least ${spot.w / 2} x ${spot.h / 2} pixels.")
        val out = BufferedImage(spot.w, spot.h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            color = java.awt.Color.WHITE; fillRect(0, 0, spot.w, spot.h)
            drawImage(img, 0, 0, spot.w, spot.h, x, y, x + w, y + h, null)
            dispose()
        }
        val bytes = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        ImageIO.createImageOutputStream(bytes).use { o ->
            writer.output = o
            val p = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.88f }
            writer.write(null, IIOImage(out, null, null), p)
        }
        writer.dispose()
        return bytes.toByteArray()
    }
}

/** The ad platform's REST routes (inside the server's routing). */
fun Route.adRoutes(store: Store, ads: AdsConfig, user: (ApplicationCall) -> User, optionalUser: (ApplicationCall) -> User?, covered: (User) -> Boolean) {
    val log = LoggerFactory.getLogger("ads")
    val bookLimit = Limiter(10, 86_400_000)
    val salt = store.salt()
    Files.createDirectories(ads.imagesDir)

    fun viewer(call: ApplicationCall, u: User?): String {
        val key = u?.id ?: call.request.header("X-Lux-Install")?.take(64)?.takeIf { it.length >= 8 } ?: ("ip:" + call.request.origin.remoteHost)
        return MessageDigest.getInstance("SHA-256").digest("$salt:$key".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }

    fun dto(b: AdRow, today: LocalDate, admin: Boolean = false) = AdBooking(
        b.id, b.spot.name, b.area, Ads.area(b.area)?.name ?: b.area, b.start.toString(), b.days, b.business,
        Ads.Category.entries.firstOrNull { it.name == b.category }?.title ?: b.category, b.status.name, b.status.title,
        b.price, b.gst, b.price + b.gst, "/ads/img/${b.image}", b.note,
        phase = if (b.status != Ads.Status.Scheduled) "" else when { today.isBefore(b.start) -> "upcoming"; today.isBefore(b.end) -> "live"; else -> "ended" },
        reached = store.reach(b.id),
        contact = if (admin) b.contact else "", gstin = if (admin) b.gstin else "", paidRef = if (admin) b.paidRef else "", created = b.created,
    )

    data class Checked(val spot: Ads.Spot, val area: Ads.Area, val start: LocalDate, val days: Int)

    fun check(spot: String, area: String, startDay: String, days: Int): Checked {
        val s = Ads.Spot.entries.firstOrNull { it.name == spot } ?: throw HttpError(HttpStatusCode.BadRequest, "Please choose where the ad goes.")
        val a = Ads.area(area) ?: throw HttpError(HttpStatusCode.BadRequest, "Please choose an area.")
        val start = runCatching { LocalDate.parse(startDay) }.getOrNull() ?: throw HttpError(HttpStatusCode.BadRequest, "Please choose a start date.")
        val today = ads.today()
        if (start.isBefore(today.plusDays(Ads.MIN_LEAD_DAYS.toLong()))) throw HttpError(HttpStatusCode.BadRequest, "Ads can start from tomorrow, so we can check them first.")
        if (start.isAfter(today.plusDays(Ads.MAX_LEAD_DAYS.toLong()))) throw HttpError(HttpStatusCode.BadRequest, "Ads can be booked up to ${Ads.MAX_LEAD_DAYS} days ahead.")
        if (days !in 1..Ads.MAX_DAYS) throw HttpError(HttpStatusCode.BadRequest, "An ad can run for 1 to ${Ads.MAX_DAYS} days.")
        return Checked(s, a, start, days)
    }

    // ---- players: what to show here, today
    get("/v1/ads") {
        val u = optionalUser(call)
        val today = ads.today()
        val chosen = call.request.queryParameters["area"]?.let { Ads.area(it)?.code }
        val area = chosen ?: ads.locator.areaOf(call.request.origin.remoteHost) ?: Ads.NATIONAL
        if (area.isEmpty()) { call.respond(AdsNow("", "Outside India", today = today.toString())); return@get }      // advertisers buy India only
        val name = Ads.area(area)?.name ?: "All India"
        if (ads.freeWithPass && u != null && covered(u)) { call.respond(AdsNow(area, name, adFree = true, today = today.toString())); return@get }
        store.recordSeen(emptyList(), area, today, viewer(call, u))
        val live = store.liveBookings(today, area)
        // One ad per spot; a state's own booking comes before an All India one.
        val pick = Ads.Spot.entries.mapNotNull { s -> live.filter { it.spot == s }.minByOrNull { if (it.area == area) 0 else 1 } }
        call.respond(AdsNow(area, name, pick.map { AdCreative(it.id, it.spot.name, "/ads/img/${it.image}", it.business, it.end.minusDays(1).toString()) }, today = today.toString()))
    }
    // The app reports an ad once a day when it was really on screen (the advertiser's "seen by" count).
    post("/v1/ads/seen") {
        val u = optionalUser(call)
        val ids = call.receive<List<String>>().take(6)
        val today = ads.today()
        val valid = ids.filter { id -> store.booking(id)?.let { it.status == Ads.Status.Scheduled && !today.isBefore(it.start) && today.isBefore(it.end) } == true }
        if (valid.isNotEmpty()) store.recordSeen(valid, Ads.NATIONAL, today, viewer(call, u))
        call.respond(HttpStatusCode.NoContent)
    }
    get("/ads/img/{name}") {
        val name = call.parameters["name"]!!
        if (!Regex("[A-Za-z0-9_-]{8,40}\\.jpg").matches(name)) throw HttpError(HttpStatusCode.NotFound, "No such picture.")
        val f = ads.imagesDir.resolve(name)
        if (!Files.exists(f)) throw HttpError(HttpStatusCode.NotFound, "No such picture.")
        call.response.header("Cache-Control", "public, max-age=604800, immutable")
        call.respondBytes(Files.readAllBytes(f), ContentType.Image.JPEG)
    }

    // ---- advertisers: price, book, follow
    post("/v1/ads/quote") {
        val req = call.receive<AdQuoteRequest>()
        val c = try { check(req.spot, req.area, req.startDay, req.days) } catch (e: HttpError) { call.respond(AdQuote(0, 0, 0, 0, problem = e.message ?: "")); return@post }
        val q = Ads.quote(c.spot, c.area, c.days)
        val taken = store.takenDays(c.spot, c.area.code, c.start, c.days)
        call.respond(AdQuote(q.perDay, q.price, q.gst, q.total, taken.map { it.toString() }, store.playersPerDay(c.area.code, ads.today())))
    }
    post("/v1/ads/bookings") {
        val u = user(call)
        if (!u.google) throw HttpError(HttpStatusCode.Forbidden, "Please save your account with Google first, so we can always reach you about your ad.")
        if (!bookLimit.allow(u.id)) throw HttpError(HttpStatusCode.TooManyRequests, "That's a lot of bookings for one day. Please try again tomorrow.")
        val req = call.receive<AdBookingRequest>()
        val c = check(req.spot, req.area, req.startDay, req.days)
        val cat = Ads.Category.entries.firstOrNull { it.name == req.category } ?: throw HttpError(HttpStatusCode.BadRequest, "Please choose what the ad is for.")
        if (cat.blocked) throw HttpError(HttpStatusCode.BadRequest, "We can't show this kind of ad. ${cat.why}")
        val business = Wire2.clean(req.business, 60)
        if (business.length < 2) throw HttpError(HttpStatusCode.BadRequest, "Please enter the business name.")
        val contact = Wire2.clean(req.contact, 80)
        if (contact.length < 6) throw HttpError(HttpStatusCode.BadRequest, "Please enter a phone number or email, so we can reach you.")
        val gstin = req.gstin.trim().uppercase()
        if (gstin.isNotEmpty() && !Regex("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]").matches(gstin)) throw HttpError(HttpStatusCode.BadRequest, "That GSTIN doesn't look right. Leave it empty if you don't have one.")
        val jpeg = AdImages.prepare(req.imageBase64, c.spot)
        val q = Ads.quote(c.spot, c.area, c.days)
        val file = java.util.UUID.randomUUID().toString().replace("-", "") + ".jpg"
        Files.write(ads.imagesDir.resolve(file), jpeg)
        val row = store.createBooking(AdRow("", u.id, c.spot, c.area.code, c.start, c.days, business, cat.name, contact, gstin, file, q.price, q.gst, Ads.Status.Review))
        if (row == null) { Files.deleteIfExists(ads.imagesDir.resolve(file)); throw HttpError(HttpStatusCode.Conflict, "Someone just booked some of those days. Please pick other dates.") }
        log.info("ad booking ${row.id}: ${c.spot} ${c.area.code} ${c.start} x${c.days} by ${u.id}")
        call.respond(dto(row, ads.today()))
    }
    get("/v1/ads/bookings") {
        val u = user(call)
        val today = ads.today()
        call.respond(store.bookingsOf(u.id).map { dto(it, today) })
    }
    post("/v1/ads/bookings/{id}/cancel") {
        val u = user(call)
        val b = store.booking(call.parameters["id"]!!)?.takeIf { it.userId == u.id } ?: throw HttpError(HttpStatusCode.NotFound, "No such booking.")
        if (b.status != Ads.Status.Review && b.status != Ads.Status.Payment) throw HttpError(HttpStatusCode.Conflict, "A paid booking can't be cancelled here. Please contact us.")
        store.setBookingStatus(b.id, Ads.Status.Cancelled, "Cancelled by the advertiser.")
        call.respond(dto(store.booking(b.id)!!, ads.today()))
    }

    // ---- review (the business owner only): every ad is checked by a person before it can run
    fun admin(call: ApplicationCall) {
        val t = call.request.header("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
        if (ads.adminToken.length < 20 || !MessageDigest.isEqual(t.toByteArray(), ads.adminToken.toByteArray())) throw HttpError(HttpStatusCode.NotFound, "Not found.")
    }
    get("/admin") {
        if (ads.adminToken.length < 20) throw HttpError(HttpStatusCode.NotFound, "Not found.")
        call.respondText(ADMIN_PAGE, ContentType.Text.Html)
    }
    get("/v1/admin/ads") {
        admin(call)
        val today = ads.today()
        call.respond(store.allBookings().map { dto(it, today, admin = true) })
    }
    post("/v1/admin/ads/{id}/{action}") {
        admin(call)
        val b = store.booking(call.parameters["id"]!!) ?: throw HttpError(HttpStatusCode.NotFound, "No such booking.")
        val d = call.receive<AdDecision>()
        val (from, to) = when (call.parameters["action"]) {
            "approve" -> setOf(Ads.Status.Review) to Ads.Status.Payment
            "reject" -> setOf(Ads.Status.Review, Ads.Status.Payment) to Ads.Status.Rejected
            "paid" -> setOf(Ads.Status.Payment) to Ads.Status.Scheduled
            "cancel" -> setOf(Ads.Status.Review, Ads.Status.Payment, Ads.Status.Scheduled) to Ads.Status.Cancelled
            else -> throw HttpError(HttpStatusCode.NotFound, "Not found.")
        }
        if (b.status !in from) throw HttpError(HttpStatusCode.Conflict, "This booking is ${b.status.title.lowercase()}.")
        if (to == Ads.Status.Rejected && d.note.isBlank()) throw HttpError(HttpStatusCode.BadRequest, "Please give the advertiser a reason.")
        if (to == Ads.Status.Scheduled && d.ref.isBlank()) throw HttpError(HttpStatusCode.BadRequest, "Please enter the payment reference (UPI / bank).")
        store.setBookingStatus(b.id, to, d.note.take(300).ifBlank { null }, d.ref.take(80).ifBlank { null })
        log.info("ad booking ${b.id}: ${b.status} -> $to")
        call.respond(dto(store.booking(b.id)!!, ads.today(), admin = true))
    }
}

/** Housekeeping, run every hour: bookings not reviewed or paid by their start day free their days again. */
fun adSweep(store: Store, ads: AdsConfig) {
    val today = ads.today()
    for (b in store.allBookings()) {
        if (b.start.isAfter(today)) continue
        when (b.status) {
            Ads.Status.Review -> store.setBookingStatus(b.id, Ads.Status.Cancelled, "We could not review it in time. Sorry - nothing was charged.")
            Ads.Status.Payment -> store.setBookingStatus(b.id, Ads.Status.Cancelled, "Payment was not received before the start date.")
            else -> {}
        }
    }
    store.pruneSeen(today)
}

private object Wire2 {
    fun clean(raw: String, max: Int) = raw.trim().replace(Regex("\\s+"), " ").filter { !it.isISOControl() }.take(max)
}

/** The review page: a plain page served by this server (only reachable from the server itself, see deploy/Caddyfile). */
private val ADMIN_PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Ludo Lux ads review</title><style>
body{font-family:system-ui,sans-serif;background:#0b2129;color:#fdf8ec;margin:0;padding:16px;max-width:900px;margin:auto}
h1{color:#ffc94a}input,button{font-size:16px;padding:8px 12px;border-radius:8px;border:1px solid #456}button{cursor:pointer;background:#ffc94a;color:#3a1f00;border:0;font-weight:700;margin:2px}
button.no{background:#e2e8f0;color:#1e293b}.card{background:#13323d;border-radius:14px;padding:12px;margin:12px 0}.card img{max-width:100%;border-radius:8px;display:block;margin:8px 0}
.st{display:inline-block;padding:2px 8px;border-radius:99px;background:#334155;font-size:13px}.Review{background:#b45309}.Payment{background:#1d4ed8}.Scheduled{background:#15803d}
small{color:#a7b8bf}.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
</style></head><body><h1>Ads review</h1>
<div class="row"><input id="tok" type="password" placeholder="Admin token" size="40"><button onclick="save()">Open</button>
<label><input type="checkbox" id="all" onchange="load()"> show finished</label></div><div id="list"></div>
<script>
const T=()=>sessionStorage.getItem('t')||'';function save(){sessionStorage.setItem('t',document.getElementById('tok').value);load()}
function esc(s){return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
async function api(p,b){const r=await fetch(p,{method:b?'POST':'GET',headers:{'Authorization':'Bearer '+T(),'Content-Type':'application/json'},body:b?JSON.stringify(b):undefined});
 const j=await r.json().catch(()=>({error:'error '+r.status}));if(!r.ok)throw new Error(j.error||r.status);return j}
async function act(id,a){let b={};if(a=='reject'){b.note=prompt('Reason for the advertiser:');if(!b.note)return}
 if(a=='paid'){b.ref=prompt('Payment reference (UPI / bank):');if(!b.ref)return}if(a=='cancel'&&!confirm('Cancel this booking?'))return
 try{await api('/v1/admin/ads/'+id+'/'+a,b);load()}catch(e){alert(e.message)}}
async function load(){const el=document.getElementById('list');let xs;try{xs=await api('/v1/admin/ads')}catch(e){el.innerHTML='<p>'+esc(e.message)+'</p>';return}
 const all=document.getElementById('all').checked;xs=xs.filter(x=>all||['Review','Payment','Scheduled'].includes(x.status)&&x.phase!='ended');
 el.innerHTML=xs.length?'':'<p>Nothing to review.</p>';for(const x of xs){const d=document.createElement('div');d.className='card';
 d.innerHTML='<div class="row"><b>'+esc(x.business)+'</b><span class="st '+esc(x.status)+'">'+esc(x.statusTitle)+(x.phase?' - '+esc(x.phase):'')+'</span></div>'+
 '<small>'+esc(x.spot)+' - '+esc(x.areaName)+' - from '+esc(x.startDay)+' for '+x.days+' days - '+esc(x.category)+'</small><img src="'+esc(x.image)+'">'+
 '<div>Total Rs '+x.total+' (Rs '+x.price+' + GST Rs '+x.gst+') - contact: '+esc(x.contact)+(x.gstin?' - GSTIN '+esc(x.gstin):'')+(x.paidRef?' - paid: '+esc(x.paidRef):'')+' - seen by '+x.reached+'</div>'+
 (x.note?'<small>Note: '+esc(x.note)+'</small>':'')+'<div class="row" style="margin-top:8px"></div>';const r=d.lastChild;
 const btn=(t,a,c)=>{const b=document.createElement('button');b.textContent=t;if(c)b.className=c;b.onclick=()=>act(x.id,a);r.appendChild(b)};
 if(x.status=='Review'){btn('Approve','approve');btn('Reject','reject','no')}if(x.status=='Payment'){btn('Mark paid','paid');btn('Reject','reject','no')}
 if(['Review','Payment','Scheduled'].includes(x.status))btn('Cancel','cancel','no');el.appendChild(d)}}
if(T())load();
</script></body></html>"""
