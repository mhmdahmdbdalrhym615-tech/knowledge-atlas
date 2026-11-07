package com.knowledgeatlas.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.knowledgeatlas.app.data.model.KnowledgeSource
import com.knowledgeatlas.app.search.AtlasLocalSearch
import com.knowledgeatlas.app.search.AtlasSearchGateway
import com.knowledgeatlas.app.search.WebSearchHit
import com.knowledgeatlas.app.search.WebSearchResult
import com.knowledgeatlas.app.update.DirectUpdateManager
import com.knowledgeatlas.app.update.UpdateResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val AtlasInk = Color(0xFF101827)
private val AtlasMidnight = Color(0xFF17243A)
private val AtlasTeal = Color(0xFF36D6C3)
private val AtlasBlue = Color(0xFF6E8BFF)
private val AtlasGold = Color(0xFFF1C77A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = (application as AtlasApplication).database.knowledgeDao()
        val updateManager = DirectUpdateManager(this)
        val searchGateway = AtlasSearchGateway()
        val webSearchState = MutableStateFlow<AtlasWebSearchUiState?>(null)

        setContent {
            val darkColors = darkColorScheme(
                primary = AtlasTeal,
                onPrimary = AtlasInk,
                secondary = AtlasBlue,
                background = AtlasInk,
                surface = AtlasMidnight,
                surfaceVariant = Color(0xFF24334A),
                onSurface = Color(0xFFF3F6FC),
                onBackground = Color(0xFFF3F6FC),
                primaryContainer = Color(0xFF1D3B49),
                onPrimaryContainer = Color(0xFFE4FFFB)
            )
            MaterialTheme(colorScheme = darkColors) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val sources by dao.getAllSources().collectAsState(initial = emptyList())
                    val webSearch by webSearchState.collectAsState()
                    AtlasHome(
                        sources = sources,
                        webSearchState = webSearch,
                        onSearchWeb = { rawQuery ->
                            val requestedQuery = rawQuery.trim()
                            if (requestedQuery.isNotEmpty()) {
                                webSearchState.value = AtlasWebSearchUiState(
                                    query = requestedQuery,
                                    isLoading = true
                                )
                                lifecycleScope.launch {
                                    val result = searchGateway.search(requestedQuery)
                                    if (webSearchState.value?.query == requestedQuery) {
                                        webSearchState.value = AtlasWebSearchUiState(
                                            query = requestedQuery,
                                            result = result
                                        )
                                    }
                                }
                            }
                        },
                        onToggleFavorite = { source ->
                            lifecycleScope.launch { dao.updateSource(source.copy(isFavorite = !source.isFavorite)) }
                        },
                        onCheckUpdate = {
                            lifecycleScope.launch {
                                when (val result = updateManager.checkAndDownload()) {
                                    UpdateResult.Disabled -> Toast.makeText(this@MainActivity, "التحديث المباشر غير مفعّل لهذه النسخة.", Toast.LENGTH_LONG).show()
                                    UpdateResult.UpToDate -> Toast.makeText(this@MainActivity, "أنت تستخدم أحدث إصدار.", Toast.LENGTH_SHORT).show()
                                    UpdateResult.PermissionRequired -> Toast.makeText(this@MainActivity, "اسمح بالتثبيت من هذا المصدر، ثم أعد فحص التحديث.", Toast.LENGTH_LONG).show()
                                    is UpdateResult.ReadyToInstall -> updateManager.requestUserInstall(result)
                                    is UpdateResult.Failed -> Toast.makeText(this@MainActivity, result.reason, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

private data class AtlasWebSearchUiState(
    val query: String,
    val result: WebSearchResult? = null,
    val isLoading: Boolean = false
)

private enum class AtlasTab { DISCOVER, SAVED, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AtlasHome(
    sources: List<KnowledgeSource>,
    webSearchState: AtlasWebSearchUiState?,
    onSearchWeb: (String) -> Unit,
    onToggleFavorite: (KnowledgeSource) -> Unit,
    onCheckUpdate: () -> Unit
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf<Int?>(null) }
    var searchText by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(AtlasTab.DISCOVER) }
    var selectedSource by remember { mutableStateOf<KnowledgeSource?>(null) }
    val categories = listOf(
        1 to "التعليم", 2 to "الأبحاث", 3 to "البرمجة", 4 to "الذكاء الاصطناعي",
        5 to "الزراعة", 6 to "العلوم", 7 to "الأعمال", 8 to "البيانات",
        9 to "الصحة العامة", 10 to "الفكر والتاريخ", 11 to "الهندسة والطاقة",
        12 to "الإنتاجية"
    )
    val tabSources = when (activeTab) {
        AtlasTab.SAVED -> sources.filter { it.isFavorite }
        else -> sources
    }
    val rankedSources = AtlasLocalSearch.rank(tabSources, searchText)
    val filteredSources = rankedSources.filter { source ->
        selectedCategory == null || source.categoryId == selectedCategory
    }
    val activeWebSearch = webSearchState?.takeIf { it.query == searchText.trim() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                                .background(Brush.linearGradient(listOf(AtlasTeal, AtlasBlue))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Language, contentDescription = null, tint = AtlasInk)
                        }
                        Spacer(Modifier.width(11.dp))
                        Column {
                            Text("أطلس المعرفة", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Text("KNOWLEDGE ATLAS", fontSize = 9.sp, letterSpacing = 2.sp, color = AtlasTeal)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { activeTab = AtlasTab.SETTINGS }, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Outlined.Settings, contentDescription = "فتح الإعدادات", tint = Color.White)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = AtlasMidnight,
                contentColor = Color.White,
                windowInsets = WindowInsets.navigationBars
            ) {
                NavigationBarItem(
                    selected = activeTab == AtlasTab.DISCOVER,
                    onClick = { activeTab = AtlasTab.DISCOVER },
                    icon = { Icon(Icons.Outlined.Explore, contentDescription = null) },
                    label = { Text("اكتشف") }
                )
                NavigationBarItem(
                    selected = activeTab == AtlasTab.SAVED,
                    onClick = { activeTab = AtlasTab.SAVED },
                    icon = { Icon(Icons.Outlined.Bookmark, contentDescription = null) },
                    label = { Text("المحفوظات") }
                )
                NavigationBarItem(
                    selected = activeTab == AtlasTab.SETTINGS,
                    onClick = { activeTab = AtlasTab.SETTINGS },
                    icon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null) },
                    label = { Text("التحديثات") }
                )
            }
        }
    ) { insets ->
        AnimatedContent(
            targetState = activeTab,
            transitionSpec = {
                (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 18 }) togetherWith
                    (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 24 })
            },
            label = "atlas-main-navigation"
        ) { tab ->
            when (tab) {
                AtlasTab.DISCOVER, AtlasTab.SAVED -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(insets),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp)
                ) {
                    item {
                        TemporalHero(
                            title = if (tab == AtlasTab.SAVED) "مكتبتك الشخصية" else "المعرفة بلا حدود",
                            subtitle = if (tab == AtlasTab.SAVED) "كل ما حفظته في مكان واحد." else "اكتشف مصادر موثوقة. افهم الفكرة. ابدأ من المكان الصحيح.",
                            count = sources.size
                        )
                    }
                    if (tab == AtlasTab.DISCOVER) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("اعثر على المصادر بالكلمات أو المصطلحات", fontWeight = FontWeight.SemiBold, color = Color.White)
                                OutlinedTextField(
                                    value = searchText,
                                    onValueChange = { searchText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(18.dp),
                                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                    placeholder = { Text("مثال: كيف أحسّن خصوبة التربة؟") },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(
                                        onSearch = { onSearchWeb(searchText) }
                                    )
                                )
                                Button(
                                    onClick = { onSearchWeb(searchText) },
                                    enabled = searchText.isNotBlank() && activeWebSearch?.isLoading != true,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (activeWebSearch?.isLoading == true) "جارٍ البحث في الويب..."
                                        else "ابحث في الويب الأوسع"
                                    )
                                }
                            }
                        }
                        activeWebSearch?.let { searchState ->
                            if (searchState.isLoading) {
                                item {
                                    Text(
                                        "جارٍ البحث عن نتائج الويب وفق كلمات الاستعلام...",
                                        color = AtlasTeal,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                            searchState.result?.let { result ->
                                when (result) {
                                    is WebSearchResult.Success -> {
                                        item {
                                            Text(
                                                "نتائج البحث من الويب (${result.hits.size})",
                                                color = AtlasTeal,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        if (result.hits.isEmpty()) {
                                            item {
                                                EmptyState(
                                                    "لم تظهر نتائج",
                                                    "جرّب صياغة السؤال بطريقة أخرى."
                                                )
                                            }
                                        } else {
                                            items(result.hits, key = { it.url }) { hit ->
                                                WebSearchResultCard(
                                                    hit = hit,
                                                    onOpen = {
                                                        runCatching {
                                                            context.startActivity(
                                                                Intent(Intent.ACTION_VIEW, Uri.parse(hit.url))
                                                            )
                                                        }.onFailure {
                                                            Toast.makeText(
                                                                context,
                                                                "تعذر فتح رابط المصدر.",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    WebSearchResult.NotConfigured -> item {
                                        EmptyState(
                                            "محرك البحث غير مربوط بعد",
                                            "البحث داخل مكتبة أطلس يعمل محلياً. البحث العالمي سيعمل بعد إعداد عنوان Search API على الخادم."
                                        )
                                    }
                                    is WebSearchResult.Failure -> item {
                                        EmptyState(
                                            "تعذر إكمال البحث",
                                            result.message
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("استكشف المجالات", fontWeight = FontWeight.SemiBold, color = Color.White)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item {
                                        FilterChip(
                                            selected = selectedCategory == null,
                                            onClick = { selectedCategory = null },
                                            label = { Text("الكل") }
                                        )
                                    }
                                    items(categories) { category ->
                                        FilterChip(
                                            selected = selectedCategory == category.first,
                                            onClick = { selectedCategory = if (selectedCategory == category.first) null else category.first },
                                            label = { Text(category.second) }
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("مصادر تستحق الاستكشاف", fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                                Text("${filteredSources.size} مصدر", color = AtlasTeal, fontSize = 12.sp)
                            }
                        }
                    } else {
                        item { Text("المصادر المحفوظة", fontWeight = FontWeight.Bold, color = Color.White) }
                    }
                    if (filteredSources.isEmpty()) {
                        item {
                            EmptyState(
                                title = if (tab == AtlasTab.SAVED) "لم تحفظ مصادر بعد" else "لا توجد نتائج مطابقة",
                                subtitle = if (tab == AtlasTab.SAVED) "اضغط علامة الحفظ على أي مصدر لإضافته هنا." else "جرّب كلمة أخرى أو اختر مجالاً مختلفاً."
                            )
                        }
                    } else {
                        items(filteredSources, key = { it.id }) { source ->
                            SourceCard(
                                source = source,
                                onOpen = { selectedSource = source },
                                onFavorite = { onToggleFavorite(source) }
                            )
                        }
                    }
                }

                AtlasTab.SETTINGS -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(insets),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item { TemporalHero("مركز النظام", "تحديثات التطبيق وإدارة نسختك.", sources.size) }
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = AtlasMidnight),
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = AtlasTeal, modifier = Modifier.size(30.dp))
                                Text("تحديث أطلس المعرفة", fontWeight = FontWeight.Bold, color = Color.White)
                                Text("تحقق من وجود إصدار أحدث. التثبيت النهائي يخضع لموافقة Android.", color = Color.LightGray)
                                Button(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth()) {
                                    Text("فحص التحديثات")
                                }
                            }
                        }
                    }
                    item {
                        EmptyState("إعدادات أطلس", "سيتم توسيع إعدادات اللغة والمظهر وإمكانية الوصول في مرحلة لاحقة.")
                    }
                }
            }
        }
    }

    selectedSource?.let { source ->
        AlertDialog(
            onDismissRequest = { selectedSource = null },
            containerColor = AtlasMidnight,
            title = { Text(source.nameAr.ifBlank { source.nameEn }, color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(source.shortDescriptionAr, color = Color.White)
                    Text("ماذا يقدم؟", color = AtlasTeal, fontWeight = FontWeight.Bold)
                    Text(source.concreteOfferingAr, color = Color.LightGray)
                    Text("كيف تبدأ؟", color = AtlasTeal, fontWeight = FontWeight.Bold)
                    Text(source.exactStepsToStartAr, color = Color.LightGray)
                    Text(
                        if (source.lastVerifiedDate.isBlank()) "لم يُسجل تحقق فردي بعد"
                        else "آخر تحقق: ${source.lastVerifiedDate}",
                        color = if (source.lastVerifiedDate.isBlank()) AtlasGold else Color.LightGray,
                        fontSize = 12.sp
                    )
                    Text("نوع الوصول: ${accessTypeLabel(source.accessType)}", color = Color.LightGray, fontSize = 12.sp)
                    Text("تصنيف الجهة: ${confidenceLabel(source.confidenceLevel)}", color = Color.LightGray, fontSize = 12.sp)
                    Text("مرجع التحقق: ${source.verificationSource}", color = Color.LightGray, fontSize = 12.sp)
                    if (source.confidenceLevel == "REVIEW_REQUIRED") {
                        Text("تنبيه: هذا السجل مرشح أولي ولم يكتمل التحقق الفردي منه.", color = AtlasGold, fontSize = 12.sp)
                    }
                    Text(if (source.hasArabicInterface) "واجهة عربية متاحة" else "الواجهة العربية غير مؤكدة", color = Color.LightGray, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = source.officialUrl
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    selectedSource = null
                }) { Text("فتح الموقع") }
            },
            dismissButton = {
                TextButton(onClick = { selectedSource = null }) { Text("إغلاق") }
            }
        )
    }
}

@Composable
private fun TemporalHero(title: String, subtitle: String, count: Int) {
    val transition = rememberInfiniteTransition(label = "atlas-time-motion")
    val glow by transition.animateFloat(
        initialValue = 0.78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "hero-glow"
    )
    val scale by animateFloatAsState(
        targetValue = if (title.isNotBlank()) 1f else 0.98f,
        animationSpec = tween(450),
        label = "hero-scale"
    )
    Box(
        Modifier.fillMaxWidth().scale(scale).clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF183B4B).copy(alpha = glow),
                        Color(0xFF26345D),
                        Color(0xFF282747)
                    )
                )
            )
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = AtlasTeal.copy(alpha = 0.15f), shape = CircleShape) {
                    Text("  LIVE KNOWLEDGE  ", color = AtlasTeal, fontSize = 9.sp, modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp))
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(8.dp).clip(CircleShape).background(AtlasTeal.copy(alpha = glow)))
            }
            Text(title, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 34.sp)
            Text(subtitle, color = Color(0xFFD1DCEB), fontSize = 14.sp, lineHeight = 22.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$count", color = AtlasGold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(7.dp))
                Text("مصدر في مكتبتك الحالية", color = Color.LightGray, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SourceCard(
    source: KnowledgeSource,
    onOpen: () -> Unit,
    onFavorite: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val elevationScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = tween(160),
        label = "source-card-press"
    )
    Card(
        modifier = Modifier.fillMaxWidth().scale(elevationScale).animateContentSize()
            .clickable { onOpen() },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AtlasMidnight),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF24566A), Color(0xFF353A68)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(source.nameEn.take(1).uppercase(), color = AtlasTeal, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(source.nameAr.ifBlank { source.nameEn }, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(source.nameEn, color = Color(0xFF9EADC2), fontSize = 11.sp)
                }
                TextButton(onClick = onFavorite) {
                    Icon(
                        Icons.Outlined.Bookmark,
                        contentDescription = if (source.isFavorite) "إزالة من المحفوظات" else "حفظ المصدر",
                        tint = if (source.isFavorite) AtlasGold else Color.LightGray
                    )
                }
            }
            Text(source.shortDescriptionAr, color = Color(0xFFD0D9E6), fontSize = 13.sp, lineHeight = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFF1C4650), shape = RoundedCornerShape(50)) {
                    Text(categoryLabel(source.categoryId), color = AtlasTeal, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
                Text(
                    if (source.lastVerifiedDate.isBlank()) "قيد المراجعة"
                    else "تحقق: ${source.lastVerifiedDate}",
                    color = if (source.lastVerifiedDate.isBlank()) AtlasGold else Color(0xFF9EADC2),
                    fontSize = 10.sp
                )
                Spacer(Modifier.weight(1f))
                Text("التفاصيل ←", color = AtlasTeal, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun categoryLabel(id:Int):String=when(id) {
  1->"التعليم"; 2->"الأبحاث"; 3->"البرمجة"; 4->"الذكاء الاصطناعي"
  5->"الزراعة"; 6->"العلوم"; 7->"الأعمال"; 8->"البيانات"
  9->"الصحة العامة"; 10->"الفكر والتاريخ"; 11->"الهندسة والطاقة"; 12->"الإنتاجية"
  else->"مجال آخر"
}
private fun accessTypeLabel(value:String):String=when(value.uppercase()) {
  "FREE"->"مجاني"; "PAID"->"مدفوع"; "MIXED"->"مجاني ومدفوع"
  "REGISTRATION"->"يتطلب تسجيلاً"; else->"غير محدد"
}
private fun confidenceLabel(value:String):String=when(value.uppercase()) {
  "REVIEW_REQUIRED"->"قيد المراجعة"; "OFFICIAL"->"جهة رسمية / موقع الجهة"; "ACADEMIC"->"أكاديمي"
  "GOVERNMENT"->"حكومي"; "COMMUNITY"->"مجتمعي"; else->"غير مصنف"
}

@Composable
private fun WebSearchResultCard(
    hit: WebSearchHit,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AtlasMidnight),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(hit.title, color = Color.White, fontWeight = FontWeight.Bold)
            if (hit.snippet.isNotBlank()) {
                Text(hit.snippet, color = Color(0xFFD0D9E6), fontSize = 13.sp, lineHeight = 20.sp)
            }
            Text(
                "${hit.publisher.ifBlank { "الجهة الناشرة غير محددة" }} • ${hit.publisherType}",
                color = AtlasTeal,
                fontSize = 11.sp
            )
            if (hit.trustNote.isNotBlank()) {
                Text(hit.trustNote, color = Color.LightGray, fontSize = 10.sp, lineHeight = 15.sp)
            }
            hit.retrievedAt?.let {
                Text("وقت جلب النتيجة: $it", color = Color.LightGray, fontSize = 10.sp)
            }
            Text(
                "درجة صلة آلية (لترتيب النتائج): ${(hit.relevance * 100).toInt()}%",
                color = AtlasGold,
                fontSize = 11.sp
            )
            Text("فتح المصدر ↗", color = AtlasTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = AtlasMidnight
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = AtlasTeal, modifier = Modifier.size(30.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.LightGray, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}
