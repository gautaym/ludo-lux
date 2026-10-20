package lux.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogTest {
    @Test fun theClearestBoardsAndTheWholeGameStayFree() {
        for (b in listOf("royal", "sunny", "classic", "bold")) assertTrue(Catalog.unlocked(Catalog.boardItem(b), emptySet()), b)
        assertTrue(Catalog.unlocked(Catalog.pieceItem(0), emptySet()) && Catalog.unlocked(Catalog.pieceItem(1), emptySet()))
        for (c in 0..3) assertTrue(Catalog.unlocked(Catalog.captureItem(c), emptySet()))
        assertFalse(Catalog.unlocked("board:neon", emptySet()))
    }

    @Test fun productsUnlockWhatTheySay() {
        assertTrue(Catalog.unlocked("board:neon", setOf("board_neon")))
        assertFalse(Catalog.unlocked("capture:sword", setOf("board_neon")))
        for (c in 4..6) assertTrue(Catalog.unlocked(Catalog.captureItem(c), setOf("captures_action")))
        assertEquals("captures_action", Catalog.productFor("capture:kick")?.id)
    }

    @Test fun passesUnlockEverything() {
        for (item in Catalog.allItems) {
            assertTrue(Catalog.unlocked(item, setOf(Catalog.UNLOCK_ALL)), item)
            assertTrue(Catalog.unlocked(item, setOf(Catalog.FAMILY_PASS)), item)
            assertTrue(Catalog.unlocked(item, emptySet(), familyCovered = true), item)
        }
    }
}
