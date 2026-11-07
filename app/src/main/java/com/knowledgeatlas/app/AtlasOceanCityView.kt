package com.knowledgeatlas.app

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A real-time OpenGL ES 2.0 underwater city.
 * Geometry, perspective, lighting, water caustics, particles and picking are rendered on-device.
 */
class AtlasOceanCityView(
    context: Context,
    private val onBuildingSelected: (Int) -> Unit
) : GLSurfaceView(context) {

    private val cityRenderer = OceanCityRenderer { id -> post { onBuildingSelected(id) } }
    private val densityScale = resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragged = false
    private var scaling = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            cityRenderer.zoom(detector.scaleFactor)
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(cityRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        keepScreenOn = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragged = false
                scaling = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                scaling = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress && !scaling) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (kotlin.math.abs(event.x - downX) > 7f * densityScale ||
                        kotlin.math.abs(event.y - downY) > 7f * densityScale
                    ) dragged = true
                    cityRenderer.orbit(dx, dy)
                } else if (event.pointerCount > 1) {
                    scaling = true
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                scaling = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged && !scaling && !scaleDetector.isInProgress) {
                    cityRenderer.pick(event.x, event.y, width, height, densityScale)
                    performClick()
                }
                scaling = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                scaling = false
                dragged = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
    }
}

private data class CityNode(
    val id: Int,
    val x: Float,
    val z: Float,
    val height: Float,
    val color: FloatArray
)

private data class GlMesh(val buffer: FloatBuffer, val vertexCount: Int)

private data class CityCitizen(
    val id: Int,
    var angle: Float,
    var targetAngle: Float,
    val radius: Float,
    var direction: Float,
    val speed: Float,
    var pauseRemaining: Float,
    var gaitPhase: Float,
    val outfit: FloatArray,
    val skin: FloatArray,
    val accent: FloatArray,
    val hair: FloatArray,
    val trousers: FloatArray,
    val footwear: FloatArray
)

private class OceanCityRenderer(
    private val onBuildingSelected: (Int) -> Unit
) : GLSurfaceView.Renderer {

    private var program = 0
    private var particleProgram = 0
    private lateinit var cube: GlMesh
    private lateinit var cylinder: GlMesh
    private lateinit var sphere: GlMesh
    private lateinit var torus: GlMesh
    private lateinit var floor: GlMesh
    private lateinit var personSphere: GlMesh
    private lateinit var personCylinder: GlMesh
    private lateinit var particles: FloatBuffer

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val model = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)
    private val pickVector = FloatArray(4)
    private val projected = FloatArray(4)

    private var screenWidth = 1
    private var screenHeight = 1
    private var uMvpLocation = -1
    private var uModelLocation = -1
    private var uColorLocation = -1
    private var uGlowLocation = -1
    private var uEyeLocation = -1
    private var uTimeLocation = -1
    private var aPositionLocation = -1
    private var aNormalLocation = -1
    private var particleMvpLocation = -1
    private var particleTimeLocation = -1
    private var particlePositionLocation = -1
    private var lastFrameNanos = 0L
    private val citizenRandom = Random(4219L)
    private val citizens = List(9) { index ->
        val angle = (index.toFloat() / 9f) * (2f * PI.toFloat())
        val direction = if (index % 2 == 0) 1f else -1f
        CityCitizen(
            id = index,
            angle = angle,
            targetAngle = angle + direction * (0.55f + citizenRandom.nextFloat() * 0.85f),
            radius = when (index % 3) {
                0 -> 3.35f
                1 -> 4.25f
                else -> 5.0f
            },
            direction = direction,
            speed = 0.48f + citizenRandom.nextFloat() * 0.24f,
            pauseRemaining = citizenRandom.nextFloat() * 1.15f,
            gaitPhase = citizenRandom.nextFloat() * 6.28f,
            outfit = arrayOf(
                floatArrayOf(0.08f, 0.55f, 0.62f, 1f),
                floatArrayOf(0.20f, 0.34f, 0.72f, 1f),
                floatArrayOf(0.52f, 0.25f, 0.62f, 1f),
                floatArrayOf(0.16f, 0.52f, 0.34f, 1f),
                floatArrayOf(0.70f, 0.36f, 0.16f, 1f),
                floatArrayOf(0.24f, 0.48f, 0.58f, 1f)
            )[index % 6],
            skin = arrayOf(
                floatArrayOf(0.78f, 0.55f, 0.39f, 1f),
                floatArrayOf(0.54f, 0.34f, 0.23f, 1f),
                floatArrayOf(0.90f, 0.70f, 0.53f, 1f)
            )[index % 3],
            accent = arrayOf(
                floatArrayOf(0.18f, 0.95f, 0.88f, 1f),
                floatArrayOf(0.38f, 0.68f, 1f, 1f),
                floatArrayOf(0.95f, 0.66f, 0.30f, 1f)
            )[index % 3],
            hair = arrayOf(
                floatArrayOf(0.055f, 0.035f, 0.028f, 1f),
                floatArrayOf(0.16f, 0.095f, 0.045f, 1f),
                floatArrayOf(0.035f, 0.045f, 0.065f, 1f)
            )[index % 3],
            trousers = arrayOf(
                floatArrayOf(0.045f, 0.12f, 0.18f, 1f),
                floatArrayOf(0.11f, 0.15f, 0.22f, 1f),
                floatArrayOf(0.16f, 0.18f, 0.22f, 1f)
            )[index % 3],
            footwear = arrayOf(
                floatArrayOf(0.025f, 0.035f, 0.052f, 1f),
                floatArrayOf(0.12f, 0.085f, 0.055f, 1f),
                floatArrayOf(0.055f, 0.075f, 0.09f, 1f)
            )[index % 3]
        )
    }
    @Volatile private var yaw = -0.38f
    @Volatile private var pitch = 0.30f
    @Volatile private var cameraDistance = 20.5f
    @Volatile private var selectedId = -1
    private var startTime = System.nanoTime()
    private var frameTime = 0f
    private val eye = FloatArray(3)

    private val nodes = listOf(
        CityNode(1, 6.4f, 0.0f, 1.65f, rgb(0.22f, 0.92f, 0.82f)),
        CityNode(2, 5.55f, 3.2f, 2.0f, rgb(0.46f, 0.64f, 1.0f)),
        CityNode(3, 3.25f, 5.55f, 1.95f, rgb(0.25f, 0.76f, 1.0f)),
        CityNode(4, 0.0f, 6.55f, 2.45f, rgb(0.70f, 0.43f, 1.0f)),
        CityNode(5, -3.25f, 5.55f, 1.75f, rgb(0.42f, 0.88f, 0.52f)),
        CityNode(6, -5.55f, 3.2f, 2.10f, rgb(0.25f, 0.83f, 0.91f)),
        CityNode(7, -6.4f, 0.0f, 2.35f, rgb(1.0f, 0.68f, 0.34f)),
        CityNode(8, -5.55f, -3.2f, 2.05f, rgb(0.38f, 0.65f, 1.0f)),
        CityNode(9, -3.25f, -5.55f, 1.95f, rgb(1.0f, 0.40f, 0.54f)),
        CityNode(10, 0.0f, -6.55f, 2.20f, rgb(0.84f, 0.70f, 0.43f)),
        CityNode(11, 3.25f, -5.55f, 2.50f, rgb(0.43f, 0.72f, 1.0f)),
        CityNode(12, 5.55f, -3.2f, 1.80f, rgb(0.70f, 0.60f, 1.0f))
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.006f, 0.035f, 0.070f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        particleProgram = linkProgram(PARTICLE_VERTEX_SHADER, PARTICLE_FRAGMENT_SHADER)
        uMvpLocation = GLES20.glGetUniformLocation(program, "uMvp")
        uModelLocation = GLES20.glGetUniformLocation(program, "uModel")
        uColorLocation = GLES20.glGetUniformLocation(program, "uColor")
        uGlowLocation = GLES20.glGetUniformLocation(program, "uGlow")
        uEyeLocation = GLES20.glGetUniformLocation(program, "uEye")
        uTimeLocation = GLES20.glGetUniformLocation(program, "uTime")
        aPositionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        aNormalLocation = GLES20.glGetAttribLocation(program, "aNormal")
        particleMvpLocation = GLES20.glGetUniformLocation(particleProgram, "uMvp")
        particleTimeLocation = GLES20.glGetUniformLocation(particleProgram, "uTime")
        particlePositionLocation = GLES20.glGetAttribLocation(particleProgram, "aPosition")
        cube = createCube()
        cylinder = createCylinder(28)
        sphere = createSphere(18, 24)
        personSphere = createSphere(8, 12)
        personCylinder = createCylinder(10)
        torus = createTorus(1.0f, 0.055f, 32, 8)
        floor = createCube()
        particles = createParticles(94)
        startTime = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = max(1, width)
        screenHeight = max(1, height)
        GLES20.glViewport(0, 0, screenWidth, screenHeight)
        val ratio = screenWidth.toFloat() / screenHeight.toFloat()
        Matrix.perspectiveM(projection, 0, if (ratio < 0.72f) 50f else 43f, ratio, 0.1f, 90f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        if (lastFrameNanos != 0L && now - lastFrameNanos < 33_000_000L) return
        val deltaSeconds = if (lastFrameNanos == 0L) {
            1f / 30f
        } else {
            ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        }
        lastFrameNanos = now
        frameTime = (now - startTime) / 1_000_000_000f
        updateCitizens(deltaSeconds)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val cx = sin(yaw) * cameraDistance
        val cz = cos(yaw) * cameraDistance
        val cy = 1.5f + sin(pitch) * cameraDistance
        eye[0] = cx
        eye[1] = cy
        eye[2] = cz
        Matrix.setLookAtM(view, 0, cx, cy, cz, 0f, 1.05f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uTimeLocation, frameTime)
        GLES20.glUniform3f(uEyeLocation, eye[0], eye[1], eye[2])

        drawCityFloor()
        drawCityRings()
        drawCentralCitadel()
        drawDistricts()
        drawMovingStructures()
        drawCitizens()
        drawMaintenanceRobots()
        drawSubmersibles()
        drawCoralGardens()
        drawParticles()

        // Keep the city continuously alive with a slow, subtle camera drift only while idle.
    }

    fun orbit(dx: Float, dy: Float) {
        yaw -= dx * 0.0044f
        pitch = (pitch + dy * 0.0032f).coerceIn(0.10f, 0.92f)
    }

    fun zoom(factor: Float) {
        if (factor > 0f) cameraDistance = (cameraDistance / factor).coerceIn(12.0f, 31.0f)
    }

    fun pick(x: Float, y: Float, width: Int, height: Int, density: Float) {
        if (width <= 0 || height <= 0) return
        val hitRadius = max(42f * density, width * 0.075f)
        var nearestId = -1
        var nearestDistance = Float.MAX_VALUE

        // The command citadel is the central point of the city.
        val candidates = ArrayList<Triple<Int, Float, Float>>(nodes.size + 1)
        candidates.add(Triple(0, 0f, 0f))
        nodes.forEach { candidates.add(Triple(it.id, it.x, it.z)) }

        for ((id, worldX, worldZ) in candidates) {
            val worldY = if (id == 0) 2.15f else nodes.first { it.id == id }.height + 0.45f
            pickVector[0] = worldX
            pickVector[1] = worldY
            pickVector[2] = worldZ
            pickVector[3] = 1f
            Matrix.multiplyMV(projected, 0, viewProjection, 0, pickVector, 0)
            if (projected[3] <= 0.1f) continue
            val ndcX = projected[0] / projected[3]
            val ndcY = projected[1] / projected[3]
            val px = (ndcX * 0.5f + 0.5f) * width
            val py = (1f - (ndcY * 0.5f + 0.5f)) * height
            val distance = kotlin.math.hypot(px - x, py - y)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestId = id
            }
        }

        if (nearestId >= 0 && nearestDistance <= hitRadius) {
            selectedId = nearestId
            onBuildingSelected(nearestId)
        }
    }

    private fun drawCityFloor() {
        draw(floor, 0f, -0.38f, 0f, 22f, 0.72f, 22f, rgb(0.025f, 0.15f, 0.19f), rgb(0.0f, 0.10f, 0.13f))
        draw(cylinder, 0f, -0.08f, 0f, 9.2f, 0.20f, 9.2f, rgb(0.025f, 0.18f, 0.22f), rgb(0.02f, 0.18f, 0.20f))
        draw(torus, 0f, 0.04f, 0f, 8.9f, 1f, 8.9f, rgb(0.05f, 0.36f, 0.40f), rgb(0.08f, 0.95f, 0.88f))
        draw(torus, 0f, 0.06f, 0f, 8.3f, 1f, 8.3f, rgb(0.04f, 0.22f, 0.31f), rgb(0.05f, 0.45f, 0.70f))
        draw(cube, 0f, -0.01f, 0f, 15.8f, 0.035f, 0.12f, rgb(0.025f, 0.25f, 0.29f), rgb(0.02f, 0.45f, 0.48f))
        draw(cube, 0f, 0.0f, 0f, 0.12f, 0.035f, 15.8f, rgb(0.025f, 0.25f, 0.29f), rgb(0.02f, 0.45f, 0.48f))
    }

    private fun drawCityRings() {
        draw(torus, 0f, 0.23f, 0f, 4.9f, 1f, 4.9f, rgb(0.04f, 0.24f, 0.33f), rgb(0.02f, 0.46f, 0.65f))
        draw(torus, 0f, 0.25f, 0f, 3.7f, 1f, 3.7f, rgb(0.04f, 0.28f, 0.34f), rgb(0.04f, 0.72f, 0.75f))
        draw(torus, 0f, 0.28f, 0f, 2.5f, 1f, 2.5f, rgb(0.07f, 0.35f, 0.39f), rgb(0.10f, 0.75f, 0.72f))
    }

    private fun drawCentralCitadel() {
        draw(cylinder, 0f, 0.30f, 0f, 2.05f, 0.52f, 2.05f, rgb(0.06f, 0.27f, 0.34f), rgb(0.02f, 0.34f, 0.42f))
        draw(cylinder, 0f, 1.20f, 0f, 1.35f, 1.55f, 1.35f, rgb(0.12f, 0.34f, 0.43f), rgb(0.02f, 0.34f, 0.48f))
        draw(cylinder, 0f, 2.40f, 0f, 0.95f, 1.05f, 0.95f, rgb(0.16f, 0.42f, 0.50f), rgb(0.04f, 0.52f, 0.61f))
        draw(sphere, 0f, 3.25f, 0f, 0.98f, 0.78f, 0.98f, rgb(0.20f, 0.63f, 0.68f), rgb(0.12f, 0.90f, 0.86f))
        draw(cylinder, 0f, 4.12f, 0f, 0.16f, 1.1f, 0.16f, rgb(0.28f, 0.76f, 0.77f), rgb(0.24f, 0.98f, 0.90f))
        draw(sphere, 0f, 4.72f, 0f, 0.26f, 0.26f, 0.26f, rgb(0.60f, 0.94f, 0.87f), rgb(0.45f, 1f, 0.92f))
        draw(torus, 0f, 1.10f, 0f, 1.48f, 1f, 1.48f, rgb(0.06f, 0.27f, 0.32f), rgb(0.10f, 0.93f, 0.85f))
        draw(torus, 0f, 2.45f, 0f, 1.08f, 1f, 1.08f, rgb(0.08f, 0.28f, 0.39f), rgb(0.16f, 0.78f, 0.98f))
        draw(torus, 0f, 3.13f, 0f, 1.05f, 1f, 1.05f, rgb(0.12f, 0.43f, 0.49f), rgb(0.16f, 0.95f, 0.88f))
    }

    private fun drawDistricts() {
        for (node in nodes) {
            val active = node.id == selectedId
            val glowBoost = if (active) 1.65f else 1f
            val stone = rgb(0.075f, 0.22f, 0.30f)
            val trim = scaleColor(node.color, if (active) 1.45f else 0.82f)

            // Structural plinth, outer ring and a layered building silhouette.
            draw(cylinder, node.x, 0.12f, node.z, 1.08f, 0.22f, 1.08f, stone, scaleColor(node.color, 0.62f * glowBoost))
            draw(torus, node.x, 0.25f, node.z, if (active) 1.08f else 0.94f, 1f, if (active) 1.08f else 0.94f,
                rgb(0.035f, 0.16f, 0.23f), trim)
            draw(cylinder, node.x, 0.34f, node.z, 0.78f, 0.18f, 0.78f,
                rgb(0.08f, 0.24f, 0.31f), scaleColor(node.color, 0.34f * glowBoost))

            val h = node.height
            if (node.id % 3 == 0 || node.id == 5 || node.id == 9) {
                draw(cylinder, node.x, 0.46f + h * 0.46f, node.z, 0.64f, h, 0.64f,
                    rgb(0.10f, 0.29f, 0.38f), scaleColor(node.color, 0.25f * glowBoost))
                draw(cylinder, node.x, 0.86f + h, node.z, 0.72f, 0.10f, 0.72f,
                    rgb(0.10f, 0.28f, 0.36f), scaleColor(node.color, 0.42f * glowBoost))
                draw(torus, node.x, 0.86f + h, node.z, 0.70f, 1f, 0.70f,
                    rgb(0.05f, 0.18f, 0.25f), trim)
            } else {
                draw(cube, node.x, 0.43f + h * 0.46f, node.z, 1.0f, h, 0.84f,
                    rgb(0.085f, 0.25f, 0.36f), scaleColor(node.color, 0.24f * glowBoost))
                draw(cube, node.x, 0.58f + h, node.z, 1.10f, 0.11f, 0.94f,
                    rgb(0.12f, 0.31f, 0.40f), trim)
            }

            // Two illuminated facade bands give each tower depth at a distance.
            draw(torus, node.x, 0.78f, node.z, 0.70f, 1f, 0.70f,
                rgb(0.08f, 0.22f, 0.32f), scaleColor(node.color, 1.05f * glowBoost))
            draw(torus, node.x, 1.36f, node.z, 0.70f, 1f, 0.70f,
                rgb(0.08f, 0.22f, 0.32f), scaleColor(node.color, 0.92f * glowBoost))
            draw(sphere, node.x, 0.92f + h, node.z, 0.39f, 0.30f, 0.39f,
                rgb(0.16f, 0.42f, 0.52f), scaleColor(node.color, 1.08f * glowBoost))
            draw(cylinder, node.x, 1.20f + h, node.z, 0.09f, 0.62f, 0.09f,
                rgb(0.22f, 0.56f, 0.61f), scaleColor(node.color, 1.2f * glowBoost))

            // Recessed-looking illuminated windows and entrance columns.
            for (level in 0..2) {
                val windowY = 0.63f + level * 0.42f + h * 0.32f
                draw(cube, node.x + 0.24f, windowY, node.z + 0.425f,
                    0.16f, 0.21f, 0.025f, rgb(0.035f, 0.16f, 0.22f), scaleColor(node.color, 1.65f * glowBoost))
                draw(cube, node.x - 0.24f, windowY, node.z + 0.425f,
                    0.16f, 0.21f, 0.025f, rgb(0.035f, 0.16f, 0.22f), scaleColor(node.color, 1.35f * glowBoost))
            }
            draw(personCylinder, node.x - 0.45f, 0.46f, node.z + 0.46f,
                0.045f, 0.48f, 0.045f, rgb(0.16f, 0.34f, 0.42f), trim)
            draw(personCylinder, node.x + 0.45f, 0.46f, node.z + 0.46f,
                0.045f, 0.48f, 0.045f, rgb(0.16f, 0.34f, 0.42f), trim)

            // Floating crown and antenna.
            draw(torus, node.x, 1.15f + h, node.z, 0.92f, 1f, 0.92f,
                rgb(0.08f, 0.25f, 0.36f), scaleColor(node.color, 1.35f * glowBoost))
            if (node.id == 4 || node.id == 11) {
                draw(torus, node.x, 1.48f + h, node.z, 0.98f, 1f, 0.98f,
                    rgb(0.08f, 0.25f, 0.36f), scaleColor(node.color, 1.55f * glowBoost))
            }
        }
    }

    private fun drawMovingStructures() {
        val orbit = frameTime * 0.20f
        val x1 = cos(orbit) * 4.35f
        val z1 = sin(orbit) * 4.35f
        val x2 = cos(orbit + PI.toFloat()) * 4.35f
        val z2 = sin(orbit + PI.toFloat()) * 4.35f
        draw(sphere, x1, 2.9f + sin(frameTime * 0.8f) * 0.16f, z1, 0.16f, 0.16f, 0.16f,
            rgb(0.28f, 0.79f, 0.86f), rgb(0.15f, 0.95f, 1f))
        draw(sphere, x2, 2.0f + cos(frameTime * 0.7f) * 0.12f, z2, 0.12f, 0.12f, 0.12f,
            rgb(0.54f, 0.66f, 1f), rgb(0.28f, 0.45f, 1f))
    }

    private fun updateCitizens(deltaSeconds: Float) {
        for (citizen in citizens) {
            if (citizen.pauseRemaining > 0f) {
                citizen.pauseRemaining -= deltaSeconds
                if (citizen.pauseRemaining <= 0f) {
                    citizen.targetAngle = citizen.angle + citizen.direction *
                        (0.55f + citizenRandom.nextFloat() * 1.25f)
                }
            } else {
                val remaining = citizen.targetAngle - citizen.angle
                val step = (citizen.speed / citizen.radius) * deltaSeconds
                if (kotlin.math.abs(remaining) <= step) {
                    citizen.angle = citizen.targetAngle
                    citizen.pauseRemaining = 0.55f + citizenRandom.nextFloat() * 1.65f
                    if (citizenRandom.nextFloat() < 0.20f) citizen.direction *= -1f
                    citizen.targetAngle = citizen.angle + citizen.direction *
                        (0.55f + citizenRandom.nextFloat() * 1.25f)
                } else {
                    citizen.angle += if (remaining > 0f) step else -step
                }
            }
            citizen.gaitPhase += deltaSeconds * (6.0f + citizen.speed * 2.5f)
        }
    }

    private fun drawCitizens() {
        for (citizen in citizens) {
            val x = cos(citizen.angle) * citizen.radius
            val z = sin(citizen.angle) * citizen.radius
            val walking = citizen.pauseRemaining <= 0f
            val phase = citizen.gaitPhase
            val bob = if (walking) abs(sin(phase * 2f)) * 0.025f
                else sin(frameTime * 1.1f + citizen.id) * 0.008f
            val armSwing = if (walking) sin(phase) * 25f
                else sin(frameTime * 1.25f + citizen.id) * 4f
            val legSwing = if (walking) sin(phase) * 27f else 0f
            val kneeBend = if (walking) max(0f, sin(phase + 0.8f)) * 18f else 0f
            val facing = (-citizen.angle + if (citizen.direction < 0f) PI.toFloat() else 0f) *
                (180f / PI.toFloat())
            val look = if (walking) 0f else sin(frameTime * 0.7f + citizen.id * 1.7f) * 9f
            val base = 0.15f

            // Torso: layered clothing, collar, belt and shoulder silhouette.
            drawCitizenPart(personSphere, x, z, facing, 0f, base + 0.49f + bob, 0f,
                0.175f, 0.245f, 0.112f, citizen.outfit, citizen.accent)
            drawCitizenPart(personSphere, x, z, facing, 0f, base + 0.30f + bob, 0f,
                0.145f, 0.105f, 0.10f, citizen.trousers, citizen.accent)
            drawCitizenPart(personCylinder, x, z, facing, 0f, base + 0.67f + bob, 0f,
                0.052f, 0.10f, 0.052f, citizen.skin, citizen.skin)
            drawCitizenPart(personSphere, x, z, facing, -0.145f, base + 0.60f + bob, 0f,
                0.075f, 0.085f, 0.09f, citizen.outfit, citizen.accent)
            drawCitizenPart(personSphere, x, z, facing, 0.145f, base + 0.60f + bob, 0f,
                0.075f, 0.085f, 0.09f, citizen.outfit, citizen.accent)

            // Head, hair cap, ears and a readable face profile.
            drawCitizenPart(personSphere, x, z, facing, 0f, base + 0.79f + bob, 0f,
                0.105f, 0.128f, 0.102f, citizen.skin, citizen.accent, look)
            drawCitizenPart(personSphere, x, z, facing, 0f, base + 0.888f + bob, -0.012f,
                0.108f, 0.052f, 0.108f, citizen.hair, citizen.hair, look)
            drawCitizenPart(personSphere, x, z, facing, -0.105f, base + 0.79f + bob, 0f,
                0.025f, 0.035f, 0.025f, citizen.skin, citizen.skin)
            drawCitizenPart(personSphere, x, z, facing, 0.105f, base + 0.79f + bob, 0f,
                0.025f, 0.035f, 0.025f, citizen.skin, citizen.skin)
            drawCitizenPart(personSphere, x, z, facing, -0.038f, base + 0.80f + bob, 0.091f,
                0.014f, 0.012f, 0.012f, rgb(0.018f, 0.035f, 0.045f), citizen.accent)
            drawCitizenPart(personSphere, x, z, facing, 0.038f, base + 0.80f + bob, 0.091f,
                0.014f, 0.012f, 0.012f, rgb(0.018f, 0.035f, 0.045f), citizen.accent)
            drawCitizenPart(personSphere, x, z, facing, 0f, base + 0.765f + bob, 0.108f,
                0.018f, 0.022f, 0.018f, citizen.skin, citizen.skin)
            drawCitizenPart(personSphere, x, z, facing, 0f, base + 0.732f + bob, 0.098f,
                0.025f, 0.008f, 0.01f, rgb(0.28f, 0.075f, 0.075f), citizen.accent)

            // Two-piece moving arms with elbows and hands.
            drawCitizenPart(personCylinder, x, z, facing, -0.205f, base + 0.485f + bob, 0f,
                0.052f, 0.18f, 0.052f, citizen.outfit, citizen.accent, 0f, armSwing * 0.58f)
            drawCitizenPart(personSphere, x, z, facing, -0.205f, base + 0.385f + bob, 0f,
                0.052f, 0.052f, 0.052f, citizen.outfit, citizen.accent)
            drawCitizenPart(personCylinder, x, z, facing, -0.205f, base + 0.275f + bob, 0f,
                0.043f, 0.15f, 0.043f, citizen.skin, citizen.accent, 0f, armSwing * 0.82f)
            drawCitizenPart(personSphere, x, z, facing, -0.205f, base + 0.185f + bob, 0.012f,
                0.043f, 0.05f, 0.04f, citizen.skin, citizen.skin)

            drawCitizenPart(personCylinder, x, z, facing, 0.205f, base + 0.485f + bob, 0f,
                0.052f, 0.18f, 0.052f, citizen.outfit, citizen.accent, 0f, -armSwing * 0.58f)
            drawCitizenPart(personSphere, x, z, facing, 0.205f, base + 0.385f + bob, 0f,
                0.052f, 0.052f, 0.052f, citizen.outfit, citizen.accent)
            drawCitizenPart(personCylinder, x, z, facing, 0.205f, base + 0.275f + bob, 0f,
                0.043f, 0.15f, 0.043f, citizen.skin, citizen.accent, 0f, -armSwing * 0.82f)
            drawCitizenPart(personSphere, x, z, facing, 0.205f, base + 0.185f + bob, 0.012f,
                0.043f, 0.05f, 0.04f, citizen.skin, citizen.skin)

            // Articulated legs and footwear. Opposite phase produces a natural walk rhythm.
            drawCitizenPart(personCylinder, x, z, facing, -0.075f, base + 0.155f, 0f,
                0.064f, 0.18f, 0.064f, citizen.trousers, citizen.accent, 0f, -legSwing * 0.72f)
            drawCitizenPart(personSphere, x, z, facing, -0.075f, base + 0.055f, 0.045f,
                0.062f, 0.062f, 0.062f, citizen.trousers, citizen.accent)
            drawCitizenPart(personCylinder, x, z, facing, -0.075f, base - 0.055f, 0.025f,
                0.048f, 0.17f, 0.048f, citizen.trousers, citizen.accent, 0f, kneeBend - legSwing * 0.38f)
            drawCitizenPart(cube, x, z, facing, -0.075f, base - 0.145f, 0.075f,
                0.105f, 0.065f, 0.17f, citizen.footwear, citizen.accent, 0f, -legSwing * 0.72f)

            drawCitizenPart(personCylinder, x, z, facing, 0.075f, base + 0.155f, 0f,
                0.064f, 0.18f, 0.064f, citizen.trousers, citizen.accent, 0f, legSwing * 0.72f)
            drawCitizenPart(personSphere, x, z, facing, 0.075f, base + 0.055f, 0.045f,
                0.062f, 0.062f, 0.062f, citizen.trousers, citizen.accent)
            drawCitizenPart(personCylinder, x, z, facing, 0.075f, base - 0.055f, 0.025f,
                0.048f, 0.17f, 0.048f, citizen.trousers, citizen.accent, 0f, -kneeBend + legSwing * 0.38f)
            drawCitizenPart(cube, x, z, facing, 0.075f, base - 0.145f, 0.075f,
                0.105f, 0.065f, 0.17f, citizen.footwear, citizen.accent, 0f, legSwing * 0.72f)

            // Small badge gives citizens a visible identity marker without a UI label.
            draw(cube, x, base + 0.49f + bob, z + 0.116f, 0.045f, 0.055f, 0.018f,
                citizen.accent, citizen.accent, rotationY = facing)
        }
    }

    private fun drawCitizenPart(
        mesh: GlMesh,
        rootX: Float,
        rootZ: Float,
        facingDegrees: Float,
        localX: Float,
        y: Float,
        localZ: Float,
        sx: Float,
        sy: Float,
        sz: Float,
        color: FloatArray,
        glow: FloatArray,
        yawOffset: Float = 0f,
        rotationZ: Float = 0f
    ) {
        val radians = facingDegrees * (PI.toFloat() / 180f)
        val worldX = rootX + localX * cos(radians) + localZ * sin(radians)
        val worldZ = rootZ - localX * sin(radians) + localZ * cos(radians)
        draw(mesh, worldX, y, worldZ, sx, sy, sz, color, glow,
            rotationY = facingDegrees + yawOffset, rotationZ = rotationZ)
    }

    private fun drawMaintenanceRobots() {
        for (index in 0..1) {
            val orbit = frameTime * (if (index == 0) 0.30f else -0.22f) + index * PI.toFloat()
            val x = cos(orbit) * 6.7f
            val z = sin(orbit) * 6.7f
            val y = 0.38f + sin(frameTime * 1.6f + index) * 0.08f
            val yaw = -orbit * 180f / PI.toFloat() + if (index == 0) 90f else -90f
            val shell = if (index == 0) rgb(0.20f, 0.48f, 0.56f) else rgb(0.36f, 0.34f, 0.25f)
            val lamp = if (index == 0) rgb(0.10f, 0.98f, 0.86f) else rgb(1f, 0.66f, 0.24f)
            draw(personCylinder, x, y + 0.20f, z, 0.20f, 0.28f, 0.18f, shell, lamp, rotationY = yaw)
            draw(personSphere, x, y + 0.43f, z, 0.15f, 0.13f, 0.15f, shell, lamp, rotationY = yaw)
            draw(personSphere, x, y + 0.44f, z + 0.125f, 0.045f, 0.035f, 0.025f, lamp, lamp, rotationY = yaw)
            draw(personCylinder, x - 0.22f, y + 0.19f, z, 0.045f, 0.22f, 0.045f, shell, lamp, rotationY = yaw, rotationZ = 22f)
            draw(personCylinder, x + 0.22f, y + 0.19f, z, 0.045f, 0.22f, 0.045f, shell, lamp, rotationY = yaw, rotationZ = -22f)
            draw(torus, x, y + 0.04f, z, 0.27f, 1f, 0.27f, rgb(0.05f, 0.18f, 0.23f), lamp)
        }
    }

    private fun drawSubmersibles() {
        for (index in 0..2) {
            val speed = 0.14f + index * 0.025f
            val orbit = frameTime * speed + index * (2f * PI.toFloat() / 3f)
            val radius = 8.2f + index * 0.28f
            val x = cos(orbit) * radius
            val z = sin(orbit) * radius
            val y = 1.35f + sin(frameTime * 0.8f + index) * 0.42f
            val yaw = -orbit * 180f / PI.toFloat() + 90f
            val hull = if (index == 1) rgb(0.55f, 0.37f, 0.18f) else rgb(0.11f, 0.30f, 0.39f)
            val glow = if (index == 1) rgb(1f, 0.68f, 0.28f) else rgb(0.12f, 0.86f, 1f)
            draw(personCylinder, x, y, z, 0.30f, 0.72f, 0.30f, hull, glow, yaw, 90f)
            draw(personSphere, x, y, z + 0.34f, 0.19f, 0.16f, 0.19f, hull, glow, rotationY = yaw)
            draw(personSphere, x, y, z - 0.34f, 0.16f, 0.14f, 0.16f, hull, glow, rotationY = yaw)
            draw(personSphere, x, y + 0.02f, z + 0.10f, 0.12f, 0.10f, 0.12f,
                rgb(0.08f, 0.22f, 0.28f), glow, rotationY = yaw)
            draw(torus, x, y, z, 0.35f, 1f, 0.35f, rgb(0.035f, 0.16f, 0.22f), glow)
            draw(personCylinder, x, y - 0.22f, z - 0.50f, 0.035f, 0.25f, 0.035f,
                rgb(0.12f, 0.27f, 0.32f), glow, rotationY = yaw, rotationZ = 90f)
        }
    }

    private fun drawCoralGardens() {
        val gardenPoints = arrayOf(
            floatArrayOf(-8.4f, -7.2f), floatArrayOf(8.1f, -7.8f),
            floatArrayOf(-8.6f, 7.4f), floatArrayOf(8.5f, 7.1f),
            floatArrayOf(0f, 9.0f)
        )
        for (index in gardenPoints.indices) {
            val x = gardenPoints[index][0]
            val z = gardenPoints[index][1]
            val sway = sin(frameTime * 1.15f + index) * 7f
            val coral = when (index % 3) {
                0 -> rgb(0.12f, 0.52f, 0.48f)
                1 -> rgb(0.42f, 0.30f, 0.57f)
                else -> rgb(0.63f, 0.39f, 0.26f)
            }
            for (branch in 0..2) {
                val side = (branch - 1) * 0.17f
                val height = 0.50f + branch * 0.12f
                draw(personCylinder, x + side, height * 0.5f, z, 0.045f, height, 0.045f,
                    coral, scaleColor(coral, 1.45f), rotationZ = sway + side * 24f)
                draw(personSphere, x + side * 1.5f, height + 0.12f, z,
                    0.12f, 0.14f, 0.12f, scaleColor(coral, 1.18f), scaleColor(coral, 1.65f))
            }
        }
    }

    private fun drawParticles() {
        GLES20.glUseProgram(particleProgram)
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
        GLES20.glUniformMatrix4fv(particleMvpLocation, 1, false, mvp, 0)
        GLES20.glUniform1f(particleTimeLocation, frameTime)
        particles.position(0)
        val posLoc = particlePositionLocation
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 3 * 4, particles)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, particles.limit() / 3)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(posLoc)
    }

    private fun draw(
        mesh: GlMesh,
        x: Float,
        y: Float,
        z: Float,
        sx: Float,
        sy: Float,
        sz: Float,
        color: FloatArray,
        glow: FloatArray,
        rotationY: Float = 0f,
        rotationZ: Float = 0f
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        if (rotationY != 0f) Matrix.rotateM(model, 0, rotationY, 0f, 1f, 0f)
        if (rotationZ != 0f) Matrix.rotateM(model, 0, rotationZ, 0f, 0f, 1f)
        Matrix.scaleM(model, 0, sx, sy, sz)
        Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        GLES20.glUniformMatrix4fv(uMvpLocation, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModelLocation, 1, false, model, 0)
        GLES20.glUniform4fv(uColorLocation, 1, color, 0)
        GLES20.glUniform4fv(uGlowLocation, 1, glow, 0)

        mesh.buffer.position(0)
        val p = aPositionLocation
        val n = aNormalLocation
        GLES20.glEnableVertexAttribArray(p)
        GLES20.glEnableVertexAttribArray(n)
        GLES20.glVertexAttribPointer(p, 3, GLES20.GL_FLOAT, false, 6 * 4, mesh.buffer)
        mesh.buffer.position(3)
        GLES20.glVertexAttribPointer(n, 3, GLES20.GL_FLOAT, false, 6 * 4, mesh.buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
        GLES20.glDisableVertexAttribArray(p)
        GLES20.glDisableVertexAttribArray(n)
    }

    private fun createParticles(count: Int): FloatBuffer {
        val random = Random(9031L)
        val values = FloatArray(count * 3)
        for (i in 0 until count) {
            values[i * 3] = (random.nextFloat() - 0.5f) * 19f
            values[i * 3 + 1] = random.nextFloat() * 7f
            values[i * 3 + 2] = (random.nextFloat() - 0.5f) * 19f
        }
        return ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }
    }

    private fun createCube(): GlMesh {
        val data = ArrayList<Float>(36 * 6)
        val faces = arrayOf(
            floatArrayOf(0f, 0f, 1f, -0.5f,-0.5f,0.5f, 0.5f,-0.5f,0.5f, 0.5f,0.5f,0.5f, -0.5f,0.5f,0.5f),
            floatArrayOf(0f, 0f,-1f, 0.5f,-0.5f,-0.5f, -0.5f,-0.5f,-0.5f, -0.5f,0.5f,-0.5f, 0.5f,0.5f,-0.5f),
            floatArrayOf(1f,0f,0f, 0.5f,-0.5f,0.5f, 0.5f,-0.5f,-0.5f, 0.5f,0.5f,-0.5f, 0.5f,0.5f,0.5f),
            floatArrayOf(-1f,0f,0f, -0.5f,-0.5f,-0.5f, -0.5f,-0.5f,0.5f, -0.5f,0.5f,0.5f, -0.5f,0.5f,-0.5f),
            floatArrayOf(0f,1f,0f, -0.5f,0.5f,0.5f, 0.5f,0.5f,0.5f, 0.5f,0.5f,-0.5f, -0.5f,0.5f,-0.5f),
            floatArrayOf(0f,-1f,0f, -0.5f,-0.5f,-0.5f, 0.5f,-0.5f,-0.5f, 0.5f,-0.5f,0.5f, -0.5f,-0.5f,0.5f)
        )
        val tri = intArrayOf(0,1,2,0,2,3)
        for (face in faces) {
            for (index in tri) {
                data.add(face[0]); data.add(face[1]); data.add(face[2])
                val offset = 3 + index * 3
                data.add(face[offset]); data.add(face[offset + 1]); data.add(face[offset + 2])
            }
        }
        return mesh(data)
    }

    private fun createCylinder(segments: Int): GlMesh {
        val data = ArrayList<Float>(segments * 36)
        for (i in 0 until segments) {
            val a0 = 2f * PI.toFloat() * i / segments
            val a1 = 2f * PI.toFloat() * (i + 1) / segments
            val x0 = cos(a0); val z0 = sin(a0)
            val x1 = cos(a1); val z1 = sin(a1)
            vertex(data, x0, -0.5f, z0, x0, 0f, z0)
            vertex(data, x1, -0.5f, z1, x1, 0f, z1)
            vertex(data, x1, 0.5f, z1, x1, 0f, z1)
            vertex(data, x0, -0.5f, z0, x0, 0f, z0)
            vertex(data, x1, 0.5f, z1, x1, 0f, z1)
            vertex(data, x0, 0.5f, z0, x0, 0f, z0)
            vertex(data, 0f, 0.5f, 0f, 0f, 1f, 0f)
            vertex(data, x0, 0.5f, z0, 0f, 1f, 0f)
            vertex(data, x1, 0.5f, z1, 0f, 1f, 0f)
            vertex(data, 0f, -0.5f, 0f, 0f, -1f, 0f)
            vertex(data, x1, -0.5f, z1, 0f, -1f, 0f)
            vertex(data, x0, -0.5f, z0, 0f, -1f, 0f)
        }
        return mesh(data)
    }

    private fun createSphere(stacks: Int, slices: Int): GlMesh {
        val data = ArrayList<Float>(stacks * slices * 36)
        for (i in 0 until stacks) {
            val lat0 = -PI.toFloat() / 2f + PI.toFloat() * i / stacks
            val lat1 = -PI.toFloat() / 2f + PI.toFloat() * (i + 1) / stacks
            for (j in 0 until slices) {
                val lon0 = 2f * PI.toFloat() * j / slices
                val lon1 = 2f * PI.toFloat() * (j + 1) / slices
                val p0 = spherePoint(lat0, lon0)
                val p1 = spherePoint(lat1, lon0)
                val p2 = spherePoint(lat1, lon1)
                val p3 = spherePoint(lat0, lon1)
                vertex(data, p0[0], p0[1], p0[2], p0[0], p0[1], p0[2])
                vertex(data, p1[0], p1[1], p1[2], p1[0], p1[1], p1[2])
                vertex(data, p2[0], p2[1], p2[2], p2[0], p2[1], p2[2])
                vertex(data, p0[0], p0[1], p0[2], p0[0], p0[1], p0[2])
                vertex(data, p2[0], p2[1], p2[2], p2[0], p2[1], p2[2])
                vertex(data, p3[0], p3[1], p3[2], p3[0], p3[1], p3[2])
            }
        }
        return mesh(data)
    }

    private fun spherePoint(latitude: Float, longitude: Float): FloatArray {
        val c = cos(latitude)
        return floatArrayOf(c * cos(longitude), sin(latitude), c * sin(longitude))
    }

    private fun createTorus(major: Float, minor: Float, rings: Int, sides: Int): GlMesh {
        val data = ArrayList<Float>(rings * sides * 36)
        for (i in 0 until rings) {
            val u0 = 2f * PI.toFloat() * i / rings
            val u1 = 2f * PI.toFloat() * (i + 1) / rings
            for (j in 0 until sides) {
                val v0 = 2f * PI.toFloat() * j / sides
                val v1 = 2f * PI.toFloat() * (j + 1) / sides
                val p0 = torusPoint(major, minor, u0, v0)
                val p1 = torusPoint(major, minor, u1, v0)
                val p2 = torusPoint(major, minor, u1, v1)
                val p3 = torusPoint(major, minor, u0, v1)
                vertex(data, p0[0],p0[1],p0[2],p0[3],p0[4],p0[5])
                vertex(data, p1[0],p1[1],p1[2],p1[3],p1[4],p1[5])
                vertex(data, p2[0],p2[1],p2[2],p2[3],p2[4],p2[5])
                vertex(data, p0[0],p0[1],p0[2],p0[3],p0[4],p0[5])
                vertex(data, p2[0],p2[1],p2[2],p2[3],p2[4],p2[5])
                vertex(data, p3[0],p3[1],p3[2],p3[3],p3[4],p3[5])
            }
        }
        return mesh(data)
    }

    private fun torusPoint(major: Float, minor: Float, u: Float, v: Float): FloatArray {
        val cu = cos(u); val su = sin(u)
        val cv = cos(v); val sv = sin(v)
        val radius = major + minor * cv
        return floatArrayOf(radius * cu, minor * sv, radius * su, cv * cu, sv, cv * su)
    }

    private fun mesh(values: List<Float>): GlMesh {
        val array = FloatArray(values.size) { values[it] }
        val buffer = ByteBuffer.allocateDirect(array.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(array)
        buffer.position(0)
        return GlMesh(buffer, array.size / 6)
    }

    private fun vertex(out: MutableList<Float>, x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
        out.add(x); out.add(y); out.add(z)
        out.add(nx); out.add(ny); out.add(nz)
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val message = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("Atlas 3D shader compilation failed: " + message)
            }
            return shader
        }
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val linked = GLES20.glCreateProgram()
        GLES20.glAttachShader(linked, vs)
        GLES20.glAttachShader(linked, fs)
        GLES20.glLinkProgram(linked)
        val status = IntArray(1)
        GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] == 0) {
            val message = GLES20.glGetProgramInfoLog(linked)
            GLES20.glDeleteProgram(linked)
            throw IllegalStateException("Atlas 3D shader link failed: " + message)
        }
        return linked
    }

    companion object {
        private fun rgb(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b, 1f)
        private fun scaleColor(color: FloatArray, scale: Float) =
            floatArrayOf(min(1f, color[0] * scale), min(1f, color[1] * scale), min(1f, color[2] * scale), 1f)

        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying vec3 vWorld;
            varying vec3 vNormal;
            void main() {
                vec4 world = uModel * vec4(aPosition, 1.0);
                vWorld = world.xyz;
                vNormal = normalize(mat3(uModel) * aNormal);
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            uniform vec4 uGlow;
            uniform vec3 uEye;
            uniform float uTime;
            varying vec3 vWorld;
            varying vec3 vNormal;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 lightDir = normalize(vec3(-0.35, 0.88, 0.42));
                float diffuse = max(dot(n, lightDir), 0.0);
                float waveA = sin(vWorld.x * 2.2 + uTime * 0.72);
                float waveB = sin(vWorld.z * 2.7 - uTime * 0.63);
                float caustic = pow(max(waveA * waveB, 0.0), 5.0) * 0.34;
                vec3 viewDir = normalize(uEye - vWorld);
                vec3 halfDir = normalize(lightDir + viewDir);
                float ndv = max(dot(viewDir, n), 0.0);
                float rim = pow(1.0 - ndv, 2.2);
                float roughnessHint = clamp(0.72 - max(max(uGlow.r, uGlow.g), uGlow.b) * 0.32, 0.24, 0.72);
                float specularPower = mix(10.0, 54.0, 1.0 - roughnessHint);
                float specular = pow(max(dot(n, halfDir), 0.0), specularPower);
                float fresnel = pow(1.0 - ndv, 4.0);
                vec3 lit = uColor.rgb * (0.28 + diffuse * 0.76 + caustic);
                lit += uGlow.rgb * (0.13 + caustic * 1.25 + rim * 0.52);
                lit += vec3(0.72, 0.91, 1.0) * specular * (0.12 + fresnel * 0.34);
                float distanceToEye = length(uEye - vWorld);
                float fog = clamp((distanceToEye - 9.0) / 25.0, 0.0, 0.82);
                vec3 deepWater = vec3(0.012, 0.105, 0.145);
                gl_FragColor = vec4(mix(lit, deepWater, fog), 1.0);
            }
        """

        private const val PARTICLE_VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform float uTime;
            attribute vec3 aPosition;
            varying float vAlpha;
            void main() {
                vec3 p = aPosition;
                p.y = mod(p.y + uTime * 0.22 + 8.0, 8.0) - 1.0;
                gl_Position = uMvp * vec4(p, 1.0);
                gl_PointSize = clamp(30.0 / (1.0 + length(p) * 0.42), 1.5, 6.0);
                vAlpha = 0.34 + 0.30 * sin(uTime + aPosition.x * 2.0);
            }
        """

        private const val PARTICLE_FRAGMENT_SHADER = """
            precision mediump float;
            varying float vAlpha;
            void main() {
                vec2 p = gl_PointCoord - vec2(0.5);
                float d = length(p);
                float alpha = smoothstep(0.50, 0.04, d) * vAlpha;
                gl_FragColor = vec4(0.48, 0.92, 1.0, alpha);
            }
        """
    }
}