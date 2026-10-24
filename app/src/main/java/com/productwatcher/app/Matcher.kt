package com.productwatcher.app

object Matcher {
    private fun normalized(text: String): String = text.lowercase()
        .replace(Regex("[‐‑‒–—−]"), "-")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Exact model sequence, allowing whitespace/punctuation between tokens.
     * Excludes X60 Max Ultra, X60 Ultra, and X60 Pro Ultra 2. */
    fun matches(text: String, product: String): Boolean {
        val query = normalized(product).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (query.isEmpty()) return false
        val hay = " ${normalized(text)} "
        val phrase = query.joinToString(" ")
        if (!hay.contains(" $phrase ")) return false
        if (query.contains("x60") && query.contains("pro") && query.contains("ultra")) {
            if (Regex("\\bx60\\s+(?:max|ultra)\\s+pro\\b").containsMatchIn(hay)) return false
        }
        return true
    }

    fun price(text: String): String? {
        val number = "([0-9]{1,3}(?:[,\\.]\\d{3})+|[0-9]{3,6})"
        val patterns = listOf(
            Regex("(?:₪|ש[\\\"״']?ח)\\s*$number"),
            Regex("$number\\s*(?:₪|ש[\\\"״']?ח)"),
            Regex("(?:מחיר|price)\\s*[:：-]?\\s*₪?\\s*$number", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }?.let { "₪$it (unverified)" }
    }

    fun branch(text: String): String? {
        val patterns = listOf(
            Regex("(?:סניף|באושר\\s*עד|אושר\\s*עד\\s*[-–:]|branch)\\s*[:：-]?\\s*([\\p{L}][\\p{L} \\-]{1,32})", RegexOption.IGNORE_CASE),
            Regex("(?:סניף\\s+)([\\p{L}][\\p{L} \\-]{1,32})")
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            ?.lineSequence()?.firstOrNull()?.trim()?.take(35)
    }
}
