package lux.server

import io.ktor.client.HttpClient
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
import lux.core.Catalog
import lux.core.net.AuthResponse
import lux.core.net.CreateGroupRequest
import lux.core.net.Entitlements
import lux.core.net.FamilyPassRequest
import lux.core.net.GoogleSignInRequest
import lux.core.net.GroupDetail
import lux.core.net.GuestRequest
import lux.core.net.JoinGroupRequest
import lux.core.net.PurchaseRequest
import lux.core.net.Wire
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShopTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val verifier = GoogleVerifier(setOf("test-client"), keySource = { mapOf("k1" to keys.public) })

    private fun idToken(sub: String, name: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val h = enc.encodeToString("""{"alg":"RS256","kid":"k1"}""".toByteArray())
        val b = enc.encodeToString("""{"iss":"accounts.google.com","aud":"test-client","sub":"$sub","exp":${System.currentTimeMillis() / 1000 + 600},"given_name":"$name"}""".toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(keys.private); update("$h.$b".toByteArray()) }.sign()
        return "$h.$b.${enc.encodeToString(sig)}"
    }

    private suspend fun HttpClient.google(sub: String, name: String): AuthResponse =
        post("/v1/auth/google") { contentType(ContentType.Application.Json); setBody(GoogleSignInRequest(idToken(sub, name))) }.body()

    private suspend fun HttpClient.buy(token: String, product: String, purchase: String) =
        post("/v1/purchases") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(PurchaseRequest(product, purchase)) }

    private suspend fun HttpClient.ent(token: String): Entitlements = get("/v1/me/entitlements") { bearerAuth(token) }.body()

    private fun shop(block: suspend (HttpClient) -> Unit) = testApplication {
        val db = File.createTempFile("lux", ".db").apply { deleteOnExit() }
        application { luxServer(Store(db.path), Config(pace = 0.0, tickMs = 5), verifier, TestBilling) }
        block(createClient { install(ContentNegotiation) { json(Wire.json) } })
    }

    @Test fun buyingNeedsASavedAccountAndAGenuinePurchase() = shop { http ->
        val guest: AuthResponse = http.post("/v1/auth/guest") { contentType(ContentType.Application.Json); setBody(GuestRequest("Guest")) }.body()
        assertEquals(HttpStatusCode.Forbidden, http.buy(guest.token, "board_neon", "test:1").status, "guests save their account first")

        val ravi = http.google("g-ravi", "Ravi")
        assertEquals(HttpStatusCode.PaymentRequired, http.buy(ravi.token, "board_neon", "made-up-token").status, "unconfirmed purchases unlock nothing")
        assertEquals(HttpStatusCode.BadRequest, http.buy(ravi.token, "free_money", "test:2").status)
        assertTrue(http.ent(ravi.token).owned.isEmpty())

        val after: Entitlements = http.buy(ravi.token, "board_neon", "test:neon-1").body()
        assertEquals(listOf("board_neon"), after.owned)
        assertTrue(Catalog.unlocked("board:neon", after.owned.toSet()))
        // Buying again with the same token is fine (e.g. the app retrying); another account can't claim it.
        assertEquals(HttpStatusCode.OK, http.buy(ravi.token, "board_neon", "test:neon-1").status)
        val meena = http.google("g-meena", "Meena")
        assertEquals(HttpStatusCode.Conflict, http.buy(meena.token, "board_neon", "test:neon-1").status)
        // The purchase follows the account to a new phone.
        val ravi2 = http.google("g-ravi", "Ravi")
        assertEquals(listOf("board_neon"), http.ent(ravi2.token).owned)
    }

    @Test fun aFamilyPassCoversFiveMembersOfTheChosenGroup() = shop { http ->
        val mom = http.google("g-mom", "Mom")
        val family: GroupDetail = http.post("/v1/groups") { bearerAuth(mom.token); contentType(ContentType.Application.Json); setBody(CreateGroupRequest("Sharma Family")) }.body()
        val others = (1..5).map { http.google("g-$it", "Member$it") }
        for (o in others) http.post("/v1/groups/join") { bearerAuth(o.token); contentType(ContentType.Application.Json); setBody(JoinGroupRequest(family.code)) }

        // Without a pass, choosing a group is refused.
        assertEquals(HttpStatusCode.Forbidden, http.post("/v1/family-pass") { bearerAuth(mom.token); contentType(ContentType.Application.Json); setBody(FamilyPassRequest(family.id)) }.status)
        val bought: Entitlements = http.buy(mom.token, Catalog.FAMILY_PASS, "test:pass-1").body()
        assertTrue(bought.passActive && bought.passExpires > System.currentTimeMillis())
        http.post("/v1/family-pass") { bearerAuth(mom.token); contentType(ContentType.Application.Json); setBody(FamilyPassRequest(family.id)) }

        // Mom plus the first four to join are covered (5 seats); the fifth to join is not.
        for (o in others.take(4)) assertEquals("Mom", http.ent(o.token).coveredBy, o.name)
        assertEquals("", http.ent(others[4].token).coveredBy)
        assertTrue(Catalog.unlocked("capture:sword", emptySet(), familyCovered = http.ent(others[0].token).coveredBy.isNotEmpty()))
    }
}
