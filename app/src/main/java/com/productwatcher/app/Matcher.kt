package com.productwatcher.app

object Matcher {
    // All significant query words must appear, in any order. This deliberately
    // distinguishes X60 Pro Ultra from X60 Ultra and X60 Max Ultra.
    fun matches(text: String, product: String): Boolean {
        fun tokens(s: String) = Regex("[\\p{L}\\p{N}]+")
            .findAll(s.lowercase()).map { it.value }.filter { it.length > 1 || it.any(Char::isDigit) }.toList()
        val haystack = tokens(text).toSet()
        val needle = tokens(product)
        return needle.isNotEmpty() && needle.all { it in haystack }
    }
    fun price(text: String): String? {
        val patterns = listOf(
            Regex("(?:₪|ש[\\\"״']?ח)\\s*([0-9][0-9,.]*)"),
            Regex("([0-9][0-9,.]*)\\s*(?:₪|ש[\\\"״']?ח)"),
            Regex("(?:price|מחיר)\\s*[:：-]?\\s*([0-9][0-9,.]*)", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }?.let { "₪$it (unverified)" }
    }
}
