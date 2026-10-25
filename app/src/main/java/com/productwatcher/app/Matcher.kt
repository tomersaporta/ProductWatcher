package com.productwatcher.app

/** Model-aware matching. X60 Ultra must never be confused with X60 Pro/Max Ultra. */
object Matcher {
    private fun normalized(value: String): String = value.lowercase()
        .replace(Regex("[‐‑‒–—−]"), "-")
        .replace(Regex("(?i)\\bx\\s*6\\s*[o0]\\b"), "x60")
        .replace(Regex("(?i)\\bu[l1i]tra\\b"), "ultra")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    // Handles X 60, X60Ultra, UltraComplete, and the OCR truncation 'Complet'.
    private val modelPattern = Regex(
        "(?<![\\p{L}\\p{N}])x\\s*(\\d)\\s*(\\d)\\s*(?:(pro|max)\\s*)?u[l1i]tra\\s*(complete|complet|pro|max|2)?(?![\\p{L}\\p{N}])"
    )

    data class Model(val number: String, val tier: String, val complete: Boolean, val text: String)

    fun parsedModels(text: String): List<Model> = modelPattern.findAll(normalized(text)).map { m ->
        val prefix = m.groupValues[3]
        val suffix = m.groupValues[4]
        val tier = when {
            suffix == "2" -> "v2"
            prefix == "max" || suffix == "max" -> "max"
            prefix == "pro" || suffix == "pro" -> "pro"
            else -> "standard"
        }
        Model("x${m.groupValues[1]}${m.groupValues[2]}", tier, suffix.startsWith("complet"), m.value)
    }.toList()

    fun distinctModelCount(text: String): Int = parsedModels(text).map { it.number + ":" + it.tier + ":" + it.complete }.distinct().size

    fun matches(text: String, product: String): Boolean {
        val requested = parsedModels(product).firstOrNull()
        if (requested != null) {
            return parsedModels(text).any { found ->
                found.number == requested.number && found.tier == requested.tier &&
                    (!requested.complete || found.complete)
            }
        }
        // Other brands/products still use an exact, token-boundary phrase.
        val phrase = normalized(product)
        return phrase.isNotBlank() && " ${normalized(text)} ".contains(" $phrase ")
    }

    fun evidence(text: String, product: String): String? = text.lineSequence()
        .firstOrNull { matches(it, product) }?.trim()?.take(180)

    /** Use only when there is exactly one plausible price. Multi-product photos require spatial extraction. */
    fun price(text: String): String? {
        val prices = OfferExtractor.prices(text).distinct()
        return if (prices.size == 1) "₪${prices.single()} (OCR, unverified)" else null
    }

    fun branch(text: String): String? = SourceMetadataExtractor.extract("", text).branch
}
