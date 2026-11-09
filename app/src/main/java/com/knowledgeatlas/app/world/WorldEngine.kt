package com.knowledgeatlas.app.world

import android.content.Context
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class WorldMode { LOADING, WALK, ENTERING, PARKED, TAKEOFF, FLIGHT, WARP_CHARGE, WARP, APPROACH, LANDING }

/** Snapshot of everything the Compose HUD needs. Published ~12 times per second. */
data class WorldHud(
    val mode: WorldMode = WorldMode.LOADING,
    val loadingProgress: Float = 0f,
    val loadingLabel: String = "تجهيز العالم",
    val cityIndex: Int = 0,
    val cityName: String = "",
    val destinationIndex: Int? = null,
    val speedText: String = "",
    val distanceText: String = "",
    val altitudeText: String = "",
    val nearShip: Boolean = false,
    val nearDoorName: String? = null,
    val nearDoorSourceId: String? = null,
    val running: Boolean = false,
    val canLand: Boolean = false,
    val canWarp: Boolean = false,
    val autopilot: Boolean = false,
    val throttle: Float = 0.25f,
    val flash: Float = 0f,
    val titleCard: String? = null,
    val titleAlpha: Float = 0f,
    val warpFactorText: String = "",
    val error: String? = null
)

/**
 * The virtual world: a Filament renderer driving a third-person character, a flyable shuttle with
 * a faster-than-light warp sequence, and one generated city per knowledge domain.
 * All Filament calls happen on the main thread (Choreographer); heavy decoding runs on IO.
 */
class WorldEngine(
    private val context: Context,
    private val surfaceView: SurfaceView,
    private val cities: List<CityDef>,
    startCityIndex: Int,
    private val onHud: (WorldHud) -> Unit,
    private val onSpeak: (String, Boolean) -> Unit,
    private val onOpenSource: (String) -> Unit
) : Choreographer.FrameCallback {

    companion object {
        init { Utils.init() }
        private const val GROUND_Y = 0.15f
        private val SUN_DIR = V3(0.391f, -0.714f, -0.581f).normalized()
        private const val WALK_SPEED = 1.45f
        private const val RUN_SPEED = 4.6f
        private const val ANIM_IDLE = 0
        private const val ANIM_WALK = 2
        private const val ANIM_RUN = 3
        private const val ANIM_CHEER = 4
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val choreographer = Choreographer.getInstance()
    private val engine: Engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(context)
    private var swapChain: SwapChain? = null
    private var width = 1
    private var height = 1

    // Assets
    private var materials = mutableListOf<Material>()
    private val surfaces = mutableMapOf<String, SurfaceSet>()
    private val sharedInstances = mutableListOf<MaterialInstance>()
    private var palette: WorldPalette? = null
    private var builder: CityBuilder? = null
    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null
    private var materialProvider: UbershaderProvider? = null
    private var guide: FilamentAsset? = null
    private var shuttle: FilamentAsset? = null
    private var pendingAssets = mutableListOf<FilamentAsset>()
    private var daySky: Skybox? = null
    private var dayIbl: IndirectLight? = null
    private var spaceSky: Skybox? = null
    private var spaceIbl: IndirectLight? = null
    private val envTextures = mutableListOf<Texture>()
    private var terrain: Mesh? = null
    private var sun = 0
    private var effects: ParticleSystem? = null
    private var sky: ParticleSystem? = null
    private var city: CityScene? = null
    private var nextCity: CityScene? = null

    // Simulation state
    private var mode = WorldMode.LOADING
    private var cityIndex = startCityIndex.coerceIn(0, cities.lastIndex)
    private var destination: Int? = null
    private var paused = false
    private var lastFrame = 0L
    private var hudTimer = 0f
    private var time = 0f
    private var loadingProgress = 0f
    private var loadingLabel = "تجهيز العالم"
    private var error: String? = null

    // Input
    var up = false; var down = false; var left = false; var right = false
    var throttle = 0.25f
    private var running = false
    private var camYawOffset = 0f
    private var camPitch = 0.2f
    private var camDistance = 4.6f
    private var lastTouchX = 0f; private var lastTouchY = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            camDistance = (camDistance / detector.scaleFactor).coerceIn(2.4f, 12f)
            return true
        }
    })

    // Character
    private var charPos = V3(14f, GROUND_Y, -168f)
    private var charYaw = 0f
    private var charSpeed = 0f
    private var animCurrent = ANIM_IDLE
    private var animPrevious = ANIM_IDLE
    private var animTime = 0f
    private var animPrevTime = 0f
    private var animBlend = 1f
    private var characterInScene = false
    private var enterTimer = 0f
    private var enteringDoor = -1
    private var nearDoor = -1
    private var announcedDoor = -1

    // Ship
    private var shipPos = V3(WorldLayout.PAD_X, GROUND_Y, WorldLayout.PAD_Z)
    private var shipYaw = 0f
    private var shipPitch = 0f
    private var shipRoll = 0f
    private var shipSpeed = 0f
    private var shipVertical = 0f
    private var yawRate = 0f
    private var autopilot = false
    private var autoWarpAfterTakeoff = false
    private var phaseTimer = 0f
    private var warpTotal = 10f
    private var warpDistance = 0.0
    private var warpPeak = 0.0
    private val callouts = ApproachCallouts()
    private var landingPhase = 0
    private var shake = 0f

    // Camera smoothing
    private var camPos = V3(0f, 5f, -180f)
    private var camTarget = V3(0f, 1.4f, 0f)
    private var camFov = 52f
    private var flash = 0f
    private var flashTarget = 0f
    private var titleCard: String? = null
    private var titleTimer = 0f
    private var pendingAction: (() -> Unit)? = null

    init {
        configureView()
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
                displayHelper.attach(renderer, surfaceView.display)
            }

            override fun onDetachedFromSurface() {
                displayHelper.detach()
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                    swapChain = null
                }
            }

            override fun onResized(w: Int, h: Int) {
                width = max(1, w); height = max(1, h)
                view.viewport = Viewport(0, 0, width, height)
            }
        }
        uiHelper.attachTo(surfaceView)
        surfaceView.setOnTouchListener { _, e -> onTouch(e); true }
        choreographer.postFrameCallback(this)
        scope.launch { load() }
    }

    // ---------------------------------------------------------------- setup

    private fun configureView() {
        view.scene = scene
        view.camera = camera
        camera.setExposure(16f, 1f / 125f, 100f)
        view.antiAliasing = View.AntiAliasing.NONE
        view.temporalAntiAliasingOptions = view.temporalAntiAliasingOptions.apply { enabled = true; feedback = 0.08f }
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply {
            enabled = true; radius = 0.6f; intensity = 1.1f; quality = View.QualityLevel.MEDIUM
        }
        view.bloomOptions = view.bloomOptions.apply {
            enabled = true; strength = 0.14f; levels = 6; resolution = 360; threshold = true; highlight = 1000f
        }
        view.vignetteOptions = view.vignetteOptions.apply { enabled = true; midPoint = 0.62f; feather = 0.6f; roundness = 0.6f }
        view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
            enabled = true; minScale = 0.62f; maxScale = 1f; quality = View.QualityLevel.MEDIUM
        }
        view.colorGrading = ColorGrading.Builder()
            .toneMapper(ToneMapper.ACES())
            .contrast(1.04f)
            .vibrance(1.12f)
            .build(engine)
        setFog(true)
        renderer.clearOptions = renderer.clearOptions.apply { clear = true; clearColor = floatArrayOf(0.02f, 0.03f, 0.06f, 1f) }

        sun = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1f, 0.96f, 0.9f)
            .intensity(105_000f)
            .direction(SUN_DIR.x, SUN_DIR.y, SUN_DIR.z)
            .sunAngularRadius(1.4f)
            .sunHaloSize(8f)
            .castShadows(true)
            .shadowOptions(LightManager.ShadowOptions().apply {
                mapSize = 2048
                shadowCascades = 4
                shadowFar = 900f
                constantBias = 0.0012f
                normalBias = 0.6f
                stable = false
            })
            .build(engine, sun)
        scene.addEntity(sun)
    }

    private fun setFog(day: Boolean) {
        view.fogOptions = view.fogOptions.apply {
            enabled = day
            distance = 180f
            density = 0.000045f
            height = 0f
            heightFalloff = 0.00055f
            maximumOpacity = 0.92f
            fogColorFromIbl = true
            inScatteringStart = 0f
            inScatteringSize = 28f
        }
    }

    private suspend fun load() {
        try {
            val assets = context.assets
            setLoading(0.04f, "تحميل الخامات الواقعية")
            val decoded = withContext(Dispatchers.IO) {
                WorldFiles.surfaces.associateWith { name ->
                    Triple(
                        WorldFiles.decode(assets, "world/tex/${name}_c.jpg"),
                        WorldFiles.decode(assets, "world/tex/${name}_n.jpg"),
                        WorldFiles.decode(assets, "world/tex/${name}_arm.jpg")
                    )
                }
            }
            setLoading(0.42f, "رفع الخامات إلى المعالج الرسومي")
            decoded.forEach { (name, t) ->
                surfaces[name] = SurfaceSet(
                    name,
                    TextureUpload.upload(engine, t.first, srgb = true),
                    TextureUpload.upload(engine, t.second, srgb = false),
                    TextureUpload.upload(engine, t.third, srgb = false)
                )
            }
            val buffers = withContext(Dispatchers.IO) {
                listOf(
                    WorldFiles.MATERIAL_WORLD, WorldFiles.MATERIAL_COLOR, WorldFiles.MATERIAL_SIGN,
                    WorldFiles.MATERIAL_PARTICLE, WorldFiles.MATERIAL_PORTAL, WorldFiles.SKY_DAY, WorldFiles.IBL_DAY,
                    WorldFiles.SKY_SPACE, WorldFiles.IBL_SPACE, WorldFiles.GUIDE, WorldFiles.SHUTTLE
                ).associateWith { WorldFiles.readAsset(assets, it) }
            }
            setLoading(0.6f, "تجهيز الإضاءة والسماء")
            fun mat(path: String) = Material.Builder().payload(buffers.getValue(path), buffers.getValue(path).remaining()).build(engine).also { materials += it }
            val worldMat = mat(WorldFiles.MATERIAL_WORLD)
            val colorMat = mat(WorldFiles.MATERIAL_COLOR)
            val signMat = mat(WorldFiles.MATERIAL_SIGN)
            val particleMat = mat(WorldFiles.MATERIAL_PARTICLE)
            val portalMat = mat(WorldFiles.MATERIAL_PORTAL)

            val dayIblBundle = KTX1Loader.createIndirectLight(engine, buffers.getValue(WorldFiles.IBL_DAY), KTX1Loader.Options())
            dayIbl = dayIblBundle.indirectLight?.also { it.intensity = 36_000f }
            dayIblBundle.cubemap?.let { envTextures += it }
            val daySkyBundle = KTX1Loader.createSkybox(engine, buffers.getValue(WorldFiles.SKY_DAY), KTX1Loader.Options())
            daySkyBundle.cubemap?.let { tex ->
                envTextures += tex
                daySky = Skybox.Builder().environment(tex).intensity(30_000f).build(engine)
            }
            daySkyBundle.skybox?.let { engine.destroySkybox(it) }
            val spaceIblBundle = KTX1Loader.createIndirectLight(engine, buffers.getValue(WorldFiles.IBL_SPACE), KTX1Loader.Options())
            spaceIbl = spaceIblBundle.indirectLight?.also { it.intensity = 9_000f }
            spaceIblBundle.cubemap?.let { envTextures += it }
            val spaceSkyBundle = KTX1Loader.createSkybox(engine, buffers.getValue(WorldFiles.SKY_SPACE), KTX1Loader.Options())
            spaceSkyBundle.cubemap?.let { tex ->
                envTextures += tex
                spaceSky = Skybox.Builder().environment(tex).intensity(5_500f).build(engine)
            }
            spaceSkyBundle.skybox?.let { engine.destroySkybox(it) }
            setDayEnvironment(true)

            fun surf(name: String, tile: Float, tint: FloatArray = floatArrayOf(1f, 1f, 1f), rough: Float = 0f, variation: Float = 0.35f, normal: Float = 1f, offset: Boolean = false) =
                surfaces.getValue(name).instance(worldMat, tile, tint, normal, rough, variation).also {
                    if (offset) it.setPolygonOffset(-1f, -2f)
                    sharedInstances += it
                }
            val surfaceInstances = mapOf(
                "terrain" to surf("terrain", 9f, variation = 0.85f, normal = 0.8f),
                "asphalt" to surf("asphalt", 6f, rough = -0.05f, offset = true),
                "pavers" to surf("pavers", 3.2f, floatArrayOf(0.92f, 0.9f, 0.88f), variation = 0.25f),
                "hexpad" to surf("hexpad", 4f, floatArrayOf(0.85f, 0.86f, 0.9f), offset = true),
                "marble" to surf("marble", 3f, rough = -0.1f, variation = 0.1f),
                "metal" to surf("metal", 2.5f, floatArrayOf(0.8f, 0.82f, 0.86f), variation = 0.15f),
                "podium" to surf("podium", 4f, floatArrayOf(0.95f, 0.93f, 0.9f), variation = 0.2f)
            )
            val facadeTiles = floatArrayOf(20f, 24f, 28f, 15f, 15f, 15f)
            val facadeInstances = facadeTiles.mapIndexed { i, tile ->
                surf("facade$i", tile, variation = 0.12f, rough = -0.04f)
            }
            val colorLit = colorMat.createInstance().apply {
                setParameter("roughness", 0.42f); setParameter("metallic", 0.6f); setParameter("emissiveIntensity", 3.2f)
            }
            val colorDecal = colorMat.createInstance().apply {
                setParameter("roughness", 0.6f); setParameter("metallic", 0f); setParameter("emissiveIntensity", 2.2f)
                setPolygonOffset(-2f, -4f)
            }
            sharedInstances += colorLit; sharedInstances += colorDecal
            palette = WorldPalette(surfaceInstances, facadeInstances, colorLit, colorDecal, signMat, portalMat)
            builder = CityBuilder(engine, palette!!, SignPainter(context.assets))

            terrain = buildTerrain(surfaceInstances.getValue("terrain"))
            scene.addEntity(terrain!!.entity)

            val effectsMi = particleMat.createInstance().apply { setParameter("intensity", 1f) }
            val skyMi = particleMat.createInstance().apply { setParameter("intensity", 1f) }
            sharedInstances += effectsMi; sharedInstances += skyMi
            effects = ParticleSystem(engine, effectsMi, 3600).also { scene.addEntity(it.entity) }
            sky = ParticleSystem(engine, skyMi, 4200, cameraRelative = true, priority = 0).also {
                scene.addEntity(it.entity)
                it.visible = false
            }

            setLoading(0.72f, "تحميل الشخصية والمركبة")
            materialProvider = UbershaderProvider(engine)
            assetLoader = AssetLoader(engine, materialProvider!!, EntityManager.get())
            resourceLoader = ResourceLoader(engine, true)
            guide = assetLoader!!.createAsset(buffers.getValue(WorldFiles.GUIDE))
            shuttle = assetLoader!!.createAsset(buffers.getValue(WorldFiles.SHUTTLE))
            listOfNotNull(guide, shuttle).forEach { asset ->
                resourceLoader!!.asyncBeginLoad(asset)
                pendingAssets += asset
                val rm = engine.renderableManager
                asset.renderableEntities.forEach { e ->
                    val ri = rm.getInstance(e)
                    rm.setCastShadows(ri, true)
                    rm.setReceiveShadows(ri, true)
                }
            }
            shuttle?.let { scene.addEntities(it.entities) }
            guide?.let { scene.addEntities(it.entities); characterInScene = true }

            setLoading(0.86f, "بناء ${cities[cityIndex].nameAr}")
            city = builder!!.build(cities[cityIndex], cities[cityIndex].domain.colorArgb.toInt())
            scene.addEntities(city!!.entities)
            setLoading(1f, "")
            mode = WorldMode.WALK
            charYaw = 0f
            showTitle(cities[cityIndex].nameAr)
            onSpeak("أهلاً بك في ${cities[cityIndex].nameAr}. امشي للبوابة عشان تشوف المواقع، أو اركب المركبة وسافر لمدينة تانية", true)
        } catch (t: Throwable) {
            error = "تعذر تحميل العالم ثلاثي الأبعاد: ${t.message ?: t.javaClass.simpleName}"
            publishHud()
        }
    }

    private fun setLoading(p: Float, label: String) {
        loadingProgress = p; loadingLabel = label
        publishHud()
    }

    private fun setDayEnvironment(day: Boolean) {
        scene.skybox = if (day) daySky else spaceSky
        scene.indirectLight = if (day) dayIbl else spaceIbl
        setFog(day)
        val lm = engine.lightManager
        val li = lm.getInstance(sun)
        if (li != 0) {
            lm.setIntensity(li, if (day) 105_000f else 70_000f)
            lm.setColor(li, 1f, if (day) 0.96f else 0.92f, if (day) 0.9f else 0.86f)
        }
    }

    /** Terrain heights: flat around the city, rolling hills and distant ridges further out. */
    private fun terrainHeight(x: Float, z: Float): Float {
        val r = hypot(x.toDouble(), (z - 600f).toDouble()).toFloat()
        val near = smooth(7_000f, 22_000f, r)
        val far = smooth(28_000f, 70_000f, r)
        val hills = 170f * sin(x * 0.00021f + 1.3f) * cos(z * 0.00017f) +
            90f * sin(x * 0.00053f + z * 0.00041f) + 55f * cos(z * 0.0009f - x * 0.0003f)
        val ridges = 900f * abs(sin(x * 0.000061f + 0.4f) * cos(z * 0.000047f + 1.1f)) + 400f * abs(sin((x + z) * 0.00009f))
        return near * (hills + 120f) + far * ridges
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun buildTerrain(mi: MaterialInstance): Mesh {
        val b = MeshBuilder()
        val n = 96
        val size = 170_000f
        val color = MeshBuilder.WHITE
        val index = Array(n + 1) { IntArray(n + 1) }
        for (j in 0..n) for (i in 0..n) {
            // Denser rings near the city: remap the grid with a power curve.
            val u = (i / n.toFloat()) * 2f - 1f
            val v = (j / n.toFloat()) * 2f - 1f
            val x = Math.signum(u) * abs(u).let { it * it * 0.82f + it * 0.18f } * size / 2f
            val z = Math.signum(v) * abs(v).let { it * it * 0.82f + it * 0.18f } * size / 2f + 600f
            val h = terrainHeight(x, z)
            val e = 30f
            val nx = terrainHeight(x - e, z) - terrainHeight(x + e, z)
            val nz = terrainHeight(x, z - e) - terrainHeight(x, z + e)
            val normal = V3(nx, 2f * e, nz).normalized()
            val tangent = (V3.Z cross normal).normalized().let { if (it.length() < 0.5f) V3.X else it }
            index[j][i] = b.vertex(V3(x, h, z), tangentQuaternion(normal, tangent), x, -z, color)
        }
        for (j in 0 until n) for (i in 0 until n) {
            val a = index[j][i]; val bb = index[j][i + 1]; val c = index[j + 1][i + 1]; val d = index[j + 1][i]
            b.triangle(a, d, c); b.triangle(a, c, bb)
        }
        return b.build(engine, mi, castShadows = false, receiveShadows = true, priority = 5)
    }

    // ---------------------------------------------------------------- public actions

    fun setPaused(value: Boolean) {
        paused = value
        if (!value) lastFrame = 0L
    }

    fun toggleRun() { running = !running; publishHud() }

    fun selectDestination(index: Int) {
        if (index !in cities.indices) return
        destination = index
        if (index == cityIndex) {
            onSpeak("إنت بالفعل في ${cities[index].nameAr}", true)
        } else {
            val d = WorldLayout.distanceAu(cities[cityIndex], cities[index])
            val hint = when (mode) {
                WorldMode.WALK -> "اركب المركبة واطلع للسما عشان تفعّل القفزة الضوئية"
                WorldMode.PARKED -> "اضغط إقلاع وقفزة"
                else -> "اضغط قفزة ضوئية"
            }
            onSpeak("الوجهة ${cities[index].nameAr}، على بعد ${WorldText.formatNumber(d, 1)} وحدة فلكية. $hint", true)
        }
        publishHud()
    }

    fun board() {
        if (mode != WorldMode.WALK || !nearShip()) return
        fadeThen {
            setCharacterVisible(false)
            mode = WorldMode.PARKED
            camYawOffset = 0f
            onSpeak("ركبتي المركبة. اختار الوجهة واضغط إقلاع", true)
        }
    }

    fun exitShip() {
        if (mode != WorldMode.PARKED) return
        fadeThen {
            val side = V3(cos(shipYaw), 0f, -sin(shipYaw))
            charPos = V3(shipPos.x + side.x * 15f, GROUND_Y, shipPos.z + side.z * 15f + 18f)
            charYaw = shipYaw
            setCharacterVisible(true)
            mode = WorldMode.WALK
            camYawOffset = 0f
        }
    }

    fun takeoff(thenWarp: Boolean) {
        if (mode != WorldMode.PARKED) return
        autoWarpAfterTakeoff = thenWarp && destination != null && destination != cityIndex
        mode = WorldMode.TAKEOFF
        phaseTimer = 0f
        // Already at the destination pad: suppress the approach callouts for this city.
        callouts.reset(); callouts.update(0f)
        shipVertical = 0f
        onSpeak(if (autoWarpAfterTakeoff) "إقلاع. هنفعّل القفزة الضوئية أول ما نوصل للارتفاع الآمن" else "إقلاع", true)
    }

    fun engageWarp() {
        val dest = destination ?: return
        if (dest == cityIndex) return
        if (mode != WorldMode.FLIGHT && mode != WorldMode.APPROACH) return
        mode = WorldMode.WARP_CHARGE
        autopilot = false
        phaseTimer = 0f
        onSpeak("جاري تجهيز محرك القفزة الضوئية. الوجهة ${cities[dest].nameAr}", true)
    }

    fun requestLanding() {
        if (mode != WorldMode.FLIGHT) return
        if (padDistance() > 30_000f) return
        mode = WorldMode.APPROACH
        autopilot = true
        onSpeak("تم تفعيل الهبوط التلقائي على مهبط ${cities[cityIndex].nameAr}", true)
    }

    fun manualControl() {
        if (mode == WorldMode.APPROACH) { mode = WorldMode.FLIGHT; autopilot = false }
    }

    fun enterDoor() {
        if (mode != WorldMode.WALK || nearDoor < 0) return
        enteringDoor = nearDoor
        enterTimer = 0f
        mode = WorldMode.ENTERING
        val door = city?.def?.doors?.getOrNull(enteringDoor) ?: return
        onSpeak("جاري الدخول إلى موقع ${door.source.nameAr}", true)
    }

    fun quickTravel(doorIndex: Int) {
        val c = city ?: return
        if (mode != WorldMode.WALK) return
        val front = c.doorFronts.getOrNull(doorIndex) ?: return
        val door = c.def.doors[doorIndex]
        fadeThen {
            charPos = V3(front.x, GROUND_Y, front.z)
            charYaw = if (door.side > 0) (PI / 2).toFloat() else (-PI / 2).toFloat()
            camYawOffset = 0f
            announcedDoor = -1
        }
    }

    fun quickTravelToPort() {
        if (mode != WorldMode.WALK) return
        fadeThen {
            charPos = V3(14f, GROUND_Y, WorldLayout.PAD_Z + 22f)
            charYaw = PI.toFloat()
            camYawOffset = 0f
        }
    }

    fun currentCity(): CityDef? = city?.def

    fun destroy() {
        choreographer.removeFrameCallback(this)
        scope.cancel()
        uiHelper.detach()
        city?.let { scene.removeEntities(it.entities); it.destroy(engine) }
        nextCity?.destroy(engine)
        effects?.destroy(); sky?.destroy()
        terrain?.destroy(engine)
        listOfNotNull(guide, shuttle).forEach { asset ->
            scene.removeEntities(asset.entities)
            assetLoader?.destroyAsset(asset)
        }
        resourceLoader?.destroy()
        assetLoader?.destroy()
        materialProvider?.destroyMaterials()
        materialProvider?.destroy()
        sharedInstances.forEach { engine.destroyMaterialInstance(it) }
        surfaces.values.forEach { it.destroy(engine) }
        listOfNotNull(daySky, spaceSky).forEach { engine.destroySkybox(it) }
        listOfNotNull(dayIbl, spaceIbl).forEach { engine.destroyIndirectLight(it) }
        envTextures.forEach { engine.destroyTexture(it) }
        materials.forEach { engine.destroyMaterial(it) }
        engine.destroyEntity(sun)
        EntityManager.get().destroy(sun)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }

    // ---------------------------------------------------------------- frame loop

    override fun doFrame(frameTimeNanos: Long) {
        choreographer.postFrameCallback(this)
        if (paused) return
        val dt = if (lastFrame == 0L) 1f / 60f else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0.001f, 0.05f)
        lastFrame = frameTimeNanos
        time += dt

        resourceLoader?.let { rl ->
            if (pendingAssets.isNotEmpty()) {
                rl.asyncUpdateLoad()
                if (rl.asyncGetLoadProgress() >= 1f) {
                    pendingAssets.forEach { it.releaseSourceData() }
                    pendingAssets.clear()
                }
            }
        }

        if (mode != WorldMode.LOADING) {
            step(dt)
            updateCamera(dt)
            updateParticles(dt)
        } else {
            camera.lookAt(0.0, 40.0, -400.0, 0.0, 30.0, 0.0, 0.0, 1.0, 0.0)
        }
        applyProjection()

        hudTimer += dt
        if (hudTimer > 0.08f) { hudTimer = 0f; publishHud() }

        val sc = swapChain ?: return
        if (uiHelper.isReadyToRender && renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    private fun step(dt: Float) {
        // Screen flash used for warp and teleport transitions.
        val fadeSpeed = if (flashTarget > flash) 5f else 2.2f
        flash += (flashTarget - flash) * min(1f, dt * fadeSpeed * 2f)
        if (pendingAction != null && flash > 0.96f) {
            pendingAction?.invoke(); pendingAction = null; flashTarget = 0f
        }
        if (titleTimer > 0f) titleTimer -= dt

        when (mode) {
            WorldMode.WALK -> stepWalk(dt)
            WorldMode.ENTERING -> stepEntering(dt)
            WorldMode.PARKED -> { shipSpeed = 0f }
            WorldMode.TAKEOFF -> stepTakeoff(dt)
            WorldMode.FLIGHT -> stepFlight(dt, manual = true)
            WorldMode.APPROACH -> stepApproach(dt)
            WorldMode.LANDING -> stepLanding(dt)
            WorldMode.WARP_CHARGE -> stepWarpCharge(dt)
            WorldMode.WARP -> stepWarp(dt)
            WorldMode.LOADING -> Unit
        }
        updateCharacterTransform(dt)
        updateShipTransform()
        updateCityCallouts()
    }

    private fun fadeThen(action: () -> Unit) {
        pendingAction = action
        flashTarget = 1f
    }

    private fun showTitle(text: String) { titleCard = text; titleTimer = 4.5f }

    // ---------------------------------------------------------------- walking

    private fun stepWalk(dt: Float) {
        val turn = (if (left) 1f else 0f) - (if (right) 1f else 0f)
        charYaw += turn * 2.1f * dt
        val forwardInput = (if (up) 1f else 0f) - (if (down) 0.6f else 0f)
        val target = when {
            forwardInput > 0f -> if (running) RUN_SPEED else WALK_SPEED
            forwardInput < 0f -> -WALK_SPEED * 0.7f
            else -> 0f
        }
        charSpeed += (target - charSpeed) * min(1f, dt * 7f)
        if (abs(charSpeed) > 0.01f) {
            val f = V3(sin(charYaw), 0f, cos(charYaw))
            val next = V3(charPos.x + f.x * charSpeed * dt, GROUND_Y, charPos.z + f.z * charSpeed * dt)
            charPos = resolveCollision(charPos, next)
        }
        // Gently swing the camera back behind the character while moving.
        if (abs(charSpeed) > 0.2f || turn != 0f) camYawOffset *= (1f - min(1f, dt * 1.6f))
        val animTarget = when {
            abs(charSpeed) > 3f -> ANIM_RUN
            abs(charSpeed) > 0.25f -> ANIM_WALK
            else -> ANIM_IDLE
        }
        playAnim(animTarget)
        nearDoor = findNearDoor()
        if (nearDoor >= 0 && nearDoor != announcedDoor) {
            announcedDoor = nearDoor
            city?.def?.doors?.getOrNull(nearDoor)?.let { onSpeak("أنت أمام بوابة موقع ${it.source.nameAr}", true) }
        } else if (nearDoor < 0 && announcedDoor >= 0) {
            val front = city?.doorFronts?.getOrNull(announcedDoor)
            if (front == null || hypot(front.x - charPos.x, front.z - charPos.z) > 20f) announcedDoor = -1
        }
    }

    private fun stepEntering(dt: Float) {
        val c = city ?: return
        enterTimer += dt
        val portal = c.portalCenters[enteringDoor]
        val dx = portal.x - charPos.x; val dz = portal.z - charPos.z
        val dist = hypot(dx, dz)
        charYaw = atan2(dx, dz)
        if (dist > 0.6f) {
            val sp = WALK_SPEED * 1.1f * dt
            charPos = V3(charPos.x + dx / dist * sp, GROUND_Y, charPos.z + dz / dist * sp)
            playAnim(ANIM_WALK)
        } else playAnim(ANIM_IDLE)
        repeat(4) {
            effects?.emit(
                portal.x + rnd(-1f, 1f) * 2.8f, portal.y + rnd(0f, 6.5f), portal.z + rnd(-3f, 3f),
                rnd(-0.4f, 0.4f), rnd(0.5f, 2f), rnd(-0.4f, 0.4f), 1.4f, 0.12f, 0.02f, 1.5f, 1.4f, 2.2f, 1f
            )
        }
        if (enterTimer > 2.1f && pendingAction == null && flashTarget == 0f) {
            val door = c.def.doors[enteringDoor]
            val front = c.doorFronts[enteringDoor]
            fadeThen {
                charPos = V3(front.x, GROUND_Y, front.z)
                charYaw = if (door.side > 0) (-PI / 2).toFloat() else (PI / 2).toFloat()
                mode = WorldMode.WALK
                camYawOffset = 0f
                onOpenSource(door.source.id)
            }
        }
    }

    private fun resolveCollision(from: V3, to: V3): V3 {
        val c = city ?: return to
        val r = 0.45f
        var x = to.x; var z = to.z
        for (o in c.obstacles) {
            if (o.contains(x, z, r)) {
                // Slide along whichever axis is free.
                when {
                    !o.contains(from.x, z, r) -> x = from.x
                    !o.contains(x, from.z, r) -> z = from.z
                    else -> { x = from.x; z = from.z }
                }
            }
        }
        // Ship hull while parked.
        if (mode == WorldMode.WALK) {
            val dx = x - shipPos.x; val dz = z - shipPos.z
            val sx = dx; val sz = dz
            if (abs(sx) < 12f && abs(sz) < 19f) { x = from.x; z = from.z }
        }
        x = x.coerceIn(-940f, 940f)
        z = z.coerceIn(-340f, c.def.avenueLength + 560f)
        return V3(x, GROUND_Y, z)
    }

    private fun nearShip(): Boolean = hypot(charPos.x - shipPos.x, charPos.z - shipPos.z) < 26f

    private fun findNearDoor(): Int {
        val c = city ?: return -1
        var best = -1; var bestD = 7.5f
        c.doorFronts.forEachIndexed { i, p ->
            val d = hypot(p.x - charPos.x, p.z - charPos.z)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun playAnim(index: Int) {
        if (index != animCurrent) {
            animPrevious = animCurrent
            animPrevTime = animTime
            animCurrent = index
            animTime = 0f
            animBlend = 0f
        }
    }

    private fun updateCharacterTransform(dt: Float) {
        val asset = guide ?: return
        val animator = asset.instance.animator
        if (animator.animationCount == 0) return
        val rate = when (animCurrent) {
            ANIM_WALK -> (abs(charSpeed) / 1.21f).coerceIn(0.6f, 1.5f)
            ANIM_RUN -> (abs(charSpeed) / 2.77f).coerceIn(0.9f, 1.8f)
            else -> 1f
        }
        animTime += dt * rate
        animPrevTime += dt
        animBlend = min(1f, animBlend + dt / 0.28f)
        val cur = animCurrent.coerceAtMost(animator.animationCount - 1)
        val prev = animPrevious.coerceAtMost(animator.animationCount - 1)
        val curDur = animator.getAnimationDuration(cur)
        val t = if (charSpeed < -0.05f && cur == ANIM_WALK) curDur - (animTime % curDur) else animTime % curDur
        animator.applyAnimation(cur, t)
        if (animBlend < 1f) animator.applyCrossFade(prev, animPrevTime % animator.getAnimationDuration(prev), animBlend)
        animator.updateBoneMatrices()
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(asset.root), Xf.yaw(charPos, charYaw))
    }

    private fun setCharacterVisible(visible: Boolean) {
        val asset = guide ?: return
        if (visible && !characterInScene) { scene.addEntities(asset.entities); characterInScene = true }
        if (!visible && characterInScene) { scene.removeEntities(asset.entities); characterInScene = false }
    }

    // ---------------------------------------------------------------- flight

    private fun shipForward() = V3(sin(shipYaw) * cos(shipPitch), sin(shipPitch), cos(shipYaw) * cos(shipPitch))

    private fun padDistance(): Float {
        val dx = WorldLayout.PAD_X - shipPos.x; val dy = GROUND_Y - shipPos.y; val dz = WorldLayout.PAD_Z - shipPos.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun stepTakeoff(dt: Float) {
        phaseTimer += dt
        val targetAlt = 140f
        shipVertical += ((if (shipPos.y < targetAlt) 28f else 0f) - shipVertical) * min(1f, dt * 1.4f)
        shipPos = V3(shipPos.x, shipPos.y + shipVertical * dt, shipPos.z)
        shipPitch += (0.05f - shipPitch) * min(1f, dt)
        // Ground dust while the thrusters are close to the pad.
        if (shipPos.y < 40f) repeat(6) {
            val a = rnd(0f, (2 * PI).toFloat())
            effects?.emit(
                shipPos.x + cos(a) * 6f, GROUND_Y + 0.4f, shipPos.z + sin(a) * 6f,
                cos(a) * rnd(8f, 22f), rnd(0.2f, 2f), sin(a) * rnd(8f, 22f),
                rnd(1.2f, 2.4f), 1.2f, 6f, 0.16f, 0.14f, 0.11f, 0.6f, dragCoeff = 1.6f
            )
        }
        if (shipPos.y >= targetAlt - 4f && phaseTimer > 3.5f) {
            mode = WorldMode.FLIGHT
            shipSpeed = 60f
            throttle = 0.3f
            if (autoWarpAfterTakeoff) { autoWarpAfterTakeoff = false; engageWarp() }
        }
    }

    private fun stepFlight(dt: Float, manual: Boolean) {
        val pitchIn = (if (up) 1f else 0f) - (if (down) 1f else 0f)
        val yawIn = (if (left) 1f else 0f) - (if (right) 1f else 0f)
        if (manual) {
            shipPitch = (shipPitch + pitchIn * 0.65f * dt).coerceIn(-1.1f, 1.1f)
            if (pitchIn == 0f) shipPitch *= (1f - min(1f, dt * 0.15f))
            yawRate += (yawIn * 0.75f - yawRate) * min(1f, dt * 3f)
            shipYaw += yawRate * dt
            val targetSpeed = 35f + throttle * throttle * 3465f
            val accel = if (targetSpeed > shipSpeed) 420f + shipSpeed * 0.6f else 900f + shipSpeed
            shipSpeed = approach(shipSpeed, targetSpeed, accel * dt)
        }
        integrateShip(dt)
    }

    private fun integrateShip(dt: Float) {
        val f = shipForward()
        var p = shipPos + f * (shipSpeed * dt)
        val floor = terrainHeight(p.x, p.z) + 28f
        if (p.y < floor) {
            p = V3(p.x, floor, p.z)
            if (shipPitch < 0f) shipPitch *= 0.85f
        }
        shipPos = V3(p.x, min(p.y, 16_000f), p.z)
        shipRoll += ((-yawRate * 0.9f).coerceIn(-0.9f, 0.9f) - shipRoll) * min(1f, dt * 2.5f)
        emitExhaust(dt, (shipSpeed / 3500f).coerceIn(0.15f, 1f))
    }

    private fun stepApproach(dt: Float) {
        val dx = WorldLayout.PAD_X - shipPos.x
        val dz = (WorldLayout.PAD_Z - 30f) - shipPos.z
        val d = hypot(dx, dz)
        if (up || down || left || right) { manualControl(); return }
        val desiredYaw = atan2(dx, dz)
        val yawErr = wrapAngle(desiredYaw - shipYaw)
        yawRate += ((yawErr * 1.2f).coerceIn(-0.6f, 0.6f) - yawRate) * min(1f, dt * 2f)
        shipYaw += yawRate * dt
        val desiredAlt = min(WorldLayout.WARP_EXIT_ALTITUDE, 60f + d * 0.07f)
        val desiredPitch = atan2(desiredAlt - shipPos.y, max(d, 200f)).coerceIn(-0.5f, 0.35f)
        shipPitch += (desiredPitch - shipPitch) * min(1f, dt * 1.5f)
        val desiredSpeed = (25f + d * 0.12f).coerceIn(28f, 4_200f)
        shipSpeed = approach(shipSpeed, desiredSpeed, (300f + shipSpeed * 0.9f) * dt)
        integrateShip(dt)
        if (d < 750f) {
            mode = WorldMode.LANDING
            landingPhase = 0
            onSpeak("بدء الهبوط النهائي", false)
        }
    }

    private fun stepLanding(dt: Float) {
        val hover = V3(WorldLayout.PAD_X, GROUND_Y + 40f, WorldLayout.PAD_Z)
        if (landingPhase == 0) {
            val to = hover - shipPos
            val d = to.length()
            val speed = (d * 0.55f).coerceIn(4f, max(30f, shipSpeed))
            shipSpeed = approach(shipSpeed, speed, 60f * dt)
            val dir = to.normalized()
            shipPos = shipPos + dir * min(d, shipSpeed * dt)
            val horizontal = hypot(to.x, to.z)
            val desiredYaw = if (horizontal > 30f) atan2(to.x, to.z) else 0f
            val err = wrapAngle(desiredYaw - shipYaw)
            yawRate = (err * 1.5f).coerceIn(-0.5f, 0.5f)
            shipYaw += yawRate * dt
            shipPitch += (0f - shipPitch) * min(1f, dt * 2f)
            emitExhaust(dt, 0.25f)
            if (d < 2.5f) { landingPhase = 1; shipSpeed = 0f; yawRate = 0f }
        } else {
            shipYaw += wrapAngle(0f - shipYaw) * min(1f, dt * 2f)
            val y = max(GROUND_Y, shipPos.y - (2f + (shipPos.y - GROUND_Y) * 0.35f) * dt)
            shipPos = V3(WorldLayout.PAD_X, y, WorldLayout.PAD_Z)
            if (y < 22f) repeat(5) {
                val a = rnd(0f, (2 * PI).toFloat())
                effects?.emit(
                    shipPos.x + cos(a) * 7f, GROUND_Y + 0.4f, shipPos.z + sin(a) * 7f,
                    cos(a) * rnd(6f, 18f), rnd(0.2f, 1.6f), sin(a) * rnd(6f, 18f),
                    rnd(1.2f, 2.2f), 1.2f, 5.5f, 0.16f, 0.14f, 0.11f, 0.55f, dragCoeff = 1.6f
                )
            }
            if (y <= GROUND_Y + 0.01f) {
                shipPos = V3(WorldLayout.PAD_X, GROUND_Y, WorldLayout.PAD_Z)
                shipYaw = 0f; shipPitch = 0f; shipRoll = 0f
                mode = WorldMode.PARKED
                autopilot = false
                showTitle(cities[cityIndex].nameAr)
                onSpeak("تم الهبوط بسلام. أهلاً بك في ${cities[cityIndex].nameAr}. اضغط انزل من المركبة وادخل المدينة", true)
            }
        }
    }

    private fun stepWarpCharge(dt: Float) {
        phaseTimer += dt
        shipPitch += (0.32f - shipPitch) * min(1f, dt * 1.2f)
        shipSpeed = approach(shipSpeed, 1_400f, 500f * dt)
        yawRate *= 0.9f
        integrateShip(dt)
        shake = min(1f, phaseTimer / 2.8f) * 0.6f
        emitExhaust(dt, 1f)
        if (phaseTimer > 2.8f && pendingAction == null) {
            fadeThen { enterWarp() }
        }
    }

    private fun enterWarp() {
        val dest = destination ?: return
        warpDistance = WorldLayout.distanceAu(cities[cityIndex], cities[dest]) * WorldLayout.AU_METERS
        warpTotal = WorldLayout.warpSeconds(warpDistance / WorldLayout.AU_METERS)
        warpPeak = WorldLayout.peakWarpFactor(warpDistance / WorldLayout.AU_METERS)
        // Swap the world: hide the city and ground, switch to deep space.
        city?.let { scene.removeEntities(it.entities) }
        terrain?.let { scene.removeEntity(it.entity) }
        effects?.clear()
        setDayEnvironment(false)
        nextCity?.destroy(engine)
        nextCity = builder?.build(cities[dest], cities[dest].domain.colorArgb.toInt())
        shipPos = V3(0f, 0f, 0f)
        shipYaw = 0f; shipPitch = 0f; shipRoll = 0f; yawRate = 0f
        shipSpeed = 0f
        mode = WorldMode.WARP
        phaseTimer = 0f
        seedSpace()
        val factor = WorldText.formatNumber(warpPeak, 0)
        onSpeak("انطلقنا بسرعة تفوق الضوء. السرعة القصوى $factor ضعف سرعة الضوء", true)
    }

    private fun stepWarp(dt: Float) {
        phaseTimer += dt
        shake = 0.35f + 0.25f * WorldLayout.warpSpeedShape(phaseTimer, warpTotal)
        shipRoll = sin(time * 0.7f) * 0.06f
        if (phaseTimer > warpTotal * 0.5f && phaseTimer - dt <= warpTotal * 0.5f) {
            onSpeak("منتصف الطريق إلى ${cities[destination ?: cityIndex].nameAr}", false)
        }
        emitExhaust(dt, 1f)
        if (phaseTimer >= warpTotal - 0.4f && pendingAction == null) fadeThen { exitWarp() }
    }

    private fun exitWarp() {
        val dest = destination ?: return
        city?.destroy(engine)
        city = nextCity ?: builder?.build(cities[dest], cities[dest].domain.colorArgb.toInt())
        nextCity = null
        cityIndex = dest
        city?.let { scene.addEntities(it.entities) }
        terrain?.let { scene.addEntity(it.entity) }
        sky?.clear(); sky?.visible = false
        effects?.clear()
        setDayEnvironment(true)
        shipPos = V3(0f, WorldLayout.WARP_EXIT_ALTITUDE, WorldLayout.PAD_Z - WorldLayout.WARP_EXIT_DISTANCE)
        shipYaw = 0f; shipPitch = -0.04f; shipRoll = 0f
        shipSpeed = 4_200f
        mode = WorldMode.APPROACH
        autopilot = true
        shake = 0f
        callouts.reset()
        callouts.update(WorldLayout.WARP_EXIT_DISTANCE)
        announcedDoor = -1
        onSpeak("خرجنا من القفزة الضوئية. أنت على بعد ستين كيلو من ${cities[dest].nameAr}", true)
    }

    private fun updateCityCallouts() {
        if (mode != WorldMode.APPROACH && mode != WorldMode.FLIGHT) return
        val crossed = callouts.update(padDistance()) ?: return
        onSpeak(WorldText.calloutFor(crossed, cities[cityIndex].nameAr), true)
        if (crossed <= 10_000f && crossed > 3_000f) showTitle(cities[cityIndex].nameAr)
    }

    /** Model placement of the shuttle: origin plus rolled basis (model +X, +Y, +Z). */
    private fun shipBasis(): Array<V3> {
        val f = shipForward()
        val r0 = (V3.Y cross f).normalized().let { if (it.length() < 0.5f) V3.X else it }
        val u0 = (f cross r0).normalized()
        val cr = cos(shipRoll); val sr = sin(shipRoll)
        val r = r0 * cr + u0 * sr
        val u = u0 * cr - r0 * sr
        // The model origin is at the bottom of the landing gear; pivot around the hull center while flying.
        val grounded = mode == WorldMode.PARKED || mode == WorldMode.WALK || mode == WorldMode.ENTERING ||
            mode == WorldMode.TAKEOFF || (mode == WorldMode.LANDING && landingPhase == 1)
        val pivot = if (grounded) 0f else 6f
        val origin = shipPos - u * pivot + V3(0f, pivot, 0f)
        return arrayOf(origin, r, u, f)
    }

    private fun updateShipTransform() {
        val asset = shuttle ?: return
        val (origin, r, u, f) = shipBasis()
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(asset.root), Xf.basis(origin, r, u, f))
    }

    private val nozzles = arrayOf(V3(0f, 6.4f, -18.9f), V3(1.45f, 4.3f, -18.9f), V3(-1.45f, 4.3f, -18.9f))

    private fun emitExhaust(dt: Float, power: Float) {
        val fx = effects ?: return
        val (origin, r, u, f) = shipBasis()
        val count = (power * 150f * dt).toInt().coerceIn(1, 12)
        val warping = mode == WorldMode.WARP
        val vel = if (warping) V3.ZERO else f * shipSpeed
        repeat(count) {
            val n = nozzles[random.nextInt(3)]
            val p = origin + r * n.x + u * n.y + f * n.z
            val jitter = V3(rnd(-1f, 1f), rnd(-1f, 1f), rnd(-1f, 1f)) * (1.2f + power)
            val back = f * -(25f + power * (if (warping) 260f else 90f)) + jitter
            val hot = rnd(0f, 1f) < 0.55f
            fx.emit(
                p.x, p.y, p.z,
                vel.x * 0.94f + back.x, vel.y * 0.94f + back.y, vel.z * 0.94f + back.z,
                rnd(0.22f, 0.5f) * (0.6f + power), 0.8f + power * 0.9f, 0.15f,
                if (hot) 0.5f else 1.25f, if (hot) 0.72f else 0.5f, if (hot) 1.7f else 0.22f, 1.3f,
                stretchFactor = 0.003f, dragCoeff = 0.35f
            )
        }
    }

    // ---------------------------------------------------------------- particles

    private fun seedSpace() {
        val s = sky ?: return
        s.clear()
        s.visible = true
        repeat(2600) {
            val d = randomDirection()
            val rr = rnd(6_000f, 9_000f)
            val warm = rnd(0f, 1f)
            val b = rnd(0.4f, 1f).let { it * it }
            s.emit(d.x * rr, d.y * rr, d.z * rr, 0f, 0f, 0f, 1e7f, rnd(6f, 18f) * (0.5f + b), rnd(6f, 18f) * (0.5f + b),
                (0.8f + 0.2f * warm) * b * 2.4f, 0.85f * b * 2.4f, (1f - 0.25f * warm) * b * 2.6f, 1f, twinkleAmount = 0.35f)
        }
        repeat(1100) { spawnStreak(s, initial = true) }
    }

    private fun spawnStreak(s: ParticleSystem, initial: Boolean) {
        val a = rnd(0f, (2 * PI).toFloat())
        val rad = rnd(10f, 160f)
        val z = if (initial) rnd(-200f, 2400f) else rnd(1600f, 2600f)
        val tint = rnd(0f, 1f)
        s.emit(cos(a) * rad, sin(a) * rad, z, 0f, 0f, -1f, 1e7f, rnd(0.25f, 0.9f), rnd(0.25f, 0.9f),
            0.5f + 0.5f * tint, 0.7f + 0.2f * tint, 1.6f, 0.9f, stretchFactor = 0.012f)
    }

    private fun updateParticles(dt: Float) {
        val fx = effects ?: return
        val s = sky ?: return
        if (mode == WorldMode.WARP) {
            val shape = WorldLayout.warpSpeedShape(phaseTimer, warpTotal)
            val streakSpeed = 900f + shape * 9000f
            // Stars live in camera space; streaks rush past along -Z (the ship flies toward +Z).
            for (i in 0 until s.liveCount) {
                val isStreak = i >= 2600
                if (isStreak) {
                    s.setVelocity(i, 0f, 0f, -streakSpeed)
                    s.setStretch(i, 0.006f + shape * 0.02f)
                }
            }
            s.update(dt)
            for (i in 2600 until s.liveCount) {
                if (s.positionZ(i) < -400f) {
                    val a = rnd(0f, (2 * PI).toFloat()); val rad = rnd(10f, 160f)
                    s.respawn(i, cos(a) * rad, sin(a) * rad, rnd(1600f, 2600f), 0f, 0f, -streakSpeed, 1e7f)
                }
            }
        }
        fx.update(dt)
        // Ambient sparks around portals near the character and a plaza fountain.
        val c = city
        if (c != null && (mode == WorldMode.WALK || mode == WorldMode.ENTERING)) {
            c.portalCenters.forEach { p ->
                if (hypot(p.x - charPos.x, p.z - charPos.z) < 260f && rnd(0f, 1f) < dt * 26f) {
                    val accent = MeshBuilder.color(c.def.domain.colorArgb and 0xFFFFFFFFL)
                    fx.emit(
                        p.x + rnd(-0.3f, 0.3f), p.y + rnd(0f, 7f), p.z + rnd(-3.2f, 3.2f),
                        rnd(-0.2f, 0.2f), rnd(0.3f, 1.4f), rnd(-0.2f, 0.2f), rnd(1.5f, 3.2f), 0.09f, 0.02f,
                        accent[0] * 3f + 0.2f, accent[1] * 3f + 0.2f, accent[2] * 3f + 0.2f, 1f, twinkleAmount = 0.6f
                    )
                }
            }
            val fz = c.fountain
            if (hypot(fz.x - charPos.x, fz.z - charPos.z) < 400f) repeat(5) {
                val a = rnd(0f, (2 * PI).toFloat()); val sp = rnd(0.4f, 1.8f)
                fx.emit(fz.x, fz.y + 0.2f, fz.z, cos(a) * sp, rnd(9f, 12.5f), sin(a) * sp, rnd(1.8f, 2.4f), 0.11f, 0.2f,
                    0.55f, 0.75f, 1f, 0.55f, gravityAccel = 9.8f, stretchFactor = 0.03f)
            }
            // Floating dust motes in the sunlight around the character.
            if (rnd(0f, 1f) < dt * 14f) {
                fx.emit(charPos.x + rnd(-14f, 14f), rnd(0.3f, 5f), charPos.z + rnd(-14f, 14f), rnd(-0.1f, 0.1f), rnd(-0.03f, 0.08f), rnd(-0.1f, 0.1f),
                    rnd(4f, 8f), 0.025f, 0.025f, 1.4f, 1.3f, 1.1f, 0.35f, twinkleAmount = 0.8f)
            }
        }
        val camPosNow = camPos
        val fwd = (camTarget - camPosNow).normalized()
        val rightV = (fwd cross V3.Y).normalized()
        val upV = (rightV cross fwd).normalized()
        fx.upload(camPosNow, rightV, upV)
        s.upload(if (mode == WorldMode.WARP) camPosNow else camPosNow, rightV, upV)
    }

    // ---------------------------------------------------------------- camera

    private fun updateCamera(dt: Float) {
        val desiredPos: V3
        val desiredTarget: V3
        var fovTarget = 52f
        when (mode) {
            WorldMode.WALK, WorldMode.ENTERING, WorldMode.LOADING -> {
                val yaw = charYaw + camYawOffset
                val behind = V3(sin(yaw), 0f, cos(yaw))
                val pivot = charPos + V3(0f, 1.5f, 0f)
                var dist = camDistance
                var p: V3
                while (true) {
                    p = pivot - behind * (cos(camPitch) * dist) + V3(0f, sin(camPitch) * dist, 0f)
                    val blocked = city?.obstacles?.any { it.contains(p.x, p.z, 0.25f) } == true
                    if (!blocked || dist < 1.2f) break
                    dist -= 0.3f
                }
                desiredPos = V3(p.x, max(p.y, GROUND_Y + 0.35f), p.z)
                desiredTarget = pivot + V3(0f, -0.1f, 0f) + behind * 0.6f
                fovTarget = if (abs(charSpeed) > 3f) 56f else 50f
            }
            else -> {
                val f = shipForward()
                val yaw = shipYaw + camYawOffset
                val back = V3(sin(yaw), 0f, cos(yaw))
                val speedNorm = (shipSpeed / 3500f).coerceIn(0f, 1f)
                val dist = when (mode) {
                    WorldMode.PARKED -> 62f
                    WorldMode.WARP -> 46f
                    else -> 58f + speedNorm * 34f
                }
                val height = if (mode == WorldMode.PARKED) 20f else 15f
                val center = if (mode == WorldMode.PARKED) shipPos + V3(0f, 6f, 0f) else shipPos + V3(0f, 6f, 0f)
                desiredPos = center - back * dist + V3(0f, height + sin(camPitch - 0.2f) * dist * 0.5f, 0f)
                desiredTarget = center + f * 26f
                fovTarget = when (mode) {
                    WorldMode.WARP -> 70f + 18f * WorldLayout.warpSpeedShape(phaseTimer, warpTotal)
                    WorldMode.WARP_CHARGE -> 58f + phaseTimer * 4f
                    else -> 52f + speedNorm * 14f
                }
            }
        }
        val k = when (mode) {
            WorldMode.WARP -> 1f
            WorldMode.WALK, WorldMode.ENTERING -> min(1f, dt * 7f)
            else -> min(1f, dt * 4.5f)
        }
        val snap = flash > 0.9f
        camPos = if (snap) desiredPos else camPos + (desiredPos - camPos) * k
        camTarget = if (snap) desiredTarget else camTarget + (desiredTarget - camTarget) * min(1f, k * 1.4f)
        if (mode == WorldMode.APPROACH || mode == WorldMode.FLIGHT || mode == WorldMode.WARP_CHARGE || mode == WorldMode.LANDING) {
            // At high speed the chase camera must keep up exactly or the ship drifts out of frame.
            val f = shipForward()
            val back = V3(sin(shipYaw + camYawOffset), 0f, cos(shipYaw + camYawOffset))
            val lagged = shipPos + V3(0f, 6f, 0f) - back * (58f + (shipSpeed / 3500f).coerceIn(0f, 1f) * 34f) + V3(0f, 15f, 0f)
            camPos = camPos + (lagged - camPos) * min(1f, dt * 9f)
            camTarget = shipPos + V3(0f, 6f, 0f) + f * 26f
        }
        camFov += (fovTarget - camFov) * min(1f, dt * 3f)
        var ex = 0f; var ey = 0f
        if (shake > 0f) { ex = (Math.random().toFloat() - 0.5f) * shake * 0.6f; ey = (Math.random().toFloat() - 0.5f) * shake * 0.6f }
        camera.lookAt(
            camPos.x.toDouble() + ex, camPos.y.toDouble() + ey, camPos.z.toDouble(),
            camTarget.x.toDouble(), camTarget.y.toDouble(), camTarget.z.toDouble(),
            0.0, 1.0, 0.0
        )
    }

    private fun applyProjection() {
        val aspect = width.toDouble() / height.toDouble()
        val (near, far) = when (mode) {
            WorldMode.WALK, WorldMode.ENTERING, WorldMode.LOADING -> 0.12 to 40_000.0
            WorldMode.WARP -> 1.0 to 20_000.0
            else -> 1.5 to 160_000.0
        }
        camera.setProjection(camFov.toDouble(), aspect, near, far, Camera.Fov.VERTICAL)
    }

    private fun onTouch(e: MotionEvent) {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastTouchX = e.x; lastTouchY = e.y }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1) {
                val dx = e.x - lastTouchX; val dy = e.y - lastTouchY
                camYawOffset -= dx * 0.006f
                camPitch = (camPitch + dy * 0.004f).coerceIn(-0.25f, 1.1f)
                lastTouchX = e.x; lastTouchY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> { lastTouchX = e.x; lastTouchY = e.y }
        }
    }

    // ---------------------------------------------------------------- hud

    private fun publishHud() {
        val dest = destination
        val c = cities.getOrNull(cityIndex)
        val speed: Double
        val distance: String
        when (mode) {
            WorldMode.WARP -> {
                val shape = WorldLayout.warpSpeedShape(phaseTimer, warpTotal)
                speed = warpPeak * shape * WorldLayout.LIGHT_SPEED
                val remaining = warpDistance * (1.0 - WorldLayout.warpProgress(phaseTimer, warpTotal))
                distance = WorldText.formatDistance(remaining)
            }
            WorldMode.WALK, WorldMode.ENTERING -> {
                speed = abs(charSpeed).toDouble()
                distance = ""
            }
            WorldMode.LOADING -> { speed = 0.0; distance = "" }
            else -> {
                speed = shipSpeed.toDouble()
                distance = WorldText.formatDistance(padDistance().toDouble())
            }
        }
        val door = city?.def?.doors?.getOrNull(nearDoor)?.takeIf { mode == WorldMode.WALK }
        onHud(
            WorldHud(
                mode = mode,
                loadingProgress = loadingProgress,
                loadingLabel = loadingLabel,
                cityIndex = cityIndex,
                cityName = c?.nameAr ?: "",
                destinationIndex = dest,
                speedText = WorldText.formatSpeed(speed),
                distanceText = distance,
                altitudeText = if (mode in setOf(WorldMode.FLIGHT, WorldMode.APPROACH, WorldMode.LANDING, WorldMode.TAKEOFF, WorldMode.WARP_CHARGE))
                    "${WorldText.formatNumber((shipPos.y - GROUND_Y).toDouble(), 0)} م" else "",
                nearShip = mode == WorldMode.WALK && nearShip(),
                nearDoorName = door?.source?.nameAr,
                nearDoorSourceId = door?.source?.id,
                running = running,
                canLand = mode == WorldMode.FLIGHT && padDistance() < 30_000f,
                canWarp = (mode == WorldMode.FLIGHT || mode == WorldMode.APPROACH) && dest != null && dest != cityIndex,
                autopilot = autopilot,
                throttle = throttle,
                flash = flash.coerceIn(0f, 1f),
                titleCard = titleCard,
                titleAlpha = (titleTimer / 0.8f).coerceIn(0f, 1f),
                warpFactorText = if (mode == WorldMode.WARP) WorldText.formatSpeed(speed) else "",
                error = error
            )
        )
    }

    // ---------------------------------------------------------------- utils

    private val random = java.util.Random(42)
    private fun rnd(a: Float, b: Float) = a + (b - a) * random.nextFloat()
    private fun randomDirection(): V3 {
        val z = rnd(-1f, 1f); val a = rnd(0f, (2 * PI).toFloat()); val r = sqrt(1f - z * z)
        return V3(r * cos(a), z, r * sin(a))
    }
    private fun approach(v: Float, target: Float, maxDelta: Float) =
        if (v < target) min(target, v + maxDelta) else max(target, v - maxDelta)
    private fun wrapAngle(a: Float): Float {
        var x = a
        while (x > PI) x -= (2 * PI).toFloat()
        while (x < -PI) x += (2 * PI).toFloat()
        return x
    }
}
