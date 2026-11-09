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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Tiny 3-component vector used by the procedural geometry code. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(x * x + y * y + z * z)
    fun normalized(): V3 { val l = length(); return if (l < 1e-8f) this else V3(x / l, y / l, z / l) }

    companion object {
        val X = V3(1f, 0f, 0f)
        val Y = V3(0f, 1f, 0f)
        val Z = V3(0f, 0f, 1f)
        val ZERO = V3(0f, 0f, 0f)
    }
}

internal class FloatList(initial: Int = 1024) {
    var data = FloatArray(initial); private set
    var size = 0; private set
    fun add(v: Float) { if (size == data.size) data = data.copyOf(data.size * 2); data[size++] = v }
    fun add(a: Float, b: Float) { add(a); add(b) }
    fun add(a: Float, b: Float, c: Float) { add(a); add(b); add(c) }
    fun add(a: Float, b: Float, c: Float, d: Float) { add(a); add(b); add(c); add(d) }
}

internal class IntList(initial: Int = 1024) {
    var data = IntArray(initial); private set
    var size = 0; private set
    fun add(v: Int) { if (size == data.size) data = data.copyOf(data.size * 2); data[size++] = v }
}

/** Packs a right-handed tangent frame (t, b = n x t, n) into the quaternion layout Filament expects. */
fun tangentQuaternion(n: V3, t: V3): FloatArray {
    val nn = n.normalized()
    val b = (nn cross t).normalized()
    val tt = (b cross nn).normalized()
    // Rotation matrix with columns (t, b, n); mRC = row R, column C.
    val m00 = tt.x; val m10 = tt.y; val m20 = tt.z
    val m01 = b.x; val m11 = b.y; val m21 = b.z
    val m02 = nn.x; val m12 = nn.y; val m22 = nn.z
    val trace = m00 + m11 + m22
    var qx: Float; var qy: Float; var qz: Float; var qw: Float
    if (trace > 0f) {
        val s = 0.5f / sqrt(trace + 1f)
        qw = 0.25f / s; qx = (m21 - m12) * s; qy = (m02 - m20) * s; qz = (m10 - m01) * s
    } else if (m00 > m11 && m00 > m22) {
        val s = 2f * sqrt(1f + m00 - m11 - m22)
        qw = (m21 - m12) / s; qx = 0.25f * s; qy = (m01 + m10) / s; qz = (m02 + m20) / s
    } else if (m11 > m22) {
        val s = 2f * sqrt(1f + m11 - m00 - m22)
        qw = (m02 - m20) / s; qx = (m01 + m10) / s; qy = 0.25f * s; qz = (m12 + m21) / s
    } else {
        val s = 2f * sqrt(1f + m22 - m00 - m11)
        qw = (m10 - m01) / s; qx = (m02 + m20) / s; qy = (m12 + m21) / s; qz = 0.25f * s
    }
    if (qw < 0f) { qx = -qx; qy = -qy; qz = -qz; qw = -qw }
    val bias = 1f / 32767f
    if (qw < bias) {
        val f = sqrt(1f - bias * bias)
        qx *= f; qy *= f; qz *= f; qw = bias
    }
    return floatArrayOf(qx, qy, qz, qw)
}

class Mesh(val entity: Int, val vertexBuffer: VertexBuffer, val indexBuffer: IndexBuffer) {
    fun destroy(engine: Engine) {
        engine.destroyEntity(entity)
        EntityManager.get().destroy(entity)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
    }
}

/**
 * Accumulates static geometry (position, tangent frame, uv, color) so whole districts can be
 * submitted as a handful of draw calls. Texture coordinates are expressed in meters and scaled
 * by the material, which keeps texel density identical on every building.
 */
class MeshBuilder {
    private val pos = FloatList()
    private val tan = FloatList()
    private val uv = FloatList()
    private val col = FloatList()
    private val idx = IntList()
    private var minX = Float.MAX_VALUE; private var minY = Float.MAX_VALUE; private var minZ = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE; private var maxY = -Float.MAX_VALUE; private var maxZ = -Float.MAX_VALUE
    val vertexCount get() = pos.size / 3
    val isEmpty get() = idx.size == 0

    fun vertex(p: V3, quat: FloatArray, u: Float, v: Float, c: FloatArray): Int {
        pos.add(p.x, p.y, p.z)
        tan.add(quat[0], quat[1], quat[2], quat[3])
        uv.add(u, v)
        col.add(c[0], c[1], c[2], c[3])
        minX = min(minX, p.x); minY = min(minY, p.y); minZ = min(minZ, p.z)
        maxX = max(maxX, p.x); maxY = max(maxY, p.y); maxZ = max(maxZ, p.z)
        return vertexCount - 1
    }

    fun triangle(a: Int, b: Int, c: Int) { idx.add(a); idx.add(b); idx.add(c) }

    /**
     * Adds a rectangle facing [n]. [up] is the in-plane direction that maps to "image up".
     * UVs are world meters relative to [uvOrigin] (u along the viewer's right, v downwards).
     */
    fun quad(
        center: V3, n: V3, up: V3, halfW: Float, halfH: Float,
        color: FloatArray = WHITE, uvOrigin: V3? = null, uvUnit: Boolean = false
    ) {
        val nn = n.normalized()
        val b = up.normalized()
        val t = (b cross nn).normalized()
        val q = tangentQuaternion(nn, t)
        val corners = arrayOf(
            center - t * halfW - b * halfH,
            center + t * halfW - b * halfH,
            center + t * halfW + b * halfH,
            center - t * halfW + b * halfH
        )
        val unitUv = arrayOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 0f))
        val o = uvOrigin ?: V3.ZERO
        val base = vertexCount
        for (i in 0 until 4) {
            val p = corners[i]
            if (uvUnit) {
                vertex(p, q, unitUv[i][0], unitUv[i][1], color)
            } else {
                val d = p - o
                vertex(p, q, d dot t, -(d dot b), color)
            }
        }
        triangle(base, base + 1, base + 2)
        triangle(base, base + 2, base + 3)
    }

    /** Axis-aligned box. Side walls get facade-style UVs anchored at the box corner and ground level. */
    fun box(
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        color: FloatArray = WHITE, top: Boolean = true, bottom: Boolean = false, sides: Boolean = true
    ) {
        val cx = (x0 + x1) / 2f; val cy = (y0 + y1) / 2f; val cz = (z0 + z1) / 2f
        val hx = (x1 - x0) / 2f; val hy = (y1 - y0) / 2f; val hz = (z1 - z0) / 2f
        val origin = V3(x0, y0, z0)
        if (sides) {
            quad(V3(x1, cy, cz), V3.X, V3.Y, hz, hy, color, V3(x1, y0, z1))
            quad(V3(x0, cy, cz), V3(-1f, 0f, 0f), V3.Y, hz, hy, color, V3(x0, y0, z0))
            quad(V3(cx, cy, z1), V3.Z, V3.Y, hx, hy, color, V3(x0, y0, z1))
            quad(V3(cx, cy, z0), V3(0f, 0f, -1f), V3.Y, hx, hy, color, V3(x1, y0, z0))
        }
        if (top) quad(V3(cx, y1, cz), V3.Y, V3.Z, hx, hz, color, origin)
        if (bottom) quad(V3(cx, y0, cz), V3(0f, -1f, 0f), V3(0f, 0f, -1f), hx, hz, color, origin)
    }

    /** Flat ring on the XZ plane (landing pad markings, plaza circles). */
    fun annulus(cx: Float, y: Float, cz: Float, rIn: Float, rOut: Float, segments: Int, color: FloatArray) {
        val q = tangentQuaternion(V3.Y, V3.X)
        val base = vertexCount
        for (i in 0..segments) {
            val a = i * (Math.PI * 2 / segments).toFloat()
            val c = cos(a); val s = sin(a)
            vertex(V3(cx + c * rIn, y, cz + s * rIn), q, c * rIn, s * rIn, color)
            vertex(V3(cx + c * rOut, y, cz + s * rOut), q, c * rOut, s * rOut, color)
        }
        for (i in 0 until segments) {
            val a = base + i * 2
            triangle(a, a + 3, a + 1)
            triangle(a, a + 2, a + 3)
        }
    }

    /** Vertical cylinder without caps (pillars, lamp poles). */
    fun cylinder(cx: Float, cz: Float, y0: Float, y1: Float, r: Float, segments: Int, color: FloatArray) {
        val base = vertexCount
        for (i in 0..segments) {
            val a = i * (Math.PI * 2 / segments).toFloat()
            val n = V3(cos(a), 0f, sin(a))
            val q = tangentQuaternion(n, V3(-sin(a), 0f, cos(a)))
            val u = a * r
            vertex(V3(cx + n.x * r, y0, cz + n.z * r), q, u, 0f, color)
            vertex(V3(cx + n.x * r, y1, cz + n.z * r), q, u, -(y1 - y0), color)
        }
        for (i in 0 until segments) {
            val a = base + i * 2
            triangle(a, a + 1, a + 3)
            triangle(a, a + 3, a + 2)
        }
    }

    fun build(engine: Engine, material: MaterialInstance, castShadows: Boolean = true, receiveShadows: Boolean = true, priority: Int = 4): Mesh {
        val vc = vertexCount
        val vb = VertexBuffer.Builder()
            .vertexCount(vc)
            .bufferCount(4)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .attribute(VertexBuffer.VertexAttribute.UV0, 2, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 3, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .build(engine)
        vb.setBufferAt(engine, 0, floatBuffer(pos.data, pos.size))
        vb.setBufferAt(engine, 1, floatBuffer(tan.data, tan.size))
        vb.setBufferAt(engine, 2, floatBuffer(uv.data, uv.size))
        vb.setBufferAt(engine, 3, floatBuffer(col.data, col.size))
        val ib = IndexBuffer.Builder()
            .indexCount(idx.size)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        val ibb = ByteBuffer.allocateDirect(idx.size * 4).order(ByteOrder.nativeOrder())
        ibb.asIntBuffer().put(idx.data, 0, idx.size)
        ib.setBuffer(engine, ibb)
        val entity = EntityManager.get().create()
        val pad = 0.5f
        RenderableManager.Builder(1)
            .boundingBox(
                Box(
                    (minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f,
                    (maxX - minX) / 2f + pad, (maxY - minY) / 2f + pad, (maxZ - minZ) / 2f + pad
                )
            )
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, idx.size)
            .material(0, material)
            .priority(priority)
            .castShadows(castShadows)
            .receiveShadows(receiveShadows)
            .build(engine, entity)
        return Mesh(entity, vb, ib)
    }

    companion object {
        val WHITE = floatArrayOf(1f, 1f, 1f, 0f)

        fun floatBuffer(data: FloatArray, count: Int): ByteBuffer {
            val bb = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
            bb.asFloatBuffer().put(data, 0, count)
            return bb
        }

        /** Linear RGB from an sRGB ARGB int, with [emissive] stored in alpha for color_lit. */
        fun color(argb: Long, emissive: Float = 0f): FloatArray {
            fun lin(c: Long): Float {
                val v = c / 255f
                return if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
            }
            return floatArrayOf(lin((argb shr 16) and 0xFF), lin((argb shr 8) and 0xFF), lin(argb and 0xFF), emissive)
        }
    }
}

/** Column-major 4x4 transforms for Filament. */
object Xf {
    /** Model matrix whose local +X/+Y/+Z map to [right]/[up]/[forward], uniformly scaled. */
    fun basis(pos: V3, right: V3, up: V3, forward: V3, scale: Float = 1f): FloatArray = floatArrayOf(
        right.x * scale, right.y * scale, right.z * scale, 0f,
        up.x * scale, up.y * scale, up.z * scale, 0f,
        forward.x * scale, forward.y * scale, forward.z * scale, 0f,
        pos.x, pos.y, pos.z, 1f
    )

    /** Yaw (around +Y) where yaw = 0 faces +Z. */
    fun yaw(pos: V3, yaw: Float, scale: Float = 1f): FloatArray {
        val f = V3(sin(yaw), 0f, cos(yaw))
        val r = V3(cos(yaw), 0f, -sin(yaw))
        return basis(pos, r, V3.Y, f, scale)
    }

    fun translation(x: Float, y: Float, z: Float) = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f)
}
