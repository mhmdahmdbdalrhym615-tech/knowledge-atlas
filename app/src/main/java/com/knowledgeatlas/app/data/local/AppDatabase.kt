package com.knowledgeatlas.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.knowledgeatlas.app.data.model.KnowledgeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Database(entities = [KnowledgeSource::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun knowledgeDao(): KnowledgeDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                val instance = Room.databaseBuilder(
                    appContext,
                    AppDatabase::class.java,
                    "knowledge_atlas_db"
                )
                    .addCallback(CatalogSeedCallback(appContext, scope))
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    private class CatalogSeedCallback(
        private val context: Context,
        private val scope: CoroutineScope
    ) : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            val preferences = context.getSharedPreferences("atlas_catalog", Context.MODE_PRIVATE)
            if (preferences.getInt("seed_version", 0) >= CATALOG_SEED_VERSION) return

            val database = INSTANCE ?: return
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val json = context.assets.open("knowledge_catalog.json")
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val root = JSONObject(json)
                    require(root.getInt("catalogVersion") == CATALOG_SEED_VERSION)
                    val records = root.getJSONArray("records")
                    val sources = records.toKnowledgeSources()
                    database.knowledgeDao().insertSourcesIfMissing(sources)
                    preferences.edit().putInt("seed_version", CATALOG_SEED_VERSION).apply()
                }
            }
        }

        private fun JSONArray.toKnowledgeSources(): List<KnowledgeSource> = buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val url = item.optString("officialUrl")
                if (!url.startsWith("https://")) continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                add(
                    KnowledgeSource(
                        id = id,
                        nameAr = item.optString("nameAr"),
                        nameEn = item.optString("nameEn"),
                        shortDescriptionAr = item.optString("shortDescriptionAr"),
                        shortDescriptionEn = item.optString("shortDescriptionEn"),
                        categoryId = item.optInt("categoryId"),
                        tags = item.optString("tags"),
                        concreteOfferingAr = item.optString("concreteOfferingAr"),
                        exactStepsToStartAr = item.optString("exactStepsToStartAr"),
                        accessType = item.optString("accessType", "UNKNOWN"),
                        hasArabicInterface = item.optBoolean("hasArabicInterface", false),
                        officialUrl = url,
                        lastVerifiedDate = item.optString("lastVerifiedDate"),
                        verificationSource = item.optString("verificationSource"),
                        confidenceLevel = item.optString("confidenceLevel", "REVIEW_REQUIRED"),
                        isFavorite = false,
                        userNotes = null
                    )
                )
            }
        }

        private companion object {
            const val CATALOG_SEED_VERSION = 1
        }
    }
}
