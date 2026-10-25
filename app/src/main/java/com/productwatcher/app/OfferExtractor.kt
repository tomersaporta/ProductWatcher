package com.productwatcher.app

import kotlin.math.abs

/** Geometry-only data model: can be tested on JVM without Android/ML Kit. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cx: Double get() = (left + right) / 2.0
    val cy: Double get() = (top + bottom) / 2.0
    val width: Int get() = (right - left).coerceAtLeast(1)
}

data class OfferMatch(
    val product: String,
    val price: String?,
    val evidence: String,
    val source: String,
    val confidence: String,
    val confidenceScore: Double,
    val reviewState: String,
    val associationReason: String,
    /** OCR can suggest a price, but only a human checking the Telegram post can verify it. */
    val priceVerified: Boolean = false
)

data class SourceMetadata(val branch: String?, val branchSource: String?, val sourceDate: String?)

object SourceMetadataExtractor {
    private const val DATE = "\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}"
    private val datePattern = Regex("(?<!\\d)($DATE)(?!\\d)")
    private val branchPatterns = listOf(
        Regex("(?:סניף|branch)\\s*[:：-]?\\s*(.+?)(?=\\s+$DATE|[,;\\n]|$)", RegexOption.IGNORE_CASE),
        Regex("(אושר\\s*עד(?:\\s+[\\p{L}'-]+){1,6})(?=\\s+$DATE|[,;\\n]|$)", RegexOption.IGNORE_CASE)
    )

    private fun branch(value: String): String? = branchPatterns.firstNotNullOfOrNull { pattern ->
        pattern.find(value)?.groupValues?.getOrNull(1)?.trim()?.replace(Regex("\\s+"), " ")
            ?.replace(Regex("^אושר\\s*עד\\s*", RegexOption.IGNORE_CASE), "")?.take(70)
    }

    fun extract(caption: String, ocr: String): SourceMetadata {
        val captionBranch = branch(caption)
        val ocrBranch = if (captionBranch == null) branch(ocr) else null
        val captionDate = datePattern.find(caption)?.groupValues?.getOrNull(1)
        val ocrDate = if (captionDate == null) datePattern.find(ocr)?.groupValues?.getOrNull(1) else null
        return SourceMetadata(
            branch = captionBranch ?: ocrBranch,
            branchSource = when { captionBranch != null -> "Telegram caption"; ocrBranch != null -> "OCR"; else -> null },
            sourceDate = captionDate ?: ocrDate
        )
    }
}

/** Stable across rescans so one Telegram post/product pair produces one stored card. */
object OfferIdentity {
    fun key(chatId: Long, messageId: Long, product: String): String =
        "$chatId:$messageId:${product.trim().lowercase()}"
}

object MessageLinkPolicy {
    fun normalize(value: String?): String? = value?.trim()?.takeIf {
        it.startsWith("https://t.me/") || it.startsWith("http://t.me/") || it.startsWith("tg://")
    }
}

object OfferAlertPolicy {
    fun shouldAlert(verified: Boolean, reviewState: String, confidenceScore: Double, price: String): Boolean =
        verified || (reviewState == "VERIFIED_BY_IMAGE" && confidenceScore >= 0.90 && !price.startsWith("Price unknown"))
}

/** Associates a tag with the nearest model printed ABOVE it and in the same image column. */
object OfferExtractor {
    private val pricePattern = Regex("(?<![\\p{L}\\p{N}])(?:[1-9]\\d{0,2}[,.]\\d{3}|[1-9]\\d{2,4})(?![\\p{L}\\p{N}])")

    fun prices(text: String): List<String> = pricePattern.findAll(text).mapNotNull { m ->
        val n = m.value.replace(",", "").replace(".", "").toIntOrNull()
        if (n != null && n in 200..50000) n.toString() else null
    }.toList()

    private data class Candidate(val score: Double, val price: String, val anchor: OcrLine, val tag: OcrLine)

    /** Premium robot-vacuum tiles must not inherit accessory/instalment prices such as 795. */
    fun plausiblePrice(product: String, value: String): Boolean {
        val amount = value.toIntOrNull() ?: return false
        val model = Matcher.parsedModels(product).firstOrNull()
        return if (model != null && model.number in setOf("x40", "x50", "x60")) amount in 1200..15000
        else amount in 200..50000
    }

    private fun combinedModelLines(lines: List<OcrLine>, width: Int, height: Int): List<OcrLine> =
        lines.indices.mapNotNull { i ->
            if (i + 1 >= lines.size) null else {
                val a = lines[i]; val b = lines[i + 1]
                if (Matcher.parsedModels(a.text).isEmpty() && Matcher.parsedModels(b.text).isEmpty() &&
                    abs(a.cx - b.cx) < width * 0.18 && abs(a.bottom - b.top) < height * 0.07 &&
                    Matcher.parsedModels("${a.text} ${b.text}").isNotEmpty())
                    OcrLine("${a.text} ${b.text}", minOf(a.left, b.left), a.top, maxOf(a.right, b.right), b.bottom)
                else null
            }
        }

    private fun localEvidence(lines: List<OcrLine>, anchor: OcrLine, tag: OcrLine?, width: Int, height: Int): String {
        val local = lines.filter { line ->
            abs(line.cx - anchor.cx) <= width * 0.22 &&
                line.cy >= anchor.cy - height * 0.08 && line.cy <= (tag?.cy ?: anchor.cy) + height * 0.08
        }.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left }).map { it.text.trim() }.filter { it.isNotBlank() }
        return local.distinct().joinToString(" · ").take(260).ifBlank {
            listOfNotNull(anchor.text, tag?.text).joinToString(" · ").take(260)
        }
    }

    fun extract(lines: List<OcrLine>, watchlist: List<String>, caption: String = ""): List<OfferMatch> {
        if (watchlist.isEmpty()) return emptyList()
        val fullText = buildString {
            append(caption)
            lines.forEach { append('\n').append(it.text) }
        }
        val width = (lines.maxOfOrNull { it.right } ?: 0).coerceAtLeast(1)
        val height = (lines.maxOfOrNull { it.bottom } ?: 0).coerceAtLeast(1)
        val tags = lines.flatMap { line -> prices(line.text).map { line to it } }
        val combined = combinedModelLines(lines, width, height)
        val allModelAnchors = (lines.filter { Matcher.parsedModels(it.text).isNotEmpty() } + combined)
            .distinctBy { listOf(it.text, it.left, it.top, it.right, it.bottom) }
        return watchlist.mapNotNull { product ->
            val direct = lines.filter { Matcher.matches(it.text, product) }
            val anchors = (direct + combined.filter { Matcher.matches(it.text, product) })
                .distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
            if (anchors.isEmpty() && !Matcher.matches(fullText, product)) return@mapNotNull null
            val candidates = anchors.flatMap { anchor ->
                val sameRow = allModelAnchors.filter { abs(it.cy - anchor.cy) < height * 0.18 }.sortedBy { it.cx }
                val position = sameRow.indexOfFirst { it.left == anchor.left && it.top == anchor.top && it.text == anchor.text }
                val leftBound = if (position > 0) (sameRow[position - 1].cx + anchor.cx) / 2.0 else 0.0
                val rightBound = if (position >= 0 && position + 1 < sameRow.size)
                    (anchor.cx + sameRow[position + 1].cx) / 2.0 else width.toDouble()
                tags.mapNotNull priceCandidate@{ (tag, price) ->
                    if (!plausiblePrice(product, price)) return@priceCandidate null
                    if (tag.cy <= anchor.cy || tag.top > anchor.bottom + height * 0.38) return@priceCandidate null
                    if (tag.cx <= leftBound || tag.cx >= rightBound) return@priceCandidate null
                    val dx = abs(tag.cx - anchor.cx) / width
                    if (dx > 0.16) return@priceCandidate null
                    val dy = (tag.top - anchor.bottom).coerceAtLeast(0) / height.toDouble()
                    val score = dx * 4.5 + dy
                    val owners = allModelAnchors.mapNotNull { other ->
                        if (tag.cy <= other.cy || tag.top > other.bottom + height * 0.38) null
                        else abs(tag.cx - other.cx) / width * 4.5 + (tag.top - other.bottom).coerceAtLeast(0) / height.toDouble()
                    }.sorted()
                    if (owners.isNotEmpty() && score > owners.first() + 0.015) return@priceCandidate null
                    if (owners.size > 1 && owners[1] - owners[0] < 0.045) return@priceCandidate null
                    Candidate(score, price, anchor, tag)
                }
            }.sortedBy { it.score }
            // Multiple different plausible prices in one tile may be an old price, instalment,
            // accessory or adjacent promotion. Without an explicit semantic label, do not guess.
            val chosen = candidates.firstOrNull()?.takeIf { candidates.map { it.price }.distinct().size == 1 }
            val captionPrice = prices(caption).distinct().singleOrNull()?.takeIf { plausiblePrice(product, it) }
            val price = when {
                chosen != null -> "₪${chosen.price} (OCR, unverified)"
                captionPrice != null && Matcher.distinctModelCount(caption) == 1 -> "₪$captionPrice (caption, unverified)"
                else -> null
            }
            val confidenceScore = when {
                chosen != null -> (0.90 - chosen.score * 0.35).coerceIn(0.76, 0.90)
                captionPrice != null -> 0.68
                anchors.isNotEmpty() -> 0.58
                else -> 0.45
            }
            val reviewState = if (confidenceScore >= 0.75 && price != null) "UNCERTAIN" else "REVIEW_NEEDED"
            val source = when { chosen != null -> "same-tile spatial OCR"; captionPrice != null -> "Telegram caption"; else -> "model OCR" }
            val reason = when {
                chosen != null -> "Unique plausible price owned by the same local product tile"
                captionPrice != null -> "Single plausible caption price; manual review recommended"
                candidates.isNotEmpty() -> "Several plausible local prices; attribution left unknown"
                else -> "No unique plausible price in the model's local image region"
            }
            val primaryAnchor = chosen?.anchor ?: anchors.firstOrNull()
            OfferMatch(
                product = product,
                price = price,
                evidence = if (primaryAnchor != null) localEvidence(lines, primaryAnchor, chosen?.tag, width, height)
                    else Matcher.evidence(fullText, product).orEmpty(),
                source = source,
                confidence = "${(confidenceScore * 100).toInt()}% · $reason",
                confidenceScore = confidenceScore,
                reviewState = reviewState,
                associationReason = reason,
                priceVerified = false
            )
        }
    }
}
