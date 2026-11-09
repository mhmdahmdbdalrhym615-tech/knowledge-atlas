package com.knowledgeatlas.app.world

import android.content.Context
import android.speech.tts.TextToSpeech
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.util.Locale

private val HudGlass = Color(0xB30B1220)
private val HudLine = Color(0x33FFFFFF)
private val HudText = Color(0xFFF2F6FF)
private val HudMuted = Color(0xFFA9B5CC)

/** Arabic voice guide with a subtitle fallback when no Arabic TTS voice is installed. */
private class WorldVoice(context: Context, private val onSubtitle: (String) -> Unit) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val egypt = Locale.forLanguageTag("ar-EG")
            val result = tts.setLanguage(egypt).let {
                if (it == TextToSpeech.LANG_MISSING_DATA || it == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.forLanguageTag("ar"))
                } else it
            }
            ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            tts.setSpeechRate(0.98f)
        }
    }

    fun speak(text: String, flush: Boolean) {
        onSubtitle(text)
        if (ready) {
            tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "atlas-${text.hashCode()}")
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}

/**
 * Full-screen virtual world. One city per knowledge domain; every door opens a catalog source.
 * [paused] stops rendering while a source page is shown on top.
 */
@Composable
fun AtlasWorldScreen(
    sources: List<WorldSourceRef>,
    startCategoryId: Int,
    paused: Boolean,
    onOpenSource: (String) -> Unit,
    onExitToCatalog: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cities = remember(sources.map { it.id }.sorted()) { WorldLayout.buildCities(sources) }
    val startIndex = cities.indexOfFirst { it.domain.categoryId == startCategoryId }.coerceAtLeast(0)

    var hud by remember { mutableStateOf(WorldHud()) }
    var subtitle by remember { mutableStateOf<String?>(null) }
    var subtitleStamp by remember { mutableStateOf(0L) }
    var engine by remember { mutableStateOf<WorldEngine?>(null) }
    var showDestinations by remember { mutableStateOf(false) }
    var showDirectory by remember { mutableStateOf(false) }
    val openSource by rememberUpdatedState(onOpenSource)

    val voice = remember {
        WorldVoice(context) { text -> subtitle = text; subtitleStamp = System.nanoTime() }
    }
    DisposableEffect(Unit) { onDispose { voice.shutdown() } }

    LaunchedEffect(subtitleStamp) {
        if (subtitle != null) {
            delay(4800)
            subtitle = null
        }
    }

    val overlayOpen = showDestinations || showDirectory
    LaunchedEffect(engine, paused) { engine?.setPaused(paused) }

    DisposableEffect(lifecycleOwner, engine) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> engine?.setPaused(true)
                Lifecycle.Event.ON_RESUME -> engine?.setPaused(paused)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).also { surfaceView ->
                    engine = WorldEngine(
                        context = ctx,
                        surfaceView = surfaceView,
                        cities = cities,
                        startCityIndex = startIndex,
                        onHud = { hud = it },
                        onSpeak = { text, flush -> voice.speak(text, flush) },
                        onOpenSource = { id -> openSource(id) }
                    )
                }
            },
            onRelease = {
                engine?.destroy()
                engine = null
            }
        )

        val e = engine
        if (e != null && hud.mode != WorldMode.LOADING && hud.error == null) {
            WorldHudLayer(
                hud = hud,
                engine = e,
                cities = cities,
                onDestinations = { showDestinations = true },
                onDirectory = { showDirectory = true },
                onExitToCatalog = onExitToCatalog
            )
        }

        // Fade-to-white flash used for boarding, warp entry and warp exit.
        if (hud.flash > 0.001f) {
            Box(Modifier.fillMaxSize().alpha(hud.flash.coerceIn(0f, 1f)).background(Color.White))
        }

        // Title card such as "مدينة الذكاء الاصطناعي".
        val title = hud.titleCard
        if (title != null && hud.titleAlpha > 0.01f) {
            Column(
                modifier = Modifier.align(Alignment.Center).alpha(hud.titleAlpha.coerceIn(0f, 1f)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("مرحباً بك في", color = HudMuted, fontSize = 16.sp)
                Text(title, color = HudText, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(120.dp).height(2.dp).background(Color(cities.getOrNull(hud.cityIndex)?.domain?.colorArgb?.toInt() ?: 0xFFFFFFFF.toInt())))
            }
        }

        // Subtitles for every spoken line.
        AnimatedVisibility(
            visible = subtitle != null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 210.dp).padding(horizontal = 24.dp)
        ) {
            Text(
                subtitle.orEmpty(),
                color = HudText,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.background(Color(0xC0000000), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        if (hud.mode == WorldMode.LOADING && hud.error == null) {
            LoadingLayer(hud)
        }

        hud.error?.let { message ->
            Column(
                modifier = Modifier.align(Alignment.Center).padding(28.dp)
                    .background(HudGlass, RoundedCornerShape(16.dp)).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("تعذّر تشغيل العالم الافتراضي", color = HudText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(message, color = HudMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                HudButton("الرجوع للكتالوج", primary = true) { onExitToCatalog() }
            }
        }

        if (showDestinations) {
            PickerSheet(title = "اختار الوجهة", onClose = { showDestinations = false }) {
                val here = cities.getOrNull(hud.cityIndex)
                LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
                    itemsIndexed(cities) { i, c ->
                        val dist = if (here != null) WorldLayout.distanceAu(here, c) else 0.0
                        PickerRow(
                            title = c.nameAr,
                            subtitle = if (i == hud.cityIndex) "إنت هنا • ${c.doors.size} موقع"
                            else "${WorldText.formatNumber(dist, 1)} وحدة فلكية • ${c.doors.size} موقع",
                            color = Color(c.domain.colorArgb.toInt()),
                            selected = hud.destinationIndex == i
                        ) {
                            engine?.selectDestination(i)
                            showDestinations = false
                        }
                    }
                }
            }
        }

        if (showDirectory) {
            val city = cities.getOrNull(hud.cityIndex)
            PickerSheet(title = "دليل ${city?.nameAr.orEmpty()}", onClose = { showDirectory = false }) {
                LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
                    item {
                        PickerRow("الميناء الفضائي", "مكان المركبة", Color(0xFF9AC7FF), false) {
                            engine?.quickTravelToPort(); showDirectory = false
                        }
                    }
                    itemsIndexed(city?.doors.orEmpty()) { i, door ->
                        PickerRow(
                            title = door.source.nameAr,
                            subtitle = "${door.source.nameEn} • ${WorldText.formatDistance((door.z - WorldLayout.PAD_Z).toDouble())} من الميناء",
                            color = Color(city?.domain?.colorArgb?.toInt() ?: 0xFFFFFFFF.toInt()),
                            selected = false
                        ) {
                            engine?.quickTravel(i); showDirectory = false
                        }
                    }
                }
            }
        }

        if (overlayOpen) {
            // Release held arrows when a sheet covers the controls.
            LaunchedEffect(Unit) { engine?.let { it.up = false; it.down = false; it.left = false; it.right = false } }
        }
    }
}

@Composable
private fun LoadingLayer(hud: WorldHud) {
    Box(Modifier.fillMaxSize().background(Color(0xFF05070D)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("أطلس المعرفة", color = HudText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("العالم الافتراضي", color = HudMuted, fontSize = 15.sp)
            Spacer(Modifier.height(26.dp))
            LinearProgressIndicator(
                progress = { hud.loadingProgress.coerceIn(0f, 1f) },
                modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().height(4.dp),
                color = Color(0xFFB18CFF),
                trackColor = Color(0x22FFFFFF)
            )
            Spacer(Modifier.height(10.dp))
            Text(hud.loadingLabel, color = HudMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun WorldHudLayer(
    hud: WorldHud,
    engine: WorldEngine,
    cities: List<CityDef>,
    onDestinations: () -> Unit,
    onDirectory: () -> Unit,
    onExitToCatalog: () -> Unit
) {
    Box(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Top bar: location, destination and the way back to the classic catalog.
        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f).background(HudGlass, RoundedCornerShape(14.dp))
                    .border(1.dp, HudLine, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(hud.cityName, color = HudText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                val dest = hud.destinationIndex?.let { cities.getOrNull(it) }
                Text(
                    if (dest != null && hud.destinationIndex != hud.cityIndex) "الوجهة: ${dest.nameAr}" else modeLabel(hud.mode),
                    color = HudMuted, fontSize = 12.sp, maxLines = 1
                )
            }
            HudButton("الوجهات") { onDestinations() }
            if (hud.mode == WorldMode.WALK) HudButton("الدليل") { onDirectory() }
            HudButton("الكتالوج") { onExitToCatalog() }
        }

        // Flight instruments.
        val flying = hud.mode !in setOf(WorldMode.WALK, WorldMode.ENTERING, WorldMode.PARKED)
        if (flying || hud.speedText.isNotEmpty() && hud.mode != WorldMode.WALK) {
            Column(
                modifier = Modifier.align(Alignment.TopStart).padding(top = 76.dp, start = 12.dp)
                    .background(HudGlass, RoundedCornerShape(14.dp))
                    .border(1.dp, HudLine, RoundedCornerShape(14.dp)).padding(12.dp)
                    .widthIn(min = 150.dp)
            ) {
                Instrument("السرعة", hud.speedText)
                if (hud.warpFactorText.isNotEmpty()) Instrument("محرك القفزة", hud.warpFactorText)
                Instrument("المسافة", hud.distanceText)
                if (hud.altitudeText.isNotEmpty()) Instrument("الارتفاع", hud.altitudeText)
                if (hud.autopilot) Text("طيار آلي", color = Color(0xFF7CF2C8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Arrow pad (bottom-left in screen space; the app is RTL so we pin it physically).
        ArrowPad(engine = engine, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))

        // Contextual actions (bottom-right).
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).widthIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            when (hud.mode) {
                WorldMode.WALK -> {
                    hud.nearDoorName?.let { name ->
                        Text("أهلاً بك في موقع $name", color = HudText, fontSize = 13.sp, textAlign = TextAlign.End)
                        HudButton("ادخل الموقع", primary = true) { engine.enterDoor() }
                    }
                    if (hud.nearShip) HudButton("اركب المركبة", primary = true) { engine.board() }
                    HudButton(if (hud.running) "مشي" else "جري") { engine.toggleRun() }
                }
                WorldMode.PARKED -> {
                    val hasDest = hud.destinationIndex != null && hud.destinationIndex != hud.cityIndex
                    if (hasDest) HudButton("إقلاع وقفزة ضوئية", primary = true) { engine.takeoff(true) }
                    else HudButton("اختار وجهة", primary = true) { onDestinations() }
                    HudButton("إقلاع") { engine.takeoff(false) }
                    HudButton("انزل من المركبة") { engine.exitShip() }
                }
                WorldMode.FLIGHT -> {
                    if (hud.canWarp) HudButton("قفزة ضوئية", primary = true) { engine.engageWarp() }
                    if (hud.canLand) HudButton("هبوط تلقائي", primary = true) { engine.requestLanding() }
                    ThrottleSlider(hud.throttle) { engine.throttle = it }
                }
                WorldMode.APPROACH -> {
                    if (hud.canWarp) HudButton("قفزة ضوئية") { engine.engageWarp() }
                    HudButton("تحكم يدوي") { engine.manualControl() }
                }
                else -> Unit
            }
        }
    }
}

private fun modeLabel(mode: WorldMode): String = when (mode) {
    WorldMode.WALK -> "استكشاف سيراً على الأقدام"
    WorldMode.ENTERING -> "جاري الدخول"
    WorldMode.PARKED -> "داخل المركبة"
    WorldMode.TAKEOFF -> "إقلاع"
    WorldMode.FLIGHT -> "طيران"
    WorldMode.WARP_CHARGE -> "تجهيز القفزة الضوئية"
    WorldMode.WARP -> "قفزة أسرع من الضوء"
    WorldMode.APPROACH -> "اقتراب من المهبط"
    WorldMode.LANDING -> "هبوط"
    WorldMode.LOADING -> "تحميل"
}

@Composable
private fun Instrument(label: String, value: String) {
    if (value.isEmpty()) return
    Text(label, color = HudMuted, fontSize = 11.sp)
    Text(value, color = HudText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ArrowPad(engine: WorldEngine, modifier: Modifier) {
    // Arrows are physical directions, so keep them left-to-right even in the Arabic (RTL) layout.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HoldKey("▲") { engine.up = it }
        Row {
            HoldKey("◀") { engine.left = it }
            Spacer(Modifier.size(58.dp))
            HoldKey("▶") { engine.right = it }
        }
        HoldKey("▼") { engine.down = it }
    }
    }
}

@Composable
private fun HoldKey(label: String, onHold: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val hold by rememberUpdatedState(onHold)
    DisposableEffect(Unit) { onDispose { hold(false) } }
    Box(
        modifier = Modifier.size(58.dp).padding(3.dp)
            .background(if (pressed) Color(0xCC3A4C78) else HudGlass, CircleShape)
            .border(1.dp, HudLine, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true; hold(true)
                    try { tryAwaitRelease() } finally { pressed = false; hold(false) }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = HudText, fontSize = 20.sp)
    }
}

@Composable
private fun ThrottleSlider(value: Float, onChange: (Float) -> Unit) {
    var v by remember { mutableStateOf(value) }
    Column(
        Modifier.width(200.dp).background(HudGlass, RoundedCornerShape(14.dp))
            .border(1.dp, HudLine, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text("قوة الدفع ${(v * 100).toInt()}٪", color = HudMuted, fontSize = 12.sp)
        Slider(
            value = v,
            onValueChange = { v = it; onChange(it) },
            colors = SliderDefaults.colors(thumbColor = Color(0xFFFFC477), activeTrackColor = Color(0xFFFFC477))
        )
    }
}

@Composable
private fun HudButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (primary) Color(0xFF0B1220) else HudText,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(if (primary) Color(0xFFFFC477) else HudGlass, RoundedCornerShape(12.dp))
            .border(1.dp, if (primary) Color.Transparent else HudLine, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
private fun PickerSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier.fillMaxWidth().height(460.dp)
                .background(Color(0xF20B1220), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .clickable(enabled = false) {}
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = HudText, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                HudButton("إغلاق") { onClose() }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(if (selected) Color(0x33FFFFFF) else Color(0x14FFFFFF), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = HudText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(subtitle, color = HudMuted, fontSize = 12.sp, maxLines = 1)
        }
    }
}
