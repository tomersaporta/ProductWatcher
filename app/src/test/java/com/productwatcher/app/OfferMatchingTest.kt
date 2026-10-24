package com.productwatcher.app

import org.junit.Assert.*
import org.junit.Test

class OfferMatchingTest {
    @Test fun plainUltraIncludesCompleteButNotProOrMax() {
        assertTrue(Matcher.matches("Dreame X60 Ultra Complete", "Dreame X60 Ultra"))
        assertTrue(Matcher.matches("X60 UltraComplete", "Dreame X60 Ultra"))
        assertTrue(Matcher.matches("X 60 Ultra Complet", "X60 Ultra Complete"))
        assertTrue(Matcher.matches("Dreame X6O Ultra", "X60 Ultra"))
        assertFalse(Matcher.matches("Dreame X60 Pro Ultra", "X60 Ultra"))
        assertFalse(Matcher.matches("Dreame X60 Max Ultra", "X60 Ultra"))
        assertFalse(Matcher.matches("Dreame X60 Ultra 2", "X60 Ultra"))
        assertFalse(Matcher.matches("X50 Ultra", "X60 Ultra"))
    }

    @Test fun proMustBeExplicit() {
        assertTrue(Matcher.matches("Dreame X60 Pro Ultra Complete", "X60 Pro Ultra"))
        assertFalse(Matcher.matches("Dreame X60 Ultra Complete", "X60 Pro Ultra"))
        assertFalse(Matcher.matches("Dreame X60 Max Ultra", "X60 Pro Ultra"))
    }

    @Test fun priceTagsUseLocationNotFirstPrice() {
        val lines = listOf(
            OcrLine("X60 Ultra Complete", 220, 395, 505, 460),
            OcrLine("X50 Ultra", 560, 405, 740, 450),
            OcrLine("X40 Ultra Complete", 850, 390, 1090, 450),
            OcrLine("3799", 356, 665, 495, 731),
            OcrLine("2450", 585, 669, 715, 730),
            OcrLine("1790", 1050, 640, 1190, 715)
        )
        val found = OfferExtractor.extract(lines, listOf("Dreame X60 Ultra", "Dreame X60 Pro Ultra", "X50 Ultra", "X40 Ultra"))
        assertEquals(3, found.size)
        assertTrue(found.single { it.product == "Dreame X60 Ultra" }.price!!.contains("3799"))
        assertTrue(found.single { it.product == "X50 Ultra" }.price!!.contains("2450"))
        assertTrue(found.single { it.product == "X40 Ultra" }.price!!.contains("1790"))
        assertNull(OfferExtractor.extract(lines.filterNot { it.text == "3799" }, listOf("X60 Ultra")).single().price)
    }
}
