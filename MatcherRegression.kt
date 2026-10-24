import com.productwatcher.app.*
fun main() {
    fun checkMatch(text:String, product:String, expected:Boolean) {
        check(Matcher.matches(text,product)==expected) { "Mismatch $text / $product: want $expected" }
    }
    checkMatch("Dreame X60 Ultra Complete", "Dreame X60 Ultra", true)
    checkMatch("X60 UltraComplete", "Dreame X60 Ultra", true)
    checkMatch("Dreame X 60 Ultra Complet", "X60 Ultra Complete", true)
    checkMatch("Dreame X60 Pro Ultra", "Dreame X60 Ultra", false)
    checkMatch("Dreame X60 Max Ultra", "Dreame X60 Ultra", false)
    checkMatch("Dreame X50 Ultra", "X60 Ultra", false)
    checkMatch("X60 Ultra Complete", "Dreame X60 Pro Ultra", false)
    checkMatch("Dreame X60 Pro Ultra Complete", "Dreame X60 Pro Ultra", true)
    checkMatch("Dreame X60 Ultra", "X60 Ultra Complete", false)
    checkMatch("Dreame X60 Ultra 2", "X60 Ultra", false)
    checkMatch("Dreame X6O UltraComplete", "X60 Ultra", true)
    val lines = listOf(
        OcrLine("X60 Ultra Complete", 220, 395, 505, 460),
        OcrLine("X50 Ultra", 560, 405, 740, 450),
        OcrLine("X40 Ultra Complete", 850, 390, 1090, 450),
        OcrLine("3799", 356, 665, 495, 731),
        OcrLine("2450", 585, 669, 715, 730),
        OcrLine("1790", 1050, 640, 1190, 715)
    )
    val out = OfferExtractor.extract(lines, listOf("Dreame X60 Ultra", "Dreame X60 Pro Ultra", "Dreame X50 Ultra", "Dreame X40 Ultra"))
    println(out)
    check(out.size==3)
    check(out.first { it.product=="Dreame X60 Ultra" }.price?.contains("3799")==true)
    check(out.first { it.product=="Dreame X50 Ultra" }.price?.contains("2450")==true)
    check(out.first { it.product=="Dreame X40 Ultra" }.price?.contains("1790")==true)
    val missingPrice = OfferExtractor.extract(lines.filterNot { it.text == "3799" }, listOf("Dreame X60 Ultra"))
    check(missingPrice.single().price == null) { "A neighboring product price was assigned incorrectly" }
    println("PASS: model variants, exclusions, spatial prices, and ambiguity rejection")
}
// Regression tests below include a complete tagged multi-product photo.
