package com.productwatcher.app

import org.junit.Assert.*
import org.junit.Test

class OfferMatchingTest {
    private fun collageFixture(): List<OcrLine> = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/critical_collage.tsv"))
        .bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { row ->
            val p = row.split('|'); OcrLine(p[0], p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt())
        }

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
        assertFalse(Matcher.matches("Dreame X60 Pro Ultra", "X60 Ultra Complete"))
        assertFalse(Matcher.matches("Dreame X60 Ultra Complete", "X60 Pro Ultra Complete"))
        assertTrue(Matcher.matches("Dreame X60 Pro Ultra Complete", "X60 Pro Ultra Complete"))
        assertTrue(Matcher.matches("Dreame X60 Max Ultra", "X60 Max Ultra"))
        assertFalse(Matcher.matches("Dreame X60 Max Ultra", "X60 Ultra Complete"))
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
        assertTrue(found.single { it.product == "Dreame X60 Ultra" }.evidence.contains("3799"))
        assertTrue(found.single { it.product == "X50 Ultra" }.price!!.contains("2450"))
        assertTrue(found.single { it.product == "X40 Ultra" }.price!!.contains("1790"))
        assertTrue(found.all { !it.priceVerified })
        assertTrue(found.all { it.price?.endsWith("(OCR, unverified)") == true })
        assertNull(OfferExtractor.extract(lines.filterNot { it.text == "3799" }, listOf("X60 Ultra")).single().price)
    }

    @Test fun criticalCollageFixturePreservesAllThreeColumns() {
        val found = OfferExtractor.extract(collageFixture(), listOf(
            "Dreame X40 Ultra Complete", "Dreame X50 Ultra", "Dreame X60 Ultra Complete", "Dreame X60 Pro Ultra"
        ))
        assertEquals("₪1790 (OCR, unverified)", found.single { it.product.contains("X40") }.price)
        assertEquals("₪2450 (OCR, unverified)", found.single { it.product.contains("X50") }.price)
        assertEquals("₪3799 (OCR, unverified)", found.single { it.product.contains("X60 Ultra Complete") }.price)
        assertTrue(found.none { it.product.contains("Pro") })
    }

    @Test fun textOnlyOfferIsStoredAsUnverifiedOcr() {
        val found = OfferExtractor.extract(
            emptyList(),
            listOf("Dreame X60 Pro Ultra", "Dreame X60 Ultra Complete"),
            "מבצע Dreame X60 Pro Ultra במחיר 4399"
        )
        assertEquals(1, found.size)
        assertEquals("Dreame X60 Pro Ultra", found.single().product)
        assertEquals("₪4399 (caption, unverified)", found.single().price)
        assertFalse(found.single().priceVerified)
        assertTrue(found.single().evidence.contains("X60 Pro Ultra"))
    }

    @Test fun offerIdentityIsStableAcrossRescansButKeepsVariantsSeparate() {
        assertEquals(
            OfferIdentity.key(-100123L, 456L, "Dreame X60 Pro Ultra"),
            OfferIdentity.key(-100123L, 456L, "  dreame x60 PRO ultra ")
        )
        assertNotEquals(
            OfferIdentity.key(-100123L, 456L, "Dreame X60 Pro Ultra"),
            OfferIdentity.key(-100123L, 456L, "Dreame X60 Ultra Complete")
        )
    }

    @Test fun rejectsAccessoryPriceAndKeepsCorrectCollagePrice() {
        val lines = listOf(
            OcrLine("Accessory bundle", 80, 180, 350, 240),
            OcrLine("795", 170, 380, 270, 430),
            OcrLine("Dreame X60 Ultra Complete", 570, 180, 930, 240),
            OcrLine("2450", 680, 390, 810, 450)
        )
        val found = OfferExtractor.extract(lines, listOf("Dreame X60 Ultra Complete")).single()
        assertEquals("₪2450 (OCR, unverified)", found.price)
        assertEquals("UNCERTAIN", found.reviewState)
        assertTrue(found.evidence.contains("2450"))
        assertFalse(OfferExtractor.plausiblePrice("Dreame X60 Ultra Complete", "795"))
    }

    @Test fun ambiguousSameTilePricesRemainUnknown() {
        val lines = listOf(
            OcrLine("Dreame X60 Ultra Complete", 120, 160, 480, 225),
            OcrLine("2450", 220, 360, 330, 415),
            OcrLine("2590", 250, 370, 360, 425)
        )
        val offer = OfferExtractor.extract(lines, listOf("Dreame X60 Ultra Complete")).single()
        assertNull(offer.price)
        assertEquals("REVIEW_NEEDED", offer.reviewState)
    }

    @Test fun captionBranchAndDateTakePriorityOverOcr() {
        val metadata = SourceMetadataExtractor.extract(
            "סניף אושר עד קרית ביאליק 20.10.2026",
            "סניף אושר עד תל אביב 19.10.2026"
        )
        assertEquals("קרית ביאליק", metadata.branch)
        assertEquals("Telegram caption", metadata.branchSource)
        assertEquals("20.10.2026", metadata.sourceDate)
    }

    @Test fun acceptsCanonicalPublicAndPrivateTelegramLinksOnly() {
        assertEquals("https://t.me/shop/123", MessageLinkPolicy.normalize(" https://t.me/shop/123 "))
        assertEquals("tg://privatepost?channel=123&post=4", MessageLinkPolicy.normalize("tg://privatepost?channel=123&post=4"))
        assertNull(MessageLinkPolicy.normalize("https://example.com/not-telegram"))
    }

    @Test fun lowConfidenceAndUnknownPricesNeverAlert() {
        assertFalse(OfferAlertPolicy.shouldAlert(false, "REVIEW_NEEDED", 0.91, "₪2450 (OCR, unverified)"))
        assertFalse(OfferAlertPolicy.shouldAlert(false, "UNCERTAIN", 0.90, "Price unknown · review required"))
        assertFalse(OfferAlertPolicy.shouldAlert(false, "UNCERTAIN", 0.70, "₪2450 (OCR, unverified)"))
        assertFalse(OfferAlertPolicy.shouldAlert(false, "UNCERTAIN", 0.95, "₪2450 (OCR, unverified)"))
        assertTrue(OfferAlertPolicy.shouldAlert(false, "VERIFIED_BY_IMAGE", 0.94, "₪2450 (image-supported)"))
    }

    @Test fun recallScreeningAcceptsPartialAndSplitModelHints() {
        val lines = listOf(OcrLine("Dreame", 10, 10, 100, 30), OcrLine("X 60", 10, 35, 100, 55))
        assertEquals(listOf("Dreame X60 Ultra Complete"),
            CandidateScreening.candidates(lines, "", listOf("Dreame X60 Ultra Complete", "Roborock S8")))
    }

    @Test fun localModelOutputRequiresExactVariantAndSameTileEvidence() {
        val region = VisionRegion(0.5, 0.1, 0.8, 0.4)
        val correct = VisionResultValidator.validate(
            VisionMatch("Dreame X60 Ultra Complete", 3799, "קרית ביאליק", "X60 Ultra Complete", "₪3799", "same_tile", region, region),
            listOf("Dreame X60 Ultra Complete", "Dreame X60 Pro Ultra"))
        assertEquals("VERIFIED_BY_IMAGE", correct?.verificationStatus)
        assertEquals(3799, correct?.priceIls)
        assertNull(VisionResultValidator.validate(
            VisionMatch("Dreame X60 Pro Ultra", 3799, null, "X60 Pro Ultra", "₪3799", "same_tile", region, region),
            listOf("Dreame X60 Ultra Complete")))
        val wrongPrice = VisionResultValidator.validate(
            VisionMatch("Dreame X60 Ultra Complete", 795, null, "X60 Ultra Complete", "₪795", "same_tile", region, region),
            listOf("Dreame X60 Ultra Complete"))
        assertNull(wrongPrice?.priceIls)
        assertEquals("UNCERTAIN", wrongPrice?.verificationStatus)
    }
}
