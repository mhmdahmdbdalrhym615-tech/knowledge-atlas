package com.knowledgeatlas.app.search

import com.knowledgeatlas.app.data.model.KnowledgeSource
import java.text.Normalizer
import kotlin.math.ceil

/**
 * Offline catalogue matching.
 *
 * This is Arabic-aware lexical expansion, not an embedding model or a claim
 * of full semantic understanding. It makes common agricultural and technical
 * synonyms discoverable without a network connection.
 */
object AtlasLocalSearch {
    private val diacritics = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    private val tokensPattern = Regex("[a-z0-9]+|[\\u0600-\\u06FF]+")
    private val stopWords = setOf(
        "كيف", "كيفيه", "ازاي", "ماذا", "ايه", "هل", "ما", "هو", "هي",
        "في", "من", "الي", "على", "عن", "مع", "هذا", "هذه", "ذلك",
        "التي", "الذي", "و", "او", "ثم"
    )

    private val synonyms = mapOf(
        "تربه" to setOf("soil", "ارض", "اراضي", "land"),
        "ارض" to setOf("soil", "تربه", "اراضي", "land"),
        "ملوحه" to setOf("salinity", "salt", "املاح", "ملح", "soil salinity"),
        "ملح" to setOf("salinity", "salt", "املاح", "ملوحه"),
        "املاح" to setOf("salinity", "salt", "ملح", "ملوحه"),
        "خصوبه" to setOf("fertility", "fertilizer", "سماد", "تسميد", "soil fertility"),
        "سماد" to setOf("fertility", "fertilizer", "خصوبه", "تسميد"),
        "تسميد" to setOf("fertility", "fertilizer", "سماد", "خصوبه"),
        "استصلاح" to setOf("reclamation", "restoration", "remediation", "land reclamation"),
        "ري" to setOf("irrigation", "water", "مياه"),
        "مياه" to setOf("water", "irrigation", "ري"),
        "زراعه" to setOf("agriculture", "agricultural", "crop", "محاصيل", "نبات"),
        "محصول" to setOf("crop", "yield", "انتاج", "زراعه"),
        "محاصيل" to setOf("crop", "yield", "agriculture"),
        "برمجه" to setOf("programming", "software", "code", "كود"),
        "كود" to setOf("code", "programming", "software", "برمجه"),
        "ذكاء" to setOf("artificial", "intelligence", "machine", "learning", "ai"),
        "بيانات" to setOf("data", "dataset", "database", "احصاء"),
        "بحث" to setOf("research", "academic", "scientific", "دراسه"),
        "تعليم" to setOf("education", "course", "learning", "مقررات")
    )

    fun normalize(value: String): String {
        val noMarks = diacritics.replace(Normalizer.normalize(value, Normalizer.Form.NFKC), "")
            .replace('ـ', ' ')
        return noMarks
            .replace(Regex("[\\u0622\\u0623\\u0625\\u0671]"), "ا")
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .lowercase()
    }

    private fun tokenize(value: String): Set<String> = tokensPattern.findAll(normalize(value))
        .map { match ->
            val token = match.value
            if (token.length > 4 && token.startsWith("ال")) token.drop(2) else token
        }
        .filter { it.length > 1 && it !in stopWords }
        .toSet()

    /**
     * Keeps catalogue order for equal scores. Empty queries preserve the
     * original list, and non-matches are omitted only while a query is active.
     */
    fun rank(sources: List<KnowledgeSource>, query: String): List<KnowledgeSource> {
        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return sources

        val minimumMatches = maxOf(1, ceil(queryTerms.size * 0.34).toInt())
        return sources.mapIndexedNotNull { index, source ->
            val searchableText = listOf(
                source.nameAr,
                source.nameEn,
                source.shortDescriptionAr,
                source.shortDescriptionEn,
                source.tags,
                source.concreteOfferingAr,
                source.exactStepsToStartAr
            ).joinToString(" ")
            val corpus = tokenize(searchableText)

            var exactMatches = 0
            var synonymMatches = 0
            for (term in queryTerms) {
                when {
                    term in corpus -> exactMatches++
                    synonyms[term].orEmpty().any { alias ->
                        tokenize(alias).any { it in corpus }
                    } -> synonymMatches++
                }
            }
            val matchedTerms = exactMatches + synonymMatches
            if (matchedTerms < minimumMatches) null
            else Triple(source, exactMatches * 3 + synonymMatches * 2, index)
        }.sortedWith(
            compareByDescending<Triple<KnowledgeSource, Int, Int>> { it.second }
                .thenBy { it.third }
        ).map { it.first }
    }
}
