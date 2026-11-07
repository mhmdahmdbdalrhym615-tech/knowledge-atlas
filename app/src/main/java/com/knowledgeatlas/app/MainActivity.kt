package com.knowledgeatlas.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.knowledgeatlas.app.data.model.KnowledgeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = (application as AtlasApplication).database.knowledgeDao()
        val query = MutableStateFlow("")
        val visibleSources = combine(dao.getAllSources(), query) { all, text ->
            if (text.isBlank()) all else all.filter { source ->
                listOf(
                    source.nameAr,
                    source.nameEn,
                    source.shortDescriptionAr,
                    source.shortDescriptionEn,
                    source.tags,
                    source.concreteOfferingAr
                ).any { it.contains(text.trim(), ignoreCase = true) }
            }
        }

        setContent {
            MaterialTheme {
                val sources by visibleSources.collectAsState(initial = emptyList())
                var searchText by remember { mutableStateOf("") }
                AtlasHome(
                    sources = sources,
                    searchText = searchText,
                    onSearchChanged = {
                        searchText = it
                        query.value = it
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AtlasHome(
    sources: List<KnowledgeSource>,
    searchText: String,
    onSearchChanged: (String) -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("أطلس المعرفة", fontWeight = FontWeight.Bold)
                        Text("بوابتك إلى مصادر المعرفة", style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        }
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "ابحث عن المعرفة، لا عن الكلمات فقط",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "اكتب مشكلة أو هدفاً تريد الوصول إليه. البحث الحالي يعمل داخل الكتالوج المحلي، وسيُربط لاحقاً بمحرك بحث دلالي عالمي."
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = onSearchChanged,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("ابحث في أطلس المعرفة") },
                    placeholder = { Text("مثال: أريد تعلم تحليل التربة") },
                    singleLine = true
                )
            }

            item {
                Text(
                    if (searchText.isBlank()) "المصادر المعرفية" else "نتائج البحث المحلي",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (sources.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            "لا توجد نتائج محلية مطابقة. ستظهر المصادر بعد إضافة الكتالوج الموثق أو مزامنته.",
                            modifier = Modifier.padding(18.dp)
                        )
                    }
                }
            } else {
                items(sources, key = { it.id }) { source ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(source.officialUrl))
                                )
                            }
                        }
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(
                                source.nameAr.ifBlank { source.nameEn },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(source.shortDescriptionAr, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                source.verificationSource,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
