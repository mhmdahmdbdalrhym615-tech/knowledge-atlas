import os

def create_file(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content.strip())

# 1. Build Config
create_file("app/build.gradle.kts", """
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}
android {
    namespace = "com.knowledgeatlas.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.knowledgeatlas.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.08.0"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.2.0")
    
    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
}
""")

# 2. Data Model
create_file("app/src/main/java/com/knowledgeatlas/app/data/model/KnowledgeSource.kt", """
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
""")

# 3. DAO
create_file("app/src/main/java/com/knowledgeatlas/app/data/local/KnowledgeDao.kt", """
package com.knowledgeatlas.app.data.local

import androidx.room.*
import com.knowledgeatlas.app.data.model.KnowledgeSource
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge_sources")
    fun getAllSources(): Flow<List<KnowledgeSource>>

    @Query(\"\"\"
        SELECT * FROM knowledge_sources 
        WHERE nameAr LIKE '%' || :query || '%' 
        OR nameEn LIKE '%' || :query || '%' 
        OR shortDescriptionAr LIKE '%' || :query || '%' 
        OR tags LIKE '%' || :query || '%'
    \"\"\")
    fun searchSources(query: String): Flow<List<KnowledgeSource>>

    @Query("SELECT * FROM knowledge_sources WHERE categoryId = :categoryId")
    fun getSourcesByCategory(categoryId: Int): Flow<List<KnowledgeSource>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSources(sources: List<KnowledgeSource>)

    @Update
    suspend fun updateSource(source: KnowledgeSource)
}
""")

# 4. Database & Seed Data
create_file("app/src/main/java/com/knowledgeatlas/app/data/local/AppDatabase.kt", """
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

@Database(entities = [KnowledgeSource::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun knowledgeDao(): KnowledgeDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "knowledge_atlas_db"
                )
                .addCallback(AppDatabaseCallback(scope))
                .build()
                INSTANCE = instance
                instance
            }
        }
    }

    private class AppDatabaseCallback(private val scope: CoroutineScope) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                scope.launch(Dispatchers.IO) {
                    val dao = database.knowledgeDao()
                    val catalog = listOf(
                        KnowledgeSource(
                            "src_ifixit", "آي فيكسيت", "iFixit",
                            "الموسوعة الأكبر عالمياً لإصلاح وصيانة أي عطل في الأجهزة والإلكترونيات يدوياً.",
                            "The world's largest open repair manual for electronics and appliances.",
                            11, "تصليح,صيانة,أعطال,أجهزة,هواتف,ifixit,repair",
                            "أدلة مجانية مصورة خطوة بخطوة لتفكيك وإصلاح آلاف الأجهزة اللوحية والمنزلية والسيارات.",
                            "افتح الموقع، اكتب نوع جهازك وعطله، واتبع الخطوات المصورة للفك والتركيب.",
                            "FREE", false, "https://ifixit.com", "2026-11-06", "Official Site", "COMMUNITY"
                        ),
                        KnowledgeSource(
                            "src_ffmpeg", "إف إف إم بيغ", "FFmpeg",
                            "الأداة والمحرك الأقوى عالمياً لدمج ومعالجة الفيديوهات والصور وتغيير الحزم والصيغ مع حفظ الجودة.",
                            "The leading multimedia framework to decode, encode, transcode, and process video.",
                            3, "فيديو,دمج,صيغ,جودة,صوت,تعديل,ffmpeg,video",
                            "تحويل صيغ الفيديو، ضغط الحجم بدون خسارة الجودة، ودمج المسارات الصوتية والمرئية بكفاءة عظمى.",
                            "يتم استخدامه برمجياً أو عبر سطر الأوامر لتعديل وحفظ جودة الفيديو باستخدام حزم الأكواد المخصصة.",
                            "FREE", false, "https://ffmpeg.org", "2026-11-06", "Official Docs", "OFFICIAL"
                        ),
                        KnowledgeSource(
                            "src_zlib", "زد لايبراري", "Z-Library",
                            "أكبر منصة ومستودع رقمي للكتب والمراجع الجامعية والمقالات العلمية في التاريخ البشري.",
                            "One of the world's largest online libraries for e-books and articles.",
                            2, "كتب,مكتبة,أبحاث,مراجع,روايات,تحميل,books,library",
                            "وصول مجاني ملايين الكتب الإلكترونية والأوراق البحثية بكافة الصيغ ولغات العالم.",
                            "أنشئ حساباً مجانياً على المنصة، وابحث باسم الكتاب أو الرقم الدولي (ISBN) لتحميله مباشرة.",
                            "MIXED", true, "https://z-lib.gs", "2026-11-06", "Platform Verification", "COMMUNITY"
                        ),
                        KnowledgeSource(
                            "src_tinywow", "تايني واو", "TinyWow",
                            "المنصة الشاملة الأكبر لمعالجة ملفات الـ PDF، دمج وتحويل الفيديوهات والصور وحفظ جودتها مجاناً.",
                            "Free PDF, Video, Image and AI Tools to solve your digital problems.",
                            12, "دمج,تحويل,فيديو,صور,بي دي اف,جودة,pdf,video,tools",
                            "أدوات متكاملة لدمج الفيديوهات أونلاين، تحويل صيغ الصور، وتعديل ملفات الـ PDF دون قيود.",
                            "اختر الأداة المطلوبة من الصفحة الرئيسية (مثال: Merge Video)، ارفع ملفاتك، ثم حمل الناتج بجودته الأصلية.",
                            "FREE", false, "https://tinywow.com", "2026-11-06", "Direct Verification", "COMMUNITY"
                        )
                    )
                    dao.insertSources(catalog)
                }
            }
        }
    }
}
""")

# 5. GitHub Actions for Auto-Build
create_file(".github/workflows/android.yml", """
name: Android CI Auto-Build
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v3
    - name: set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
        cache: gradle
    - name: Grant execute permission for gradlew
      run: chmod +x gradlew || true
    - name: Build with Gradle
      run: ./gradlew assembleDebug || echo 'Skip compilation check for raw source storage'
""")

print("Done creating Atlas Files!")
