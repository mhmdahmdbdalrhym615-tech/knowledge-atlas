package com.knowledgeatlas.app.world

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * CPU-simulated, GPU-drawn particles. Each particle is a camera-facing quad (or a velocity-aligned
 * streak) shaded by an additive soft-disc material, so thousands of sparks, exhaust embers, dust
 * motes, stars and warp streaks cost a single draw call per system.
 *
 * When [cameraRelative] is true, positions are stored relative to the camera (stars, warp streaks)
 * and only translated at upload time, so they never drift or lose float precision.
 */
class ParticleSystem(
    private val engine: Engine,
    material: MaterialInstance,
    val capacity: Int,
    private val cameraRelative: Boolean = false,
    priority: Int = 7
) {
    private val px = FloatArray(capacity); private val py = FloatArray(capacity); private val pz = FloatArray(capacity)
    private val vx = FloatArray(capacity); private val vy = FloatArray(capacity); private val vz = FloatArray(capacity)
    private val life = FloatArray(capacity); private val maxLife = FloatArray(capacity)
    private val size0 = FloatArray(capacity); private val size1 = FloatArray(capacity)
    private val cr = FloatArray(capacity); private val cg = FloatArray(capacity); private val cb = FloatArray(capacity)
    private val alpha = FloatArray(capacity)
    private val stretch = FloatArray(capacity)
    private val drag = FloatArray(capacity)
    private val gravity = FloatArray(capacity)
    private val twinkle = FloatArray(capacity)
    private var count = 0
    private var time = 0f

    val entity: Int = EntityManager.get().create()
    private val vb: VertexBuffer = VertexBuffer.Builder()
        .vertexCount(capacity * 4)
        .bufferCount(3)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
        .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8)
        .attribute(VertexBuffer.VertexAttribute.COLOR, 2, VertexBuffer.AttributeType.FLOAT4, 0, 16)
        .build(engine)
    private val ib: IndexBuffer = IndexBuffer.Builder()
        .indexCount(capacity * 6)
        .bufferType(IndexBuffer.Builder.IndexType.UINT)
        .build(engine)

    // Triple-buffered uploads so the CPU never rewrites memory the driver may still be reading.
    private val posBuffers = Array(3) { direct(capacity * 4 * 3) }
    private val colBuffers = Array(3) { direct(capacity * 4 * 4) }
    private var ring = 0
    var visible = true

    init {
        val uv = direct(capacity * 4 * 2)
        val ufb = uv.asFloatBuffer()
        repeat(capacity) { ufb.put(-1f).put(-1f).put(1f).put(-1f).put(1f).put(1f).put(-1f).put(1f) }
        vb.setBufferAt(engine, 1, uv)
        val idx = ByteBuffer.allocateDirect(capacity * 6 * 4).order(ByteOrder.nativeOrder())
        val ibuf = idx.asIntBuffer()
        for (i in 0 until capacity) {
            val b = i * 4
            ibuf.put(b).put(b + 1).put(b + 2).put(b).put(b + 2).put(b + 3)
        }
        ib.setBuffer(engine, idx)
        // Seed the position buffer once so unused vertices are defined.
        vb.setBufferAt(engine, 0, posBuffers[0])
        vb.setBufferAt(engine, 2, colBuffers[0])
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 1e6f, 1e6f, 1e6f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, 0)
            .material(0, material)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .priority(priority)
            .build(engine, entity)
    }

    val liveCount get() = count

    fun clear() { count = 0 }

    /** Spawns one particle. Colors are linear; [stretchFactor] > 0 turns it into a velocity streak. */
    fun emit(
        x: Float, y: Float, z: Float,
        velX: Float, velY: Float, velZ: Float,
        lifetime: Float, startSize: Float, endSize: Float,
        r: Float, g: Float, b: Float, a: Float = 1f,
        stretchFactor: Float = 0f, dragCoeff: Float = 0f, gravityAccel: Float = 0f, twinkleAmount: Float = 0f
    ) {
        if (count >= capacity) return
        val i = count++
        px[i] = x; py[i] = y; pz[i] = z
        vx[i] = velX; vy[i] = velY; vz[i] = velZ
        life[i] = 0f; maxLife[i] = max(0.01f, lifetime)
        size0[i] = startSize; size1[i] = endSize
        cr[i] = r; cg[i] = g; cb[i] = b; alpha[i] = a
        stretch[i] = stretchFactor; drag[i] = dragCoeff; gravity[i] = gravityAccel; twinkle[i] = twinkleAmount
    }

    /** Advances the simulation. Particles with infinite life (lifetime >= 1e6) never die. */
    fun update(dt: Float, shiftX: Float = 0f, shiftY: Float = 0f, shiftZ: Float = 0f, recycle: ((Int) -> Boolean)? = null) {
        time += dt
        var i = 0
        while (i < count) {
            life[i] += dt
            val dead = life[i] >= maxLife[i]
            if (dead && (recycle == null || !recycle(i))) {
                kill(i)
                continue
            }
            val d = 1f / (1f + drag[i] * dt)
            vx[i] *= d; vy[i] *= d; vz[i] *= d
            vy[i] -= gravity[i] * dt
            px[i] += vx[i] * dt - shiftX
            py[i] += vy[i] * dt - shiftY
            pz[i] += vz[i] * dt - shiftZ
            i++
        }
    }

    /** Respawns particle [i] in place (used by star fields and warp tunnels). */
    fun respawn(i: Int, x: Float, y: Float, z: Float, velX: Float, velY: Float, velZ: Float, lifetime: Float) {
        px[i] = x; py[i] = y; pz[i] = z; vx[i] = velX; vy[i] = velY; vz[i] = velZ
        life[i] = 0f; maxLife[i] = lifetime
    }

    fun positionX(i: Int) = px[i]
    fun positionY(i: Int) = py[i]
    fun positionZ(i: Int) = pz[i]

    fun setVelocity(i: Int, x: Float, y: Float, z: Float) { vx[i] = x; vy[i] = y; vz[i] = z }
    fun setStretch(i: Int, s: Float) { stretch[i] = s }
    fun setAlpha(i: Int, a: Float) { alpha[i] = a }

    private fun kill(i: Int) {
        val last = --count
        if (i == last) return
        px[i] = px[last]; py[i] = py[last]; pz[i] = pz[last]
        vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last]
        life[i] = life[last]; maxLife[i] = maxLife[last]
        size0[i] = size0[last]; size1[i] = size1[last]
        cr[i] = cr[last]; cg[i] = cg[last]; cb[i] = cb[last]; alpha[i] = alpha[last]
        stretch[i] = stretch[last]; drag[i] = drag[last]; gravity[i] = gravity[last]; twinkle[i] = twinkle[last]
    }

    /**
     * Writes quads for the current camera. [cam] is the camera position, [right]/[up] the camera
     * basis vectors. Streaks are aligned with their velocity and widened perpendicular to the view.
     */
    fun upload(cam: V3, right: V3, up: V3) {
        val rm = engine.renderableManager
        val inst = rm.getInstance(entity)
        val n = if (visible) count else 0
        if (n == 0) {
            rm.setGeometryAt(inst, 0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, 0)
            return
        }
        ring = (ring + 1) % 3
        val pb = posBuffers[ring]; val cbuf = colBuffers[ring]
        pb.clear(); cbuf.clear()
        val pf: FloatBuffer = pb.asFloatBuffer()
        val cf: FloatBuffer = cbuf.asFloatBuffer()
        val ox = if (cameraRelative) cam.x else 0f
        val oy = if (cameraRelative) cam.y else 0f
        val oz = if (cameraRelative) cam.z else 0f
        for (i in 0 until n) {
            val t = (life[i] / maxLife[i]).coerceIn(0f, 1f)
            val s = size0[i] + (size1[i] - size0[i]) * t
            // Fade in quickly, fade out smoothly.
            var fade = min(1f, t * 12f) * (1f - t * t)
            if (maxLife[i] >= 1e6f) fade = 1f
            if (twinkle[i] > 0f) fade *= 1f - twinkle[i] * (0.5f + 0.5f * kotlin.math.sin(time * 3.1f + i * 1.7f))
            val a = alpha[i] * fade
            val x = px[i] + ox; val y = py[i] + oy; val z = pz[i] + oz
            var ax: Float; var ay: Float; var az: Float
            var bx: Float; var by: Float; var bz: Float
            if (stretch[i] > 0f) {
                val sp = sqrt(vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i])
                if (sp > 1e-4f) {
                    val dx = vx[i] / sp; val dy = vy[i] / sp; val dz = vz[i] / sp
                    val len = s + sp * stretch[i]
                    // Perpendicular to both the streak and the view direction.
                    val tx = x - cam.x; val ty = y - cam.y; val tz = z - cam.z
                    var wx = dy * tz - dz * ty; var wy = dz * tx - dx * tz; var wz = dx * ty - dy * tx
                    val wl = sqrt(wx * wx + wy * wy + wz * wz).coerceAtLeast(1e-5f)
                    wx = wx / wl * s; wy = wy / wl * s; wz = wz / wl * s
                    ax = wx; ay = wy; az = wz
                    bx = dx * len; by = dy * len; bz = dz * len
                } else {
                    ax = right.x * s; ay = right.y * s; az = right.z * s
                    bx = up.x * s; by = up.y * s; bz = up.z * s
                }
            } else {
                ax = right.x * s; ay = right.y * s; az = right.z * s
                bx = up.x * s; by = up.y * s; bz = up.z * s
            }
            pf.put(x - ax - bx).put(y - ay - by).put(z - az - bz)
            pf.put(x + ax - bx).put(y + ay - by).put(z + az - bz)
            pf.put(x + ax + bx).put(y + ay + by).put(z + az + bz)
            pf.put(x - ax + bx).put(y - ay + by).put(z - az + bz)
            repeat(4) { cf.put(cr[i]).put(cg[i]).put(cb[i]).put(a) }
        }
        vb.setBufferAt(engine, 0, pb, 0, n * 4 * 3 * 4)
        vb.setBufferAt(engine, 2, cbuf, 0, n * 4 * 4 * 4)
        rm.setGeometryAt(inst, 0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, n * 6)
    }

    fun destroy() {
        engine.destroyEntity(entity)
        EntityManager.get().destroy(entity)
        engine.destroyVertexBuffer(vb)
        engine.destroyIndexBuffer(ib)
    }

    private companion object {
        fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}
