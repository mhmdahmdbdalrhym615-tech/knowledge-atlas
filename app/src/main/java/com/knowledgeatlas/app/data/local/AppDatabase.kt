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