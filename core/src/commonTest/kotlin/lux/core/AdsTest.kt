package lux.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdsTest {
    @Test fun allIndiaClashesWithEveryStateButStatesDontClashWithEachOther() {
        assertTrue(Ads.overlaps("IN-KA", "IN-KA"))
        assertTrue(Ads.overlaps(Ads.NATIONAL, "IN-KA"))
        assertTrue(Ads.overlaps("IN-MH", Ads.NATIONAL))
        assertTrue(!Ads.overlaps("IN-KA", "IN-MH"))
    }

    @Test fun pricesFollowAreaSizeAndLongerBookingsCostLessPerDay() {
        val top = Ads.Spot.Top
        val india = Ads.area(Ads.NATIONAL)!!
        val up = Ads.area("IN-UP")!!
        val goa = Ads.area("IN-GA")!!
        assertEquals(top.nationalPerDay, Ads.dayPrice(top, india))
        assertTrue(Ads.dayPrice(top, up) > Ads.dayPrice(top, goa))
        assertTrue(Ads.dayPrice(top, goa) > 0)
        val week = Ads.quote(top, up, 7)
        assertTrue(week.price < Ads.dayPrice(top, up) * 7)
        assertEquals((week.price * 18 + 50) / 100, week.gst)
        assertEquals(week.price + week.gst, week.total)
    }

    @Test fun picturesAreCroppedFromTheMiddle() {
        // A square picture for a 6:1 banner keeps the middle strip.
        assertContentEquals(intArrayOf(0, 500, 1200, 200), Ads.crop(Ads.Spot.Top, 1200, 1200))
        // A wide picture for the square card keeps the middle square.
        assertContentEquals(intArrayOf(250, 0, 1000, 1000), Ads.crop(Ads.Spot.Break, 1500, 1000))
    }

    @Test fun blockedCategoriesExplainWhy() {
        for (c in Ads.Category.entries.filter { it.blocked }) assertTrue(c.why.isNotEmpty(), c.name)
        assertTrue(Ads.Category.Political.blocked)
        assertTrue(Ads.allowedCategories.none { it.blocked })
        assertEquals(37, Ads.areas.size, "All India + 28 states + 8 union territories")
    }
}
