package com.knowledgeatlas.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "knowledge_sources")
data class KnowledgeSource(
    @PrimaryKey val id: String,
    val nameAr: String,
    val nameEn: String,
    val shortDescriptionAr: String,
    val shortDescriptionEn: String,
    val categoryId: Int,
    val tags: String,
    val concreteOfferingAr: String,
    val exactStepsToStartAr: String,
    val accessType: String,
    val hasArabicInterface: Boolean,
    val officialUrl: String,
    val lastVerifiedDate: String,
    val verificationSource: String,
    val confidenceLevel: String,
    val isFavorite: Boolean = false,
    val userNotes: String? = null
)