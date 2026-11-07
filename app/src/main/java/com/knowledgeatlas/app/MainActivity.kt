package com.knowledgeatlas.app

import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.knowledgeatlas.app.data.model.KnowledgeSource
import com.knowledgeatlas.app.search.AtlasLocalSearch
import com.knowledgeatlas.app.search.AtlasSearchGateway
import com.knowledgeatlas.app.search.WebSearchHit
import com.knowledgeatlas.app.search.WebSearchResult
import com.knowledgeatlas.app.update.DirectUpdateManager
import com.knowledgeatlas.app.update.UpdateResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val AtlasInk = Color(0xFF080D19)
private val AtlasMidnight = Color(0xFF111B30)
private val AtlasPanel = Color(0xFF17243B)
private val AtlasTeal = Color(0xFF42E8D1)
private val AtlasBlue = Color(0xFF7894FF)
private val AtlasViolet = Color(0xFFB18CFF)
private val AtlasGold = Color(0xFFFFD27A)
private val AtlasMuted = Color(0xFF9EADC5)

private data class AtlasCategory(val id: Int, val title: String, val symbol: String, val color: Color)

private val AtlasCategories = listOf(
    AtlasCategory(1, "التعليم", "🎓", Color(0xFF54D6C7)),
    AtlasCategory(2, "الأبحاث", "🔬", Color(0xFF8CA7FF)),
    AtlasCategory(3, "البرمجة", "⌘", Color(0xFF63C8FF)),
    AtlasCategory(4, "الذكاء الاصطناعي", "✦", Color(0xFFB18CFF)),
    AtlasCategory(5, "الزراعة", "🌱", Color(0xFF83D88A)),
    AtlasCategory(6, "العلوم", "⚛", Color(0xFF54D6E8)),
    AtlasCategory(7, "الأعمال", "◈", Color(0xFFFFC477)),
    AtlasCategory(8, "البيانات", "▥", Color(0xFF6EA8FF)),
    AtlasCategory(9, "الصحة العامة", "✚", Color(0xFFFF8FA3)),
    AtlasCategory(10, "الفكر والتاريخ", "⌛", Color(0xFFD6B47C)),
    AtlasCategory(11, "الهندسة والطاقة", "⚙", Color(0xFF9AC7FF)),
    AtlasCategory(12, "الإنتاجية", "◉", Color(0xFFB8A0FF))
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = (application as AtlasApplication).database.knowledgeDao()
        val updateManager = DirectUpdateManager(this)
        val searchGateway = AtlasSearchGateway()
        val webSearchState = MutableStateFlow<AtlasWebSearchUiState?>(null)

        setContent {
            var accent by remember { mutableStateOf(AtlasTeal) }
            MaterialTheme(
                colorScheme = androidx.compose.material3.darkColorScheme(
                    primary = accent,
                    onPrimary = AtlasInk,
                    secondary = AtlasBlue,
                    background = AtlasInk,
                    surface = AtlasPanel,
                    surfaceVariant = Color(0xFF26334C),
                    onSurface = Color(0xFFF4F7FF),
                    onBackground = Color(0xFFF4F7FF),
                    primaryContainer = Color(0xFF1B3D45),
                    onPrimaryContainer = Color(0xFFE7FFFA)
                )
            ) {
                val sources by dao.getAllSources().collectAsState(initial = emptyList())
                val webSearch by webSearchState.collectAsState()
                AtlasHome(
                    sources = sources,
                    accent = accent,
                    onAccentChange = { accent = it },
                    webSearchState = webSearch,
                    onSearchWeb = { rawQuery ->
                        val query = rawQuery.trim()
                        if (query.isNotEmpty()) {
                            webSearchState.value = AtlasWebSearchUiState(query = query, isLoading = true)
                            lifecycleScope.launch {
                                val result = searchGateway.search(query)
                                if (webSearchState.value?.query == query) {
                                    webSearchState.value = AtlasWebSearchUiState(query = query, result = result)
                                }
                            }
                        }
                    },
                    onUpdateSource = { source ->
                        lifecycleScope.launch { dao.updateSource(source) }
                    },
                    onCheckUpdate = {
                        lifecycleScope.launch {
                            when (val result = updateManager.checkAndDownload()) {
                                UpdateResult.Disabled -> android.widget.Toast.makeText(this@MainActivity, "التحديث المباشر غير مفعّل لهذه النسخة.", android.widget.Toast.LENGTH_LONG).show()
                                UpdateResult.UpToDate -> android.widget.Toast.makeText(this@MainActivity, "أنت تستخدم أحدث إصدار.", android.widget.Toast.LENGTH_SHORT).show()
                                UpdateResult.PermissionRequired -> android.widget.Toast.makeText(this@MainActivity, "اسمح بالتثبيت من هذا المصدر، ثم أعد فحص التحديث.", android.widget.Toast.LENGTH_LONG).show()
                                is UpdateResult.ReadyToInstall -> updateManager.requestUserInstall(result)
                                is UpdateResult.Failed -> android.widget.Toast.makeText(this@MainActivity, result.reason, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
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

@Composable
private fun AtlasHome(
    sources: List<KnowledgeSource>,
    accent: Color,
    onAccentChange: (Color) -> Unit,
    webSearchState: AtlasWebSearchUiState?,
    onSearchWeb: (String) -> Unit,
    onUpdateSource: (KnowledgeSource) -> Unit,
    onCheckUpdate: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf<Int?>(null) }
    var searchText by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf(AtlasTab.DISCOVER) }
    var selectedSource by remember { mutableStateOf<KnowledgeSource?>(null) }
    var browserPage by remember { mutableStateOf<Pair<String, String>?>(null) }

    BackHandler(enabled = selectedSource != null && browserPage == null) { selectedSource = null }
    val tabSources = if (activeTab == AtlasTab.SAVED) sources.filter { it.isFavorite } else sources
    val rankedSources = AtlasLocalSearch.rank(tabSources, searchText)
    val filteredSources = rankedSources.filter { selectedCategory == null || it.categoryId == selectedCategory }
    val activeWebSearch = webSearchState?.takeIf { it.query == searchText.trim() }

    Scaffold(
        containerColor = AtlasInk,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (browserPage == null && selectedSource == null) {
                AtlasBottomBar(activeTab = activeTab, onSelect = { activeTab = it })
            }
        }
    ) { padding ->
        when {
            browserPage != null -> {
                val page = browserPage!!
                InAppBrowserScreen(
                    url = page.first,
                    title = page.second,
                    accent = accent,
                    onClose = { browserPage = null }
                )
            }
            selectedSource != null -> SourceDetailScreen(
                source = selectedSource!!,
                accent = accent,
                onBack = { selectedSource = null },
                onFavorite = {
                    val updated = selectedSource!!.copy(isFavorite = !selectedSource!!.isFavorite)
                    selectedSource = updated
                    onUpdateSource(updated)
                },
                onSaveNotes = { notes ->
                    val updated = selectedSource!!.copy(userNotes = notes)
                    selectedSource = updated
                    onUpdateSource(updated)
                },
                onOpenWebsite = { url, title -> browserPage = url to title }
            )
            else -> AnimatedContent(
                targetState = activeTab,
                modifier = Modifier.fillMaxSize().padding(padding),
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 24 }) togetherWith
                        (fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 32 })
                },
                label = "atlas-tabs"
            ) { tab ->
                when (tab) {
                    AtlasTab.DISCOVER, AtlasTab.SAVED -> DiscoverScreen(
                        tab = tab,
                        sources = sources,
                        filteredSources = if (tab == AtlasTab.SAVED) filteredSources else filteredSources,
                        selectedCategory = selectedCategory,
                        onCategory = { selectedCategory = it },
                        searchText = searchText,
                        onSearchText = { searchText = it },
                        onSearchWeb = { onSearchWeb(searchText) },
                        webSearch = activeWebSearch,
                        accent = accent,
                        onOpenSource = { selectedSource = it },
                        onOpenWeb = { hit -> browserPage = hit.url to hit.title },
                        onFavorite = { source -> onUpdateSource(source.copy(isFavorite = !source.isFavorite)) }
                    )
                    AtlasTab.SETTINGS -> SettingsScreen(
                        count = sources.size,
                        accent = accent,
                        onAccentChange = onAccentChange,
                        onCheckUpdate = onCheckUpdate
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverScreen(
    tab: AtlasTab,
    sources: List<KnowledgeSource>,
    filteredSources: List<KnowledgeSource>,
    selectedCategory: Int?,
    onCategory: (Int?) -> Unit,
    searchText: String,
    onSearchText: (String) -> Unit,
    onSearchWeb: () -> Unit,
    webSearch: AtlasWebSearchUiState?,
    accent: Color,
    onOpenSource: (KnowledgeSource) -> Unit,
    onOpenWeb: (WebSearchHit) -> Unit,
    onFavorite: (KnowledgeSource) -> Unit
) {
    val savedCount = sources.count { it.isFavorite }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 17.dp, end = 17.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        item {
            AtlasTopHeader(accent = accent, isSaved = tab == AtlasTab.SAVED)
        }
        if (tab == AtlasTab.DISCOVER) {
            item {
                AtlasOceanCityPortal(
                    accent = accent,
                    sourceCount = sources.size,
                    onBuildingSelected = { id -> onCategory(if (id == 0) null else id) }
                )
            }
        } else {
            item {
                AtlasHero(
                    count = sources.size,
                    savedCount = savedCount,
                    accent = accent,
                    title = "مساحتك المعرفية",
                    subtitle = "مصادرك التي اخترت الاحتفاظ بها."
                )
            }
        }
        if (tab == AtlasTab.DISCOVER) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeading("ابحث في أطلس", "بحث محلي + بحث ويب")
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = onSearchText,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = accent) },
                        placeholder = { Text("مثال: استصلاح التربة الملحية") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearchWeb() })
                    )
                    GlowActionButton(
                        label = if (webSearch?.isLoading == true) "جارٍ البحث في الويب..." else "ابحث في الويب",
                        accent = accent,
                        enabled = searchText.isNotBlank() && webSearch?.isLoading != true,
                        onClick = onSearchWeb
                    )
                    Text("النتائج المحلية تظهر تلقائياً أثناء الكتابة.", color = AtlasMuted, fontSize = 11.sp)
                }
            }
            if (webSearch != null) {
                when {
                    webSearch.isLoading -> item { Text("يتم جمع النتائج وترتيبها...", color = accent, fontSize = 13.sp) }
                    webSearch.result is WebSearchResult.Success -> {
                        val hits = (webSearch.result as WebSearchResult.Success).hits
                        item { SectionHeading("نتائج الويب", hits.size.toString() + " نتيجة") }
                        if (hits.isEmpty()) item { EmptyState("لا توجد نتائج", "جرّب مصطلحاً آخر أو صياغة أدق.") }
                        else items(hits, key = { it.url }) { hit ->
                            WebHitCard(hit = hit, accent = accent, onOpen = { onOpenWeb(hit) })
                        }
                    }
                    webSearch.result is WebSearchResult.NotConfigured -> item {
                        EmptyState("البحث العالمي غير متصل", "المكتبة المحلية تعمل دون إنترنت. يلزم ضبط خادم البحث لتفعيل نتائج الويب.")
                    }
                    webSearch.result is WebSearchResult.Failure -> item {
                        EmptyState("تعذر إكمال البحث", (webSearch.result as WebSearchResult.Failure).message)
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeading("استكشف المجالات", "12 مجالاً")
                }
            }
            items(AtlasCategories.chunked(2)) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    CategoryTile(
                        category = pair[0],
                        selected = selectedCategory == pair[0].id,
                        accent = accent,
                        modifier = Modifier.weight(1f),
                        onClick = { onCategory(if (selectedCategory == pair[0].id) null else pair[0].id) }
                    )
                    if (pair.size > 1) {
                        CategoryTile(
                            category = pair[1],
                            selected = selectedCategory == pair[1].id,
                            accent = accent,
                            modifier = Modifier.weight(1f),
                            onClick = { onCategory(if (selectedCategory == pair[1].id) null else pair[1].id) }
                        )
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeading("مصادر مختارة", filteredSources.size.toString() + " مصدر")
                }
            }
        } else {
            item { SectionHeading("محفوظاتك", savedCount.toString() + " مصدر محفوظ") }
        }
        if (filteredSources.isEmpty()) {
            item {
                EmptyState(
                    if (tab == AtlasTab.SAVED) "لا توجد محفوظات بعد" else "لا توجد نتائج مطابقة",
                    if (tab == AtlasTab.SAVED) "افتح أي مصدر واضغط حفظ لإضافته إلى مكتبتك." else "غيّر المجال أو جرّب كلمات بحث أخرى."
                )
            }
        } else {
            items(filteredSources, key = { it.id }) { source ->
                SourceCard(
                    source = source,
                    accent = accent,
                    onOpen = { onOpenSource(source) },
                    onFavorite = { onFavorite(source) }
                )
            }
        }
    }
}

@Composable
private fun AtlasTopHeader(accent: Color, isSaved: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(45.dp).clip(RoundedCornerShape(15.dp))
                .background(Brush.linearGradient(listOf(accent, AtlasBlue, AtlasViolet))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Language, contentDescription = null, tint = AtlasInk, modifier = Modifier.size(27.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("أطلس المعرفة", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Text("KNOWLEDGE ATLAS  /  " + if (isSaved) "LIBRARY" else "DISCOVERY", color = accent, fontSize = 9.sp, letterSpacing = 1.8.sp)
        }
        Surface(
            shape = CircleShape,
            color = Color(0xFF15233A),
            modifier = Modifier.border(1.dp, accent.copy(alpha = 0.25f), CircleShape)
        ) {
            Text("12 مجال", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun AtlasHero(count: Int, savedCount: Int, accent: Color, title: String, subtitle: String) {
    val transition = rememberInfiniteTransition(label = "atlas-ambient")
    val glow by transition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.48f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ambient-glow"
    )
    TiltPanel(
        modifier = Modifier.fillMaxWidth().height(218.dp),
        accent = accent,
        background = Brush.linearGradient(listOf(Color(0xFF193B50), Color(0xFF302C5A), Color(0xFF111D35)))
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.size(170.dp).align(Alignment.TopEnd).padding(0.dp)
                    .background(Brush.radialGradient(listOf(accent.copy(alpha = glow), Color.Transparent)), CircleShape)
            )
            Box(
                Modifier.size(92.dp).align(Alignment.BottomStart)
                    .background(Brush.radialGradient(listOf(AtlasViolet.copy(alpha = glow), Color.Transparent)), CircleShape)
            )
            Column(
                Modifier.fillMaxSize().padding(19.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = accent.copy(alpha = 0.14f), shape = CircleShape) {
                        Text("✦  KNOWLEDGE ENGINE", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("● LIVE", color = Color(0xFF83F1BE), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(title, color = Color.White, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold)
                    Text(subtitle, color = Color(0xFFD1D9EE), fontSize = 12.sp, lineHeight = 19.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    HeroMetric(count.toString(), "مصدر", accent)
                    HeroMetric("12", "مجال", AtlasBlue)
                    HeroMetric(savedCount.toString(), "محفوظ", AtlasGold)
                }
            }
        }
    }
}

@Composable
private fun HeroMetric(value: String, label: String, tint: Color) {
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = Color(0xFF0A1428).copy(alpha = 0.62f),
        modifier = Modifier.border(1.dp, tint.copy(alpha = 0.25f), RoundedCornerShape(13.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(value, color = tint, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Text(label, color = Color(0xFFC5D0E1), fontSize = 10.sp)
        }
    }
}

@Composable
private fun TiltPanel(
    modifier: Modifier = Modifier,
    accent: Color,
    background: Brush,
    content: @Composable () -> Unit
) {
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    val rotationX by animateFloatAsState(dragY, spring(dampingRatio = 0.52f, stiffness = 230f), label = "tilt-x")
    val rotationY by animateFloatAsState(dragX, spring(dampingRatio = 0.52f, stiffness = 230f), label = "tilt-y")
    Box(
        modifier = modifier
            .graphicsLayer {
                this.rotationX = rotationX
                this.rotationY = rotationY
                cameraDistance = 18f * density
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { dragX = 0f; dragY = 0f },
                    onDragCancel = { dragX = 0f; dragY = 0f }
                ) { change, amount ->
                    change.consume()
                    dragY = (dragY - amount.y * 0.22f).coerceIn(-8f, 8f)
                    dragX = (dragX + amount.x * 0.22f).coerceIn(-8f, 8f)
                }
            }
            .clip(RoundedCornerShape(27.dp))
            .background(background)
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(27.dp))
    ) {
        content()
    }
}

@Composable
private fun SectionHeading(title: String, trailing: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(trailing, color = AtlasMuted, fontSize = 10.sp)
    }
}

@Composable
private fun CategoryTile(
    category: AtlasCategory,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(if (selected) 1.025f else 1f, label = "category-scale")
    val borderColor by animateColorAsState(if (selected) category.color else category.color.copy(alpha = 0.22f), label = "category-border")
    Card(
        modifier = modifier.height(105.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111D32)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 9.dp else 2.dp)
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    listOf(category.color.copy(alpha = if (selected) 0.22f else 0.12f), Color(0xFF111D32), Color(0xFF17243A))
                )
            ).border(1.dp, borderColor, RoundedCornerShape(19.dp)).padding(12.dp)
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                            .background(category.color.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(category.symbol, color = category.color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (selected) "●" else "↗", color = category.color, fontSize = 12.sp)
                }
                Text(category.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun GlowActionButton(label: String, accent: Color, enabled: Boolean = true, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, label = "button-press")
    Surface(
        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent
    ) {
        Box(
            Modifier.background(
                Brush.horizontalGradient(
                    listOf(accent.copy(alpha = if (enabled) 0.95f else 0.26f), AtlasBlue.copy(alpha = if (enabled) 0.9f else 0.2f), AtlasViolet.copy(alpha = if (enabled) 0.86f else 0.18f))
                )
            ).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = if (enabled) AtlasInk else AtlasMuted, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SourceCard(
    source: KnowledgeSource,
    accent: Color,
    onOpen: () -> Unit,
    onFavorite: () -> Unit
) {
    val category = AtlasCategories.firstOrNull { it.id == source.categoryId }
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable(onClick = onOpen),
        shape = RoundedCornerShape(21.dp),
        colors = CardDefaults.cardColors(containerColor = AtlasPanel),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(4.dp).height(145.dp).background(category?.color ?: accent))
            Column(
                Modifier.weight(1f).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(39.dp).clip(RoundedCornerShape(12.dp))
                            .background((category?.color ?: accent).copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(category?.symbol ?: "✦", color = category?.color ?: accent, fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(source.nameAr.ifBlank { source.nameEn }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(source.nameEn, color = AtlasMuted, fontSize = 10.sp)
                    }
                    IconButton(onClick = onFavorite, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Outlined.Bookmark, contentDescription = "حفظ المصدر", tint = if (source.isFavorite) AtlasGold else AtlasMuted)
                    }
                }
                Text(source.shortDescriptionAr, color = Color(0xFFD0D9E6), fontSize = 12.sp, lineHeight = 19.sp, maxLines = 3)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = (category?.color ?: accent).copy(alpha = 0.14f)) {
                        Text(category?.title ?: "مجال آخر", color = category?.color ?: accent, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("التفاصيل داخل أطلس  ›", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SourceDetailScreen(
    source: KnowledgeSource,
    accent: Color,
    onBack: () -> Unit,
    onFavorite: () -> Unit,
    onSaveNotes: (String) -> Unit,
    onOpenWebsite: (String, String) -> Unit
) {
    var notes by remember(source.id) { mutableStateOf(source.userNotes.orEmpty()) }
    var notesSaved by remember(source.id) { mutableStateOf(false) }
    val category = AtlasCategories.firstOrNull { it.id == source.categoryId }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 17.dp, end = 17.dp, top = 12.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ رجوع", color = accent, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.weight(1f))
                Text("صفحة المصدر", color = AtlasMuted, fontSize = 11.sp)
            }
        }
        item {
            TiltPanel(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                accent = category?.color ?: accent,
                background = Brush.linearGradient(listOf(Color(0xFF1B3D4E), Color(0xFF302A59), Color(0xFF111C31)))
            ) {
                Column(
                    Modifier.fillMaxSize().padding(19.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(category?.symbol ?: "✦", color = category?.color ?: accent, fontSize = 28.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(source.nameAr.ifBlank { source.nameEn }, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp)
                        Text(source.nameEn, color = AtlasMuted, fontSize = 12.sp)
                        Text(category?.title ?: "مجال آخر", color = category?.color ?: accent, fontSize = 11.sp)
                    }
                }
            }
        }
        item {
            Text(source.shortDescriptionAr, color = Color(0xFFE0E7F3), fontSize = 14.sp, lineHeight = 23.sp)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                GlowActionButton("فتح الموقع داخل التطبيق", accent, onClick = { onOpenWebsite(source.officialUrl, source.nameAr.ifBlank { source.nameEn }) })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Surface(
                    modifier = Modifier.weight(1f).clickable(onClick = onFavorite),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF17243B)
                ) {
                    Text(if (source.isFavorite) "★  محفوظ" else "☆  أضف للمحفوظات", color = AtlasGold, fontWeight = FontWeight.Bold, modifier = Modifier.padding(13.dp))
                }
                Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF17243B)) {
                    Text(accessTypeLabel(source.accessType), color = AtlasMuted, modifier = Modifier.padding(13.dp), fontSize = 11.sp)
                }
            }
        }
        item {
            DetailSection(title = "ماذا يقدم هذا المصدر؟", body = source.concreteOfferingAr, accent = accent)
        }
        item {
            DetailSection(title = "كيف تبدأ؟", body = source.exactStepsToStartAr, accent = accent)
        }
        item {
            DetailSection(
                title = "معلومات التحقق",
                body = "الحالة: " + confidenceLabel(source.confidenceLevel) +
                    "\nآخر تحقق: " + if (source.lastVerifiedDate.isBlank()) "لم يسجل تحقق فردي" else source.lastVerifiedDate +
                    "\nمرجع التحقق: " + source.verificationSource +
                    "\nاللغة العربية: " + if (source.hasArabicInterface) "متاحة" else "غير مؤكدة",
                accent = AtlasGold
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("ملاحظاتك الخاصة", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it; notesSaved = false },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("اكتب ما تعلمته أو ما تريد مراجعته لاحقاً...") }
                )
                GlowActionButton(if (notesSaved) "تم حفظ الملاحظات" else "حفظ ملاحظاتي", accent, onClick = {
                    onSaveNotes(notes)
                    notesSaved = true
                })
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, body: String, accent: Color) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF121E33)).border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(body.ifBlank { "لا توجد بيانات مدخلة لهذا الحقل بعد." }, color = Color(0xFFD2DAE8), fontSize = 12.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun InAppBrowserScreen(url: String, title: String, accent: Color, onClose: () -> Unit) {
    val context = LocalContext.current
    var progress by remember { mutableStateOf(8) }
    var failed by remember { mutableStateOf(false) }
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return request?.url?.scheme != "https"
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    progress = 8
                    failed = false
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    progress = 100
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) failed = true
                }
            }
            setBackgroundColor(android.graphics.Color.rgb(8, 13, 25))
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    androidx.activity.compose.BackHandler {
        if (webView.canGoBack()) webView.goBack() else onClose()
    }
    Column(Modifier.fillMaxSize().background(AtlasInk)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text("‹ رجوع", color = accent, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(url, color = AtlasMuted, fontSize = 9.sp, maxLines = 1)
            }
            TextButton(onClick = { webView.reload() }) { Text("↻", color = accent, fontSize = 20.sp) }
        }
        if (progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = accent)
        }
        if (failed) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("تعذر تحميل الصفحة داخل أطلس.", color = Color.White, fontWeight = FontWeight.Bold)
                Text("قد يمنع الموقع العرض داخل التطبيقات أو يحتاج إلى اتصال مستقر.", color = AtlasMuted, fontSize = 12.sp)
                TextButton(onClick = { failed = false; webView.reload() }) { Text("إعادة المحاولة", color = accent) }
            }
        }
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                if (view.url != url) view.loadUrl(url)
            }
        )
    }
}

@Composable
private fun WebHitCard(hit: WebSearchHit, accent: Color, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = AtlasPanel)
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(hit.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (hit.snippet.isNotBlank()) Text(hit.snippet, color = Color(0xFFD0D9E6), fontSize = 12.sp, lineHeight = 19.sp, maxLines = 4)
            Text(hit.publisher.ifBlank { "الناشر غير محدد" } + " • " + hit.publisherType, color = accent, fontSize = 10.sp)
            Text("افتح النتيجة داخل التطبيق  ›", color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SettingsScreen(
    count: Int,
    accent: Color,
    onAccentChange: (Color) -> Unit,
    onCheckUpdate: () -> Unit
) {
    val palettes = listOf(AtlasTeal, AtlasBlue, AtlasViolet, Color(0xFFFF9B73), Color(0xFF8ED36D))
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { AtlasTopHeader(accent, false) }
        item { AtlasHero(count, 0, accent, "مركز التحكم", "تحكم في المظهر والتحديثات وتجربة أطلس.") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("ألوان الواجهة", "اختر لون الإضاءة الأساسي")
                Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    palettes.forEach { color ->
                        val selected = color == accent
                        Box(
                            Modifier.size(42.dp).clip(CircleShape)
                                .background(Brush.radialGradient(listOf(color, color.copy(alpha = 0.45f))))
                                .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else color.copy(alpha = 0.4f), CircleShape)
                                .clickable { onAccentChange(color) }
                        )
                    }
                }
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(AtlasPanel).padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
                Text("تحديثات أطلس", color = Color.White, fontWeight = FontWeight.Bold)
                Text("يفحص وجود إصدار جديد، ويظل تثبيت أي تحديث بموافقة Android.", color = AtlasMuted, fontSize = 12.sp, lineHeight = 19.sp)
                GlowActionButton("فحص التحديثات", accent, onClick = onCheckUpdate)
            }
        }
        item {
            DetailSection(
                title = "حول التجربة",
                body = "المكتبة المحلية تعمل دون اتصال. البحث العالمي وعرض المواقع داخل التطبيق يحتاجان إلى الإنترنت. تأثير الميل ثلاثي المنظور يعتمد على تحريك البطاقة باللمس، وهو تأثير واجهة وليس مجسماً ثلاثي الأبعاد مستقلاً.",
                accent = AtlasBlue
            )
        }
    }
}

@Composable
private fun AtlasBottomBar(activeTab: AtlasTab, onSelect: (AtlasTab) -> Unit) {
    NavigationBar(
        containerColor = Color(0xFF101A2D),
        contentColor = Color.White,
        windowInsets = WindowInsets.navigationBars
    ) {
        NavigationBarItem(
            selected = activeTab == AtlasTab.DISCOVER,
            onClick = { onSelect(AtlasTab.DISCOVER) },
            icon = { Icon(Icons.Outlined.Explore, contentDescription = null) },
            label = { Text("اكتشف", fontSize = 10.sp) }
        )
        NavigationBarItem(
            selected = activeTab == AtlasTab.SAVED,
            onClick = { onSelect(AtlasTab.SAVED) },
            icon = { Icon(Icons.Outlined.Bookmark, contentDescription = null) },
            label = { Text("محفوظاتي", fontSize = 10.sp) }
        )
        NavigationBarItem(
            selected = activeTab == AtlasTab.SETTINGS,
            onClick = { onSelect(AtlasTab.SETTINGS) },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            label = { Text("النظام", fontSize = 10.sp) }
        )
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = AtlasPanel
    ) {
        Column(
            Modifier.padding(23.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = AtlasTeal, modifier = Modifier.size(30.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(subtitle, color = AtlasMuted, fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}

private fun accessTypeLabel(value: String): String = when (value.uppercase()) {
    "FREE" -> "مجاني"
    "PAID" -> "مدفوع"
    "MIXED" -> "مجاني ومدفوع"
    "REGISTRATION" -> "يتطلب تسجيلاً"
    else -> "غير محدد"
}

private fun confidenceLabel(value: String): String = when (value.uppercase()) {
    "REVIEW_REQUIRED" -> "قيد المراجعة"
    "OFFICIAL" -> "جهة رسمية / موقع الجهة"
    "ACADEMIC" -> "أكاديمي"
    "GOVERNMENT" -> "حكومي"
    "COMMUNITY" -> "مجتمعي"
    else -> "غير مصنف"
}


@Composable
private fun AtlasOceanCityPortal(
    accent: Color,
    sourceCount: Int,
    onBuildingSelected: (Int) -> Unit
) {
    val context = LocalContext.current
    var cityView by remember { mutableStateOf<AtlasOceanCityView?>(null) }
    var selectedDistrict by remember { mutableStateOf<Int?>(null) }
    val owner = context as? LifecycleOwner

    DisposableEffect(cityView, owner) {
        val view = cityView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose {
            owner?.lifecycle?.removeObserver(observer)
            view?.onPause()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("مدينة أطلس الغارقة", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text("عالم ثلاثي الأبعاد حيّ • المس المباني لاستكشافها", color = AtlasTeal, fontSize = 10.sp)
            }
            Surface(shape = CircleShape, color = Color(0xFF123A45)) {
                Text("LIVE  3D", color = AtlasTeal, fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
            }
        }
        Box(
            Modifier.fillMaxWidth().height(340.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                .background(Color(0xFF031725))
        ) {
            AndroidView(
                factory = { ctx ->
                    AtlasOceanCityView(ctx) { id ->
                        selectedDistrict = id
                        onBuildingSelected(id)
                    }.also { cityView = it }
                },
                modifier = Modifier.fillMaxSize(),
                update = { if (cityView !== it) cityView = it }
            )
            Column(
                Modifier.align(Alignment.TopStart).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text("ABYSSAL METROPOLIS", color = Color(0xFF9AF7EA), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                Text("عمق المدينة  •  3,800 m", color = Color(0xFFB5D7E4), fontSize = 9.sp)
            }
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(10.dp),
                shape = RoundedCornerShape(15.dp),
                color = Color(0xD90A2030),
                border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.32f))
            ) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        selectedDistrict?.let { id ->
                            if (id == 0) "مركز القيادة — قلب أطلس" else AtlasCategories.firstOrNull { it.id == id }?.title ?: "منطقة أطلس"
                        } ?: "اسحب لتدور • قرّب بإصبعين • المس مبنى",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Text("$sourceCount مصدر معرفي متصل بالمدينة", color = accent, fontSize = 8.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("↻ تدوير" to "المدينة", "＋ تقريب" to "بإصبعين", "⌖" to "اختر مبنى").forEach { pair ->
                Surface(
                    Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF14283A)
                ) {
                    Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(pair.first, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(pair.second, color = AtlasMuted, fontSize = 8.sp)
                    }
                }
            }
        }
    }
}
