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
                            "src_openalex", "أوبن أليكس", "OpenAlex",
                            "فهرس مفتوح للنتاج العلمي والمؤلفين والجهات البحثية والمجلات، يفيد في اكتشاف الأبحاث وبياناتها الوصفية.",
                            "An open catalog of scholarly works, authors, institutions, and sources.",
                            2, "أبحاث,باحثون,جامعات,مجلات,بيانات علمية,openalex,research,scholarly",
                            "اكتشاف الأوراق العلمية والمؤلفين والمؤسسات البحثية وربط الأعمال العلمية ببياناتها.",
                            "ابحث بعنوان الدراسة أو اسم الباحث أو المؤسسة، ثم انتقل إلى سجل العمل ومصدره الأصلي.",
                            "FREE", false, "https://openalex.org", "2026-11-07", "Official OpenAlex website", "ACADEMIC"
                        ),
                        KnowledgeSource(
                            "src_doaj", "دليل الدوريات مفتوحة الوصول", "DOAJ",
                            "دليل مستقل للدوريات العلمية مفتوحة الوصول، يساعد في اكتشاف المجلات والمقالات وفق بياناتها المنشورة.",
                            "A curated directory of peer-reviewed open access journals.",
                            2, "دوريات,مجلات,أبحاث,وصول مفتوح,تحكيم,doaj,journals,open access",
                            "البحث عن الدوريات العلمية ومقالات الوصول المفتوح والتحقق من بيانات المجلة وسياسات النشر.",
                            "ابحث بموضوع الدراسة أو اسم المجلة، ثم راجع صفحة المجلة ومعلومات النشر والتحكيم قبل الاعتماد عليها.",
                            "FREE", false, "https://doaj.org", "2026-11-07", "Official DOAJ website", "ACADEMIC"
                        )
                    )
                    dao.insertSources(catalog)
                }
            }
        }
    }
}