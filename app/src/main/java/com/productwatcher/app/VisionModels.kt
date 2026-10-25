package com.productwatcher.app

data class VisionRegion(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun encoded(): String = "$left,$top,$right,$bottom"
}

data class VisionMatch(
    val exactModel: String,
    val priceIls: Int?,
    val branch: String?,
    val modelEvidence: String,
    val priceEvidence: String,
    val association: String,
    val productRegion: VisionRegion?,
    val priceRegion: VisionRegion?
)

data class ValidatedVisionMatch(
    val product: String,
    val exactModel: String,
    val priceIls: Int?,
    val branch: String?,
    val evidence: String,
    val confidence: Double,
    val verificationStatus: String,
    val reason: String,
    val productRegion: String,
    val priceRegion: String
)

/** Recall-oriented gate. It intentionally accepts partial model-number and family hints. */
object CandidateScreening {
    private fun normalized(value: String) = value.lowercase()
        .replace('o', '0').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun candidates(lines: List<OcrLine>, caption: String, watchlist: List<String>): List<String> {
        val text = normalized(caption + "\n" + lines.joinToString("\n") { it.text })
        val hasPrice = OfferExtractor.prices(text).isNotEmpty()
        return watchlist.filter { product ->
            if (Matcher.matches(text, product)) return@filter true
            val model = Matcher.parsedModels(product).firstOrNull()
            val number = model?.number?.removePrefix("x")
            val numberHint = number != null && ("x$number" in text || Regex("\\b$number\\b").containsMatchIn(text))
            val family = normalized(product).substringBefore(" x").split(' ').filter { it.length >= 4 }
                .any { it in text }
            numberHint || (family && (hasPrice || lines.size <= 5))
        }.distinct()
    }
}

/** Model output is evidence, not authority. Exact identity and geometry are revalidated deterministically. */
object VisionResultValidator {
    fun validate(match: VisionMatch, watchlist: List<String>): ValidatedVisionMatch? {
        val watched = watchlist.singleOrNull { requested ->
            Matcher.matches(match.exactModel, requested) && Matcher.matches(requested, match.exactModel)
        } ?: return null
        val plausible = match.priceIls?.let { OfferExtractor.plausiblePrice(watched, it.toString()) } == true
        val sameTile = match.association in setOf("same_tile", "labeled_pair")
        val modelGrounded = match.modelEvidence.isNotBlank() && match.productRegion != null
        val priceGrounded = match.priceIls == null || (match.priceEvidence.isNotBlank() && match.priceRegion != null)
        val verifiedByImage = plausible && sameTile && modelGrounded && priceGrounded
        val score = when {
            verifiedByImage -> 0.94
            modelGrounded && match.priceIls == null -> 0.72
            modelGrounded -> 0.61
            else -> 0.35
        }
        val safePrice = match.priceIls?.takeIf { plausible && sameTile }
        return ValidatedVisionMatch(
            product = watched,
            exactModel = match.exactModel,
            priceIls = safePrice,
            branch = match.branch?.trim()?.takeIf { it.isNotBlank() },
            evidence = listOf(match.modelEvidence, match.priceEvidence).filter { it.isNotBlank() }.joinToString(" · ").take(500),
            confidence = score,
            verificationStatus = if (verifiedByImage) "VERIFIED_BY_IMAGE" else "UNCERTAIN",
            reason = when {
                verifiedByImage -> "Exact watched model and price are visibly linked in one product tile"
                !plausible && match.priceIls != null -> "Model price failed local model-specific plausibility checks"
                !sameTile && match.priceIls != null -> "Model price was not linked to the product by a supported spatial relationship"
                else -> "Image evidence is incomplete and requires review"
            },
            productRegion = match.productRegion?.encoded().orEmpty(),
            priceRegion = match.priceRegion?.encoded().orEmpty()
        )
    }
}
