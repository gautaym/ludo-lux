package lux.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** A purchase Google confirmed. [expires] is in epoch millis for subscriptions (0 = never). */
data class PlayPurchase(val orderId: String, val expires: Long)

/** Checks a Google Play purchase token. Returns null when it is not a valid, paid purchase of [productId]. */
interface PurchaseVerifier {
    fun verify(productId: String, token: String, subscription: Boolean): PlayPurchase?
}

/** No billing configured: every purchase is refused. */
object NoBilling : PurchaseVerifier {
    override fun verify(productId: String, token: String, subscription: Boolean): PlayPurchase? = null
}

/**
 * Test store for home testing before the Play Console is set up: any token starting with "test:" is a valid
 * purchase (subscriptions last a year). Never enable this on a public server.
 */
object TestBilling : PurchaseVerifier {
    override fun verify(productId: String, token: String, subscription: Boolean): PlayPurchase? =
        if (token.startsWith("test:")) PlayPurchase("TEST-" + token.removePrefix("test:"), if (subscription) System.currentTimeMillis() + 365L * 86_400_000 else 0) else null
}

/**
 * Real verification with the Google Play Developer API, using a service account (a JSON key file from Google Cloud,
 * granted access in the Play Console). Also acknowledges each purchase, as Google requires within 3 days.
 * Plain JDK only: the service account signs a short JWT to get an access token.
 */
class GooglePlayBilling(private val packageName: String, serviceAccountFile: Path) : PurchaseVerifier {
    private val log = LoggerFactory.getLogger("billing")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    private val account = json.parseToJsonElement(Files.readString(serviceAccountFile)).jsonObject
    private var accessToken = ""
    private var tokenExpires = 0L

    private fun s(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.content

    @Synchronized private fun token(): String {
        if (System.currentTimeMillis() < tokenExpires - 60_000) return accessToken
        val now = Instant.now().epochSecond
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = enc.encodeToString("""{"iss":"${s(account, "client_email")}","scope":"https://www.googleapis.com/auth/androidpublisher","aud":"https://oauth2.googleapis.com/token","iat":$now,"exp":${now + 3600}}""".toByteArray())
        val pem = s(account, "private_key")!!.replace(Regex("-----[A-Z ]+-----|\\s"), "")
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update("$header.$claims".toByteArray()) }.sign()
        val assertion = "$header.$claims.${enc.encodeToString(sig)}"
        val body = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") + "&assertion=" + assertion
        val res = http.send(HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/token")).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        val o = json.parseToJsonElement(res.body()).jsonObject
        accessToken = s(o, "access_token") ?: error("no access token: ${res.statusCode()}")
        tokenExpires = System.currentTimeMillis() + (o["expires_in"]?.jsonPrimitive?.int ?: 3600) * 1000L
        return accessToken
    }

    private fun call(method: String, path: String): Pair<Int, String> {
        val b = HttpRequest.newBuilder(URI("https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$packageName/$path"))
            .header("Authorization", "Bearer ${token()}").timeout(Duration.ofSeconds(10))
        val req = if (method == "POST") b.POST(HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type", "application/json").build() else b.GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        return res.statusCode() to res.body()
    }

    override fun verify(productId: String, token: String, subscription: Boolean): PlayPurchase? = runCatching {
        val t = URLEncoder.encode(token, "UTF-8")
        if (subscription) {
            val (code, body) = call("GET", "purchases/subscriptionsv2/tokens/$t")
            if (code != 200) return null
            val o = json.parseToJsonElement(body).jsonObject
            if (s(o, "subscriptionState") !in setOf("SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD")) return null
            val line = o["lineItems"]?.jsonArray?.firstOrNull { s(it.jsonObject, "productId") == productId }?.jsonObject ?: return null
            if (s(o, "acknowledgementState") != "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED") call("POST", "purchases/subscriptions/$productId/tokens/$t:acknowledge")
            PlayPurchase(s(o, "latestOrderId") ?: token.take(24), Instant.parse(s(line, "expiryTime")).toEpochMilli())
        } else {
            val (code, body) = call("GET", "purchases/products/$productId/tokens/$t")
            if (code != 200) return null
            val o = json.parseToJsonElement(body).jsonObject
            if (o["purchaseState"]?.jsonPrimitive?.int != 0) return null              // 0 = purchased
            if (o["acknowledgementState"]?.jsonPrimitive?.int != 1) call("POST", "purchases/products/$productId/tokens/$t:acknowledge")
            PlayPurchase(s(o, "orderId") ?: token.take(24), 0)
        }
    }.onFailure { log.warn("purchase check failed: ${it.message}") }.getOrNull()
}
