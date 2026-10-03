package org.polycare.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpFieldExtractorTest {

    @Test
    fun extractsEnglishMcpCardFields() {
        val latin = """
            GOVERNMENT OF INDIA
            MOTHER AND CHILD PROTECTION CARD
            Mother's Name: Priya Sharma
            Age: 23
            Village: Rampur
            BP: 120/80
            Hb: 11.2
            EDD: 15/11/2026
        """.trimIndent()

        val candidates = McpFieldExtractor.extract(latin, "")
        assertEquals("Priya Sharma", candidates.name)
        assertEquals(23, candidates.age)
        assertEquals("Rampur", candidates.village)
        assertEquals("MCP Card", candidates.docType)
        assertNotNull(candidates.clinicalNotes)
        assertTrue(candidates.clinicalNotes!!.contains("BP: 120/80"))
        assertTrue(candidates.clinicalNotes!!.contains("Hb: 11.2 g/dL"))
        assertTrue(candidates.clinicalNotes!!.contains("EDD: 15/11/2026"))
    }

    @Test
    fun extractsHindiDevanagariMcpCardFields() {
        val devanagari = """
            मातृ एवं शिशु संरक्षण कार्ड
            माता का नाम: सुनीता देवी
            उम्र: 26
            गांव: चांदपुर
        """.trimIndent()

        val candidates = McpFieldExtractor.extract("", devanagari)
        assertEquals("Sunita Devi", candidates.name)
        assertEquals(26, candidates.age)
        assertEquals("Chandpur", candidates.village)
        assertEquals("MCP Card", candidates.docType)
    }

    @Test
    fun extractsPrescriptionAndMedicineNotes() {
        val prescription = """
            PHC Primary Health Centre Rampur
            Rx Prescription
            Patient Name: Rekha Devi
            Age: 28
            Village: Kalyanpur
            Tab IFA 1 OD
            Tab Calcium 500mg
            ORS packets for child
        """.trimIndent()

        val candidates = McpFieldExtractor.extract(prescription, "")
        assertEquals("Rekha Devi", candidates.name)
        assertEquals(28, candidates.age)
        assertEquals("Kalyanpur", candidates.village)
        assertEquals("Prescription", candidates.docType)
        assertNotNull(candidates.clinicalNotes)
        assertTrue(candidates.clinicalNotes!!.contains("IFA Tablets"))
        assertTrue(candidates.clinicalNotes!!.contains("Calcium tablets"))
        assertTrue(candidates.clinicalNotes!!.contains("ORS packets"))
    }

    @Test
    fun handlesEmptyOrUnmatchedTextGracefully() {
        val candidates = McpFieldExtractor.extract("Random text without form headers", "")
        assertNull(candidates.name)
        assertNull(candidates.age)
        assertNull(candidates.village)
        assertNull(candidates.clinicalNotes)
    }

    // ---- The strict rule: a value comes only from its label's own row. No guessing. ----

    @Test
    fun aLabelWithNothingAfterItStaysEmptyEvenIfAPlainWordFollows() {
        val c = McpFieldExtractor.extract("Name\nLovekesh\nVillage\nDelhi\nAge\n21", "")
        assertNull(c.name)
        assertNull(c.village)
        assertNull(c.age)
    }

    @Test
    fun aBareNumberIsNotTakenAsTheAge() {
        assertNull(McpFieldExtractor.extract("Name: Asha\n26", "").age)
    }

    @Test
    fun aValueStopsWhereTheNextLabelStarts() {
        val c = McpFieldExtractor.extract("", "नाम सुनीता देवी गांव रामपुर")
        assertEquals("Sunita Devi", c.name)
        assertEquals("Rampur", c.village)
    }

    @Test
    fun aReadingRowIsNeverTheNameOrVillage() {
        val c = McpFieldExtractor.extract("Name: Lovekesh Anand\nVillage: Rampur\nBP: 120/80\nHb: 9.5", "")
        assertEquals("Lovekesh Anand", c.name)
        assertEquals("Rampur", c.village)
        assertEquals("BP: 120/80, Hb: 9.5 g/dL", c.clinicalNotes)
    }

    @Test
    fun equalsSignAndSpacesAreAcceptedAfterLabels() {
        val c = McpFieldExtractor.extract("Name= Lovekesh Anand\nVillage = Rampur\nAge : 21\nBP = 120 / 80", "")
        assertEquals("Lovekesh Anand", c.name)
        assertEquals("Rampur", c.village)
        assertEquals(21, c.age)
        assertEquals("BP: 120/80", c.clinicalNotes)
    }

    @Test
    fun clinicalRowFillsNotesAlongsideReadings() {
        val c = McpFieldExtractor.extract("Clinical: Fever\nBP: 120/80", "")
        assertEquals("Fever, BP: 120/80", c.clinicalNotes)
    }

    @Test
    fun everyLabelInTheTeachingTableFillsItsField() {
        fun first(field: CardField) = CardTemplate.labels.getValue(field)
        // A plain word label from each list, written as "<label>: value".
        assertEquals("Asha", McpFieldExtractor.extract("Patient Name: Asha", "").name)
        assertEquals(30, McpFieldExtractor.extract("Age: 30", "").age)
        assertEquals("Rampur", McpFieldExtractor.extract("Address: Rampur", "").village)
        assertEquals("cough", McpFieldExtractor.extract("Symptoms: cough", "").clinicalNotes)
        assertTrue(first(CardField.NAME).isNotEmpty())
    }

    // ---- Handwriting ----

    @Test
    fun misreadHindiLabelsOnTheSameRowAreStillMatched() {
        // The phone's OCR read नाम as नाज and गांव as गांत.
        val c = McpFieldExtractor.extract("", "नाज : सुनेता देवी\nगांत : वेलशामपुथ\nउम्र : 2६")
        assertEquals("Suneta Devi", c.name)
        assertEquals("Velshamputh", c.village)
        assertEquals(26, c.age)
    }

    @Test
    fun bpWithMisreadSlashAndStrayMark() {
        val c = McpFieldExtractor.extract("Name: Lovekesh Anend\nviuage: Delhi\nAge: 21\nBP: 二 2001 1000", "")
        assertEquals("BP: 200/1000 (check value)", c.clinicalNotes)
        assertEquals("BP: 120/80", McpFieldExtractor.extract("BP: 120/80", "").clinicalNotes)
    }

    // ---- Real cards, from the box positions the phone's OCR returned ----

    private fun b(t: String, x0: Float, x1: Float, y0: Float, y1: Float) = TextBox(t, x0, x1, y0, y1)

    @Test
    fun firstRealCardFromItsBoxPositions() {
        val boxes = listOf(
            b("Anend", 655f, 840f, 1005f, 1055f), b("Lovekesh", 330f, 585f, 1030f, 1075f), b("Name", 65f, 200f, 1065f, 1110f),
            b("Delhi", 410f, 580f, 1135f, 1195f), b("viuage:", 105f, 325f, 1165f, 1215f), b("21", 485f, 555f, 1235f, 1280f),
            b("Age", 265f, 385f, 1235f, 1290f), b("BP", 250f, 335f, 1320f, 1360f), b("2001", 460f, 620f, 1320f, 1370f),
            b("1000", 650f, 790f, 1320f, 1370f),
        )
        val rows = RowBuilder.rows(boxes)
        assertEquals(listOf("Name: Lovekesh Anend", "viuage: Delhi", "Age: 21", "BP: 2001 1000"), rows)
        val c = McpFieldExtractor.extract(rows.joinToString("\n"), "")
        assertEquals("Lovekesh Anend", c.name)
        assertEquals("Delhi", c.village)
        assertEquals(21, c.age)
        assertEquals("BP: 200/1000 (check value)", c.clinicalNotes)
    }

    @Test
    fun secondRealCardWithAClinicalRow() {
        val boxes = listOf(
            b("Saeed", 985f, 1220f, 290f, 370f), b("Dua", 785f, 935f, 320f, 390f), b("Name", 580f, 715f, 340f, 400f),
            b("23", 920f, 1020f, 465f, 510f), b("Age", 610f, 740f, 470f, 560f),
            b("oknla", 1010f, 1230f, 585f, 635f), b("Village", 615f, 860f, 615f, 700f),
            b("Feves", 1050f, 1305f, 695f, 765f), b("Clinical", 605f, 885f, 745f, 810f),
        )
        val rows = RowBuilder.rows(boxes)
        assertEquals(listOf("Name: Dua Saeed", "Age: 23", "Village: oknla", "Clinical: Feves"), rows)
        val c = McpFieldExtractor.extract(rows.joinToString("\n"), "")
        assertEquals("Dua Saeed", c.name)
        assertEquals(23, c.age)
        assertEquals("oknla", c.village)
        assertEquals("Feves", c.clinicalNotes)
    }

    @Test
    fun aTiltedPhotoStillGroupsEachValueWithItsOwnLabel() {
        // Rows slope down to the right by 15 degrees: without levelling, "Asha" lands nearer the Age label.
        fun t(text: String, x0: Float, x1: Float, y0: Float, y1: Float) = TextBox(text, x0, x1, y0, y1, slopeDeg = 15f)
        val boxes = listOf(
            t("Name", 100f, 220f, 200f, 240f), t("Asha", 600f, 720f, 334f, 374f),
            t("Age", 100f, 200f, 320f, 360f), t("21", 600f, 660f, 454f, 494f),
        )
        assertEquals(listOf("Name: Asha", "Age: 21"), RowBuilder.rows(boxes))
    }

    @Test
    fun hindiLabelsTwoLettersOffAreMatchedWhenFollowedByAColon() {
        val c = McpFieldExtractor.extract("", "0 तान : सुनीता देवी\nगांत : रामपुर")
        assertEquals("Sunita Devi", c.name)
        assertEquals("Rampur", c.village)
    }

    @Test
    fun aPersonNamedRamAtTheStartOfARowIsNotMistakenForTheNameLabel() {
        val c = McpFieldExtractor.extract("", "राम कुमार\nगांव: रामपुर")
        assertNull(c.name)
        assertEquals("Rampur", c.village)
    }

    @Test
    fun anAgeWithADashInsideIsReadAsOneNumber() {
        assertEquals(21, McpFieldExtractor.extract("Age: 2-1", "").age)
    }

    @Test
    fun transliteratesCommonNames() {
        assertEquals("Sunita Devi", DevanagariTransliterator.toLatin("सुनीता देवी"))
        assertEquals("Rampur", DevanagariTransliterator.toLatin("रामपुर"))
        assertEquals("Kamla", DevanagariTransliterator.toLatin("कमला"))
        assertEquals("Pavan", DevanagariTransliterator.toLatin("पवन"))
        assertEquals("Rekha", DevanagariTransliterator.toLatin("रेखा"))
        assertEquals("Manoj", DevanagariTransliterator.toLatin("मनोज"))
    }
}
