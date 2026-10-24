package com.productwatcher.app

import kotlin.math.abs

/** Geometry-only data model: can be tested on JVM without Android/ML Kit. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cx: Double get() = (left + right) / 2.0
}

data class OfferMatch(val product: String, val price: String?, val evidence: String, val source: String)

/** Associates a tag with the nearest model printed ABOVE it and in the same image column. */
object OfferExtractor {
    private val pricePattern = Regex("(?<![\\p{L}\\p{N}])(?:[1-9]\\d{0,2}[,.]\\d{3}|[1-9]\\d{2,4})(?![\\p{L}\\p{N}])")

    fun prices(text: String): List<String> = pricePattern.findAll(text).mapNotNull { m ->
        val n = m.value.replace(",", "").replace(".", "").toIntOrNull()
        if (n != null && n in 200..50000) n.toString() else null
    }.toList()

    fun extract(lines: List<OcrLine>, watchlist: List<String>, caption: String = ""): List<OfferMatch> {
        if (watchlist.isEmpty()) return emptyList()
        val fullText = buildString {
            append(caption)
            lines.forEach { append('\n').append(it.text) }
        }
        val width = (lines.maxOfOrNull { it.right } ?: 0).coerceAtLeast(1)
        val height = (lines.maxOfOrNull { it.bottom } ?: 0).coerceAtLeast(1)
        val tags = lines.flatMap { line -> prices(line.text).map { line to it } }
        return watchlist.mapNotNull { product ->
            val direct = lines.filter { Matcher.matches(it.text, product) }
            // Some OCR engines break "X60" and "Ultra" into separate adjacent lines.
            val combined = lines.indices.mapNotNull { i ->
                if (i + 1 >= lines.size) null else {
                    val a = lines[i]; val b = lines[i + 1]
                    val xGap = abs(a.cx - b.cx)
                    if (xGap < width * 0.18 && abs(a.bottom - b.top) < height * 0.07 &&
                        Matcher.matches("${a.text} ${b.text}", product))
                        OcrLine("${a.text} ${b.text}", minOf(a.left, b.left), a.top, maxOf(a.right, b.right), b.bottom)
                    else null
                }
            }
            val anchors = direct + combined
            if (anchors.isEmpty() && !Matcher.matches(fullText, product)) return@mapNotNull null
            val chosen = anchors.flatMap { anchor ->
                tags.mapNotNull priceCandidate@{ (tag, price) ->
                    if (tag.top < anchor.bottom - height * 0.025) return@priceCandidate null
                    val dx = abs(tag.cx - anchor.cx) / width
                    if (dx > 0.18) return@priceCandidate null // Different display column.
                    val dy = (tag.top - anchor.bottom).coerceAtLeast(0) / height.toDouble()
                    Triple(dx * 5.0 + dy * 0.8, price, anchor.text)
                }
            }.minByOrNull { it.first }
            val price = when {
                chosen != null -> "₪${chosen.second} (OCR, unverified)"
                // A single price in the entire post is safe as a fallback; several are not.
                tags.map { it.second }.distinct().size == 1 && Matcher.distinctModelCount(fullText) <= 1 ->
                    "₪${tags.first().second} (OCR, unverified)"
                Matcher.price(caption) != null && prices(caption).size == 1 && tags.isEmpty() -> Matcher.price(caption)
                else -> null
            }
            OfferMatch(product, price, chosen?.third ?: anchors.firstOrNull()?.text ?: Matcher.evidence(fullText, product).orEmpty(),
                if (chosen != null) "spatial OCR" else "text OCR")
        }
    }
}
