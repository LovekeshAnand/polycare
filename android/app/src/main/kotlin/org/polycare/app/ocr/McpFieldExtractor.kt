package org.polycare.app.ocr

/** The fields a scanned health card can fill. */
enum class CardField { NAME, AGE, VILLAGE, NOTES }

/**
 * THE TEACHING TABLE: which written label fills which field.
 *
 * To teach the scanner a new placeholder word, add it to the right list below; nothing else decides
 * where a value goes. Each entry is a whole word (case-insensitive; `\s*` allows a space inside it).
 * Handwriting that misreads a label by one letter is still matched for the long labels, see
 * [McpFieldExtractor.repairLabel].
 */
object CardTemplate {
    val labels: Map<CardField, List<String>> = mapOf(
        CardField.NAME to listOf(
            """mother'?s?\s*name""", """patient\s*name""", """beneficiary(?:\s*name)?""", "name",
            """माता\s*का\s*नाम""", """मरीज़?\s*का\s*नाम""", """लाभार्थी(?:\s*का\s*नाम)?""", "नाम",
        ),
        CardField.AGE to listOf("age", "आयु", "उम्र"),
        CardField.VILLAGE to listOf(
            "village", "vill", "area", "address", "residence", "गाँव", "गांव", "ग्राम", "पता", "निवास", "मोहल्ला",
        ),
        CardField.NOTES to listOf(
            """clinical(?:\s*notes?)?""", "notes?", "symptoms?", "complaints?", "diagnosis", "problems?", "लक्षण", "बीमारी",
        ),
    )

    /** Readings written on their own row. They are added to the notes and are never a name or village. */
    val readingLabels: List<String> = listOf(
        "bp", """blood\s*pressure""", "hb", "haemoglobin", "hemoglobin", "edd", """due\s*date""",
        "weight", "wt", "temp", "pulse", "height",
    )
}

/**
 * Turns the rows of a scanned card into household-record fields (M3: "OCR scan of MCP cards, lab
 * reports, prescriptions and medicine strips → confirmed fields in the household record").
 *
 * The rule is strict and has no guessing: a field's value is the text after its label **on the same
 * row**, up to the next label. If there is nothing after the label the field stays empty for the
 * ASHA worker to fill in. Position, order and "what looks like a name" are never used. Rows come from
 * [RowBuilder], which puts each value on its label's row using where the words sit on the page.
 * Hindi names and places are written out in Latin letters (see [DevanagariTransliterator]).
 *
 * Every field it finds is presented to the ASHA worker as an editable, pre-filled suggestion in
 * [ScanScreen], never saved without her reviewing and confirming consent first.
 */
object McpFieldExtractor {
    data class Candidates(
        val name: String? = null,
        val age: Int? = null,
        val village: String? = null,
        val docType: String = "MCP Card",
        val clinicalNotes: String? = null,
    )

    /** A label must be a whole word: "name" must not match inside "surname", nor "नाम" inside a longer word. */
    private fun label(alternatives: List<String>) =
        Regex("""(?<![\p{L}\p{M}])(?:${alternatives.joinToString("|")})(?![\p{L}\p{M}])""", RegexOption.IGNORE_CASE)

    private val fieldLabel: Map<CardField, Regex> = CardTemplate.labels.mapValues { label(it.value) }
    private val readingLabel = label(CardTemplate.readingLabels)
    private val allLabels: List<Regex> = fieldLabel.values.toList() + readingLabel

    private val bpPattern = Regex("""(?i)\b(?:bp|blood\s*pressure)\b[^0-9\n]{0,6}(\d{2,4})[ \t]*(?:[/|]|[ \t])[ \t]*(\d{2,4})\b""")
    private val hbPattern = Regex("""(?i)\b(?:hb|haemoglobin|hemoglobin)\s*[:=\-]?\s*(\d{1,2}(?:\.\d)?)\b""")
    private val eddPattern = Regex("""(?i)\b(?:edd|due\s*date|प्रसव\s*तिथि)\s*[:=\-]?\s*(\d{1,2}[\/\-\.]\d{1,2}[\/\-\.]\d{2,4})\b""")
    /** Digits of an age, tolerating a space or dash the OCR put inside them ("2-1" is 21). */
    private val ageValue = Regex("""\d(?:[ \-]?\d){0,2}""")

    fun extract(latinText: String, devanagariText: String): Candidates {
        val combined = DevanagariTransliterator.normaliseDigits("$latinText\n$devanagariText")
        val lines = combined.lines().map { it.trim() }.filter { it.isNotEmpty() }.map(::repairLabel)
        fun value(field: CardField) = valueAfterLabel(lines, field)

        val clinicalItems = mutableListOf<String>()
        value(CardField.NOTES)?.let { clinicalItems.add(it) }
        bpPattern.find(combined)?.let {
            var systolicText = it.groupValues[1]
            // "200/1000" read as "2001 1000": the slash became a trailing 1 or 7 on the first number.
            if (systolicText.length == 4 && systolicText.last() in "17") systolicText = systolicText.dropLast(1)
            val systolic = systolicText.toInt()
            val diastolic = it.groupValues[2].toInt()
            val plausible = systolic in 60..260 && diastolic in 30..160
            clinicalItems.add("BP: $systolic/$diastolic" + if (plausible) "" else " (check value)")
        }
        hbPattern.find(combined)?.let { clinicalItems.add("Hb: ${it.groupValues[1]} g/dL") }
        eddPattern.find(combined)?.let { clinicalItems.add("EDD: ${it.groupValues[1]}") }

        // Common medicines named on a prescription or strip.
        val lower = combined.lowercase()
        if (lower.contains("ifa") || lower.contains("iron")) clinicalItems.add("IFA Tablets")
        if (lower.contains("ors")) clinicalItems.add("ORS packets")
        if (lower.contains("zinc")) clinicalItems.add("Zinc syrup")
        if (lower.contains("paracetamol") || lower.contains("pcm")) clinicalItems.add("Paracetamol")
        if (lower.contains("calcium")) clinicalItems.add("Calcium tablets")

        return Candidates(
            name = value(CardField.NAME)?.let(::inEnglish),
            age = value(CardField.AGE)?.let { ageValue.find(it)?.value?.filter(Char::isDigit)?.toIntOrNull() }?.takeIf { it in 0..120 },
            village = value(CardField.VILLAGE)?.let(::inEnglish),
            docType = detectDocType(combined),
            clinicalNotes = clinicalItems.takeIf { it.isNotEmpty() }?.joinToString(", "),
        )
    }

    /** The text after [field]'s label on the same row, cut where the next label starts; null if empty. */
    private fun valueAfterLabel(lines: List<String>, field: CardField): String? {
        val own = fieldLabel.getValue(field)
        val others = allLabels.filter { it !== own }
        for (line in lines) {
            val m = own.find(line) ?: continue
            val afterLabel = line.substring(m.range.last + 1).trimStart(*SEPARATORS)
            clean(cutAtNextLabel(afterLabel, others))?.let { return it }
        }
        return null
    }

    /** True when a box begins with a field label, with or without a value after it ("Name:R", "Vilage: Noida"). */
    fun startsWithLabel(text: String): Boolean {
        val t = repairLabel(DevanagariTransliterator.normaliseDigits(text.trim()))
        return allLabels.any { it.find(t)?.range?.first == 0 }
    }

    /** True for a box that holds only a field label ("Name", "viuage:", "BP =") and no value. */
    fun isLabelOnly(text: String): Boolean {
        val t = repairLabel(DevanagariTransliterator.normaliseDigits(text.trim()))
        val m = allLabels.firstNotNullOfOrNull { it.find(t) } ?: return false
        return t.removeRange(m.range).trim(*SEPARATORS).isEmpty()
    }

    private val hindiLabelWords = listOf("नाम", "गांव", "गाँव", "ग्राम", "उम्र", "आयु", "पता", "निवास")
    private val englishLabelWords = listOf("village", "residence", "address", "beneficiary")
    private val leadingHindi = Regex("""^[\p{L}\p{M}]+""")
    private val leadingLatin = Regex("""^[A-Za-z]+""")

    /**
     * Handwriting OCR often changes a letter of a label ("नाम" read as "नाज", "Village" as "viuage").
     * If a row starts with a word within one edit (Hindi) or two edits (long English labels) of a
     * known label, the real label is put back. Short English labels like "Name" must match exactly.
     */
    private fun repairLabel(line: String): String = repairEnglish(repairHindi(line))

    private val separatorAfterLabel = Regex("""^\s{0,2}[:=\-–—.：]""")

    /**
     * A misread label is only repaired when it is followed by a label separator (":" or "="), so a
     * person named राम at the start of a row is never mistaken for the label "नाम". Up to two letters
     * may be wrong; a stray digit or mark before the label ("0 तान :") is ignored.
     */
    private fun repairHindi(line: String): String {
        val skip = line.indexOfFirst { it.isLetter() }
        if (skip < 0 || skip > 3) return line
        val body = line.substring(skip)
        val word = leadingHindi.find(body)?.value ?: return line
        if (word.length < 3 || !DevanagariTransliterator.containsDevanagari(word) || word in hindiLabelWords) return line
        if (!separatorAfterLabel.containsMatchIn(body.substring(word.length))) return line
        val fixed = hindiLabelWords.firstOrNull { editDistance(word, it) <= 2 } ?: return line
        return fixed + body.substring(word.length)
    }

    private fun repairEnglish(line: String): String {
        val word = leadingLatin.find(line)?.value ?: return line
        if (word.length < 6) return line
        val lower = word.lowercase()
        val fixed = englishLabelWords.firstOrNull { it != lower && editDistance(lower, it) <= 2 } ?: return line
        return fixed.replaceFirstChar { it.uppercaseChar() } + line.substring(word.length)
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun cutAtNextLabel(text: String, others: List<Regex>): String {
        val cut = others.mapNotNull { it.find(text)?.range?.first }.minOrNull() ?: return text
        return text.substring(0, cut)
    }

    private fun inEnglish(value: String): String =
        if (DevanagariTransliterator.containsDevanagari(value)) DevanagariTransliterator.toLatin(value) else value

    private fun detectDocType(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.contains("mcp") || lower.contains("mother and child") || lower.contains("मातृ") || lower.contains("rch") -> "MCP Card"
            lower.contains("rx") || lower.contains("prescription") || lower.contains("opd") || lower.contains("phc") || lower.contains("chc") -> "Prescription"
            lower.contains("lab") || lower.contains("pathology") || lower.contains("blood test") || lower.contains("urine") -> "Lab Report"
            lower.contains("tab") || lower.contains("cap") || lower.contains("mg") || lower.contains("strip") || lower.contains("expiry") -> "Medicine Strip"
            else -> "Health Document"
        }
    }

    private val SEPARATORS = charArrayOf(' ', '\t', ':', '：', '-', '–', '—', '.', '=')

    /** Strips trailing punctuation/whitespace OCR commonly leaves on a field value. */
    private fun clean(raw: String): String? =
        raw.trim().trim(':', '-', '.', ',', ';', '।').trim().takeIf { it.isNotBlank() }
}
