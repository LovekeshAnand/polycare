package org.polycare.app.ocr

/**
 * Offline Hindi (Devanagari) -> Latin spelling for names and places, e.g. "सुनीता देवी" -> "Sunita Devi".
 * Transliteration, not translation: it writes the same sounds in Latin letters. Long vowels are
 * collapsed (ी -> i, ू -> u) because that is how Indian names are normally written in English.
 */
object DevanagariTransliterator {

    fun containsDevanagari(text: String): Boolean = text.any { it in 'ऀ'..'ॿ' }

    /** Converts every Devanagari word in [text]; Latin text, digits and spacing pass through. */
    fun toLatin(text: String): String {
        val out = StringBuilder()
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) { out.append(convertWord(word.toString())); word.clear() }
        }
        for (c in normaliseDigits(text)) {
            if (c in 'ऀ'..'ॿ' && c != '।') word.append(c) else { flush(); out.append(if (c == '।') '.' else c) }
        }
        flush()
        return out.toString().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
    }

    fun normaliseDigits(text: String): String =
        text.map { if (it in '०'..'९') '0' + (it - '०') else it }.joinToString("")

    private class Unit(val consonant: String, val vowel: String?, val inherent: Boolean, val halant: Boolean) {
        var suffix = ""
    }

    private fun convertWord(w: String): String {
        val units = mutableListOf<Unit>()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            when {
                c == 'ज' && w.getOrNull(i + 1) == '्' && w.getOrNull(i + 2) == 'ञ' -> {
                    i += 3
                    i = attachVowel(w, i, "gy", units)
                }
                c in CONSONANTS -> {
                    var roman = CONSONANTS.getValue(c)
                    i++
                    if (w.getOrNull(i) == '़') { NUKTA[c]?.let { roman = it }; i++ }
                    i = attachVowel(w, i, roman, units)
                }
                c in VOWELS -> { units += Unit("", VOWELS.getValue(c), inherent = false, halant = false); i++ }
                c == 'ं' || c == 'ँ' -> { units.lastOrNull()?.suffix += "n"; i++ }
                c == 'ः' -> { units.lastOrNull()?.suffix += "h"; i++ }
                else -> i++
            }
        }
        val sb = StringBuilder()
        for ((idx, u) in units.withIndex()) {
            sb.append(u.consonant)
            when {
                u.vowel != null -> sb.append(u.vowel)
                u.inherent && keepInherentA(units, idx) -> sb.append('a')
            }
            sb.append(u.suffix)
        }
        return sb.toString()
    }

    /** Reads a matra / virama after a consonant [roman] starting at [at]; returns the next index. */
    private fun attachVowel(w: String, at: Int, roman: String, units: MutableList<Unit>): Int {
        val n = w.getOrNull(at)
        return when {
            n == '्' -> { units += Unit(roman, null, inherent = false, halant = true); at + 1 }
            n != null && n in MATRAS -> { units += Unit(roman, MATRAS.getValue(n), inherent = false, halant = false); at + 1 }
            else -> { units += Unit(roman, null, inherent = true, halant = false); at }
        }
    }

    /**
     * Hindi drops the inherent "a" at the end of a word (कमल -> Kamal, not Kamala) and in the middle
     * when it sits between consonants and the next syllable carries its own vowel (रामपुर -> Rampur).
     */
    private fun keepInherentA(units: List<Unit>, idx: Int): Boolean {
        val u = units[idx]
        if (u.suffix.isNotEmpty()) return true
        if (idx == units.lastIndex) return false
        if (idx == 0) return true
        val next = units[idx + 1]
        if (next.halant) return true
        val nextHasVowel = next.vowel != null || (next.inherent && idx + 1 != units.lastIndex)
        return !nextHasVowel
    }

    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
    )
    private val NUKTA = mapOf('ड' to "r", 'ढ' to "rh", 'फ' to "f", 'ज' to "z", 'क' to "q", 'ख' to "kh", 'ग' to "g")
    private val VOWELS = mapOf(
        'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
    )
    private val MATRAS = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e",
    )
}
