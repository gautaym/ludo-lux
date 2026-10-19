package lux.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Who Google says the person is: a stable account id ([sub]) plus their first name for the default display name. */
data class GoogleIdentity(val sub: String, val name: String, val email: String)

fun interface TokenVerifier {
    /** Returns the identity if [idToken] is a valid, unexpired Google ID token for this app; otherwise null. */
    fun verify(idToken: String): GoogleIdentity?
}

/**
 * Checks Google ID tokens ("Sign in with Google") without any third-party service or SDK: the token's RS256
 * signature is verified against Google's published public keys (fetched and cached), then issuer, audience
 * (our OAuth client ids) and expiry are checked. Works in every country where Google sign-in works.
 */
class GoogleVerifier(
    private val audiences: Set<String>,
    private val keySource: () -> Map<String, PublicKey> = ::fetchGoogleKeys,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : TokenVerifier {
    private val log = LoggerFactory.getLogger("google")
    private val keys = ConcurrentHashMap<String, PublicKey>()
    @Volatile private var fetchedAt = 0L

    override fun verify(idToken: String): GoogleIdentity? = runCatching {
        val parts = idToken.split('.')
        if (parts.size != 3) return null
        val header = json.parseToJsonElement(String(b64(parts[0]))).jsonObject
        val payload = json.parseToJsonElement(String(b64(parts[1]))).jsonObject
        if (header.str("alg") != "RS256") return null
        val key = key(header.str("kid") ?: return null) ?: return null
        val sig = Signature.getInstance("SHA256withRSA").apply { initVerify(key); update("${parts[0]}.${parts[1]}".toByteArray()) }
        if (!sig.verify(b64(parts[2]))) return null
        if (payload.str("iss") !in setOf("accounts.google.com", "https://accounts.google.com")) return null
        if (payload.str("aud") !in audiences) return null
        val exp = payload["exp"]?.jsonPrimitive?.long ?: return null
        if (exp < now()) return null
        val sub = payload.str("sub") ?: return null
        GoogleIdentity(sub, payload.str("given_name") ?: payload.str("name") ?: "", payload.str("email") ?: "")
    }.onFailure { log.warn("rejected Google token: ${it.message}") }.getOrNull()

    private fun key(kid: String): PublicKey? {
        keys[kid]?.let { return it }
        // Unknown key id: Google rotates keys; refresh (at most once a minute).
        val t = System.currentTimeMillis()
        if (t - fetchedAt > 60_000) {
            fetchedAt = t
            keys.putAll(keySource())
        }
        return keys[kid]
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
    private fun b64(s: String) = Base64.getUrlDecoder().decode(s)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

        /** Google's current signing keys (JWKS) as RSA public keys by key id. */
        fun fetchGoogleKeys(): Map<String, PublicKey> {
            val req = HttpRequest.newBuilder(URI("https://www.googleapis.com/oauth2/v3/certs")).timeout(Duration.ofSeconds(8)).build()
            val body = http.send(req, HttpResponse.BodyHandlers.ofString()).body()
            return parseJwks(body)
        }

        fun parseJwks(body: String): Map<String, PublicKey> {
            val kf = KeyFactory.getInstance("RSA")
            return json.parseToJsonElement(body).jsonObject["keys"]!!.jsonArray.associate { k ->
                val o = k.jsonObject
                val n = BigInteger(1, Base64.getUrlDecoder().decode(o["n"]!!.jsonPrimitive.content))
                val e = BigInteger(1, Base64.getUrlDecoder().decode(o["e"]!!.jsonPrimitive.content))
                o["kid"]!!.jsonPrimitive.content to kf.generatePublic(RSAPublicKeySpec(n, e))
            }
        }
    }
}
