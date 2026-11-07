package com.knowledgeatlas.app.search

import com.knowledgeatlas.app.data.model.KnowledgeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasLocalSearchTest {
    private fun source(id: String, name: String, desc: String, tags: String) = KnowledgeSource(
        id = id,
        nameAr = name,
        nameEn = name,
        shortDescriptionAr = desc,
        shortDescriptionEn = "",
        categoryId = 5,
        tags = tags,
        concreteOfferingAr = "",
        exactStepsToStartAr = "",
        accessType = "UNKNOWN",
        hasArabicInterface = false,
        officialUrl = "https://example.org/$id",
        lastVerifiedDate = "",
        verificationSource = "",
        confidenceLevel = "REVIEW_REQUIRED"
    )

    @Test
    fun normalizesArabicAlefTaMarbutaAndDiacritics() {
        assertEquals("اصلاح التربه", AtlasLocalSearch.normalize("إِصْلاحُ التُّرْبَة"))
    }

    @Test
    fun matchesArabicAgriculturalQuestionThroughSynonyms() {
        val soil = source(
            "soil",
            "مصادر علوم التربة",
            "مراجع عن ملوحة الأراضي وخصوبة التربة",
            "soil salinity land fertility"
        )
        val programming = source(
            "code",
            "تعلم البرمجة",
            "مصادر تطوير البرامج",
            "software programming"
        )

        val result = AtlasLocalSearch.rank(
            listOf(programming, soil),
            "كيف أتعامل مع ملوحة الأرض؟"
        )

        assertEquals(listOf("soil"), result.map { it.id })
    }

    @Test
    fun searchesTechnicalSynonymsOffline() {
        val source = source(
            "dev",
            "دليل هندسة البرمجيات",
            "تعلم تطوير البرامج وبناء التطبيقات",
            "software programming code"
        )

        assertEquals(
            listOf("dev"),
            AtlasLocalSearch.rank(listOf(source), "البرمجة").map { it.id }
        )
    }

    @Test
    fun emptyQueryPreservesCatalogueOrder() {
        val items = listOf(
            source("a", "الأول", "وصف", ""),
            source("b", "الثاني", "وصف", "")
        )
        assertTrue(AtlasLocalSearch.rank(items, "   ") === items)
    }
}
