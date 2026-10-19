package lux.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import lux.core.net.AuthResponse
import lux.core.net.CreateGroupRequest
import lux.core.net.GoogleSignInRequest
import lux.core.net.GroupDetail
import lux.core.net.GroupSummary
import lux.core.net.GuestRequest
import lux.core.net.Wire
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleSignInTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val verifier = GoogleVerifier(setOf("test-client"), keySource = { mapOf("k1" to keys.public) })

    /** A Google-style ID token signed with [signer]. */
    private fun token(sub: String, aud: String = "test-client", exp: Long = System.currentTimeMillis() / 1000 + 600, iss: String = "https://accounts.google.com", signer: java.security.PrivateKey = keys.private, name: String = "Ravi"): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"RS256","kid":"k1","typ":"JWT"}""".toByteArray())
        val body = enc.encodeToString("""{"iss":"$iss","aud":"$aud","sub":"$sub","exp":$exp,"given_name":"$name","email":"r@example.com"}""".toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(signer); update("$header.$body".toByteArray()) }.sign()
        return "$header.$body.${enc.encodeToString(sig)}"
    }

    @Test fun onlyGenuineFreshTokensForThisAppAreAccepted() {
        assertEquals("g-1", verifier.verify(token("g-1"))?.sub)
        assertNull(verifier.verify(token("g-1", signer = other.private)), "forged signature")
        assertNull(verifier.verify(token("g-1", aud = "someone-else")), "token for another app")
        assertNull(verifier.verify(token("g-1", exp = 1000)), "expired")
        assertNull(verifier.verify(token("g-1", iss = "https://evil.example")), "wrong issuer")
        assertNull(verifier.verify("not.a.token"))
    }

    @Test fun aGuestIsUpgradedAndTheSameGoogleAccountWorksOnAnotherPhone() = testApplication {
        val db = File.createTempFile("lux", ".db").apply { deleteOnExit() }
        application { luxServer(Store(db.path), Config(pace = 0.0, tickMs = 5), verifier) }
        val http = createClient { install(ContentNegotiation) { json(Wire.json) } }

        // Phone 1: plays as a guest first and makes a group.
        val guest: AuthResponse = http.post("/v1/auth/guest") { contentType(ContentType.Application.Json); setBody(GuestRequest("Ravi")) }.body()
        assertTrue(guest.tag in 1000..9999 && !guest.google)
        http.post("/v1/groups") { bearerAuth(guest.token); contentType(ContentType.Application.Json); setBody(CreateGroupRequest("Ravi's family")) }.body<GroupDetail>()

        // Saves the account with Google: same account, nothing lost.
        val saved: AuthResponse = http.post("/v1/auth/google") { bearerAuth(guest.token); contentType(ContentType.Application.Json); setBody(GoogleSignInRequest(token("g-ravi"))) }.body()
        assertEquals(guest.userId, saved.userId)
        assertTrue(saved.google)
        assertEquals(guest.tag, saved.tag)

        // Phone 2 (new phone, no guest): signs in with the same Google account and gets the same account and groups.
        val phone2: AuthResponse = http.post("/v1/auth/google") { contentType(ContentType.Application.Json); setBody(GoogleSignInRequest(token("g-ravi"))) }.body()
        assertEquals(guest.userId, phone2.userId)
        assertNotEquals(saved.token, phone2.token, "each phone has its own session")
        val groups: List<GroupSummary> = http.get("/v1/groups") { bearerAuth(phone2.token) }.body()
        assertEquals(listOf("Ravi's family"), groups.map { it.name })
        // Both phones stay signed in.
        assertEquals(HttpStatusCode.OK, http.get("/v1/groups") { bearerAuth(guest.token) }.status)

        // Someone new straight in with Google: a fresh account named from their Google first name.
        val fresh: AuthResponse = http.post("/v1/auth/google") { contentType(ContentType.Application.Json); setBody(GoogleSignInRequest(token("g-meena", name = "Meena"))) }.body()
        assertEquals("Meena", fresh.name)
        assertNotEquals(guest.userId, fresh.userId)

        // A bad token is refused.
        assertEquals(HttpStatusCode.Unauthorized, http.post("/v1/auth/google") { contentType(ContentType.Application.Json); setBody(GoogleSignInRequest(token("g-x", signer = other.private))) }.status)
    }

    @Test fun olderDatabasesAreUpgradedAndOldGuestTokensStillWork() {
        val db = File.createTempFile("lux-old", ".db").apply { deleteOnExit() }
        val token = "old-guest-token"
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
            c.createStatement().use { st ->
                // The schema as it was before sessions, Google links and tags.
                st.execute("CREATE TABLE users(id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created INTEGER NOT NULL)")
                st.execute("INSERT INTO users VALUES('u1', 'Mom', '$hash', 0)")
            }
        }
        val store = Store(db.path)
        val mom = store.userByToken(token)
        assertEquals("u1", mom?.id)
        store.linkGoogle("u1", "g-mom")
        assertEquals("u1", store.userByGoogle("g-mom")?.id)
    }
}
