package com.knowledgeatlas.app.data.local

import androidx.room.*
import com.knowledgeatlas.app.data.model.KnowledgeSource
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge_sources")
    fun getAllSources(): Flow<List<KnowledgeSource>>

    @Query("""
        SELECT * FROM knowledge_sources 
        WHERE nameAr LIKE '%' || :query || '%' 
        OR nameEn LIKE '%' || :query || '%' 
        OR shortDescriptionAr LIKE '%' || :query || '%' 
        OR tags LIKE '%' || :query || '%'
    """)
    fun searchSources(query: String): Flow<List<KnowledgeSource>>

    @Query("SELECT * FROM knowledge_sources WHERE categoryId = :categoryId")
    fun getSourcesByCategory(categoryId: Int): Flow<List<KnowledgeSource>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSources(sources: List<KnowledgeSource>)

    @Update
    suspend fun updateSource(source: KnowledgeSource)
}