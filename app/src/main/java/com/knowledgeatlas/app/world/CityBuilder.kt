package com.knowledgeatlas.app.world

import android.graphics.Bitmap
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Axis-aligned obstacle on the ground plane used for character and camera collisions. */
data class Rect2(val x0: Float, val z0: Float, val x1: Float, val z1: Float) {
    fun contains(x: Float, z: Float, pad: Float = 0f) = x > x0 - pad && x < x1 + pad && z > z0 - pad && z < z1 + pad
}

/** Shared material instances (created once per engine, reused by every city). */
class WorldPalette(
    val surfaces: Map<String, MaterialInstance>,
    val facades: List<MaterialInstance>,
    val colorLit: MaterialInstance,
    val colorLitDecal: MaterialInstance,
    val signMaterial: Material,
    val portalMaterial: Material
)

class CityScene(
    val def: CityDef,
    val meshes: List<Mesh>,
    private val instances: List<MaterialInstance>,
    private val textures: List<Texture>,
    val obstacles: List<Rect2>,
    /** Door trigger points: in front of each portal, on the avenue side. */
    val doorFronts: List<V3>,
    val fountain: V3,
    val portalCenters: List<V3>
) {
    val entities: IntArray get() = meshes.map { it.entity }.toIntArray()

    fun destroy(engine: Engine) {
        meshes.forEach { it.destroy(engine) }
        instances.forEach { engine.destroyMaterialInstance(it) }
        textures.forEach { engine.destroyTexture(it) }
    }
}

/**
 * Generates one domain city: a spaceport, a monumental gate carrying the city name, a long avenue
 * where every catalog source owns a tower with a glowing portal and a "welcome" sign, plus a dense
 * procedural skyline. Geometry is batched per surface so a whole city is ~20 draw calls.
 */
class CityBuilder(private val engine: Engine, private val palette: WorldPalette, private val painter: SignPainter) {

    fun build(def: CityDef, accentArgb: Int): CityScene {
        val rnd = SeededRandom(def.seed)
        val L = def.avenueLength
        val zEnd = L + 420f
        val half = WorldLayout.AVENUE_HALF_WIDTH
        val obstacles = mutableListOf<Rect2>()
        val instances = mutableListOf<MaterialInstance>()
        val textures = mutableListOf<Texture>()
        val meshes = mutableListOf<Mesh>()

        val pavers = MeshBuilder()
        val asphalt = MeshBuilder()
        val hexpad = MeshBuilder()
        val marble = MeshBuilder()
        val metal = MeshBuilder()
        val podium = MeshBuilder()
        val facades = List(6) { MeshBuilder() }
        val colored = MeshBuilder()
        val decals = MeshBuilder()

        val ground = 0.15f
        val accent = MeshBuilder.color(accentArgb.toLong() and 0xFFFFFFFFL, 1f)
        val warm = floatArrayOf(1f, 0.78f, 0.52f, 1f)
        val steel = floatArrayOf(0.18f, 0.19f, 0.21f, 0f)
        val white = floatArrayOf(0.85f, 0.86f, 0.88f, 0.35f)

        // Ground slabs. The avenue, pads and markings sit at the same height and use polygon offset.
        pavers.quad(V3(0f, ground, (zEnd - 120f) / 2f), V3.Y, V3.Z, 950f, (zEnd + 120f) / 2f)
        asphalt.quad(V3(0f, ground, (zEnd - 70f) / 2f), V3.Y, V3.Z, half, (zEnd + 70f) / 2f)
        hexpad.quad(V3(0f, ground, -205f), V3.Y, V3.Z, 165f, 145f)
        // Runway-style approach strip leading to the pad.
        asphalt.quad(V3(0f, ground, -1400f), V3.Y, V3.Z, 30f, 1050f)

        // Lane markings and pad rings (emissive accents read well at dusk and with bloom).
        var z = -40f
        while (z < zEnd) {
            decals.box(-0.18f, ground, z, 0.18f, ground + 0.001f, z + 6f, white, sides = false)
            z += 14f
        }
        decals.box(-half + 0.6f, ground, -60f, -half + 0.9f, ground + 0.001f, zEnd, white, sides = false)
        decals.box(half - 0.9f, ground, -60f, half - 0.6f, ground + 0.001f, zEnd, white, sides = false)
        decals.annulus(WorldLayout.PAD_X, ground + 0.002f, WorldLayout.PAD_Z, 27f, 28.6f, 72, accent)
        decals.annulus(WorldLayout.PAD_X, ground + 0.002f, WorldLayout.PAD_Z, 19f, 19.7f, 64, floatArrayOf(1f, 1f, 1f, 0.6f))
        // Approach lights every 60 m toward the pad.
        var az = -2400f
        while (az < -360f) {
            colored.box(-23f, ground, az - 0.6f, -21.8f, ground + 0.45f, az + 0.6f, floatArrayOf(0.9f, 0.95f, 1f, 2.2f))
            colored.box(21.8f, ground, az - 0.6f, 23f, ground + 0.45f, az + 0.6f, floatArrayOf(0.9f, 0.95f, 1f, 2.2f))
            az += 60f
        }

        // Spaceport terminal with its own sign.
        facades[0].box(175f, 0f, -320f, 265f, 30f, -110f, top = false)
        metal.box(170f, 30f, -326f, 270f, 32f, -104f)
        obstacles += Rect2(170f, -326f, 270f, -104f)
        val portName = "ميناء ${def.nameAr} الفضائي"
        addSign(
            painter.citySign(portName, "منصة الهبوط ١", accentArgb), V3(174.6f, 21f, -215f), V3(-1f, 0f, 0f), 46f, 9.4f,
            meshes, instances, textures, intensity = 1.6f
        )

        // Monumental gate over the avenue carrying the city name.
        val gz = WorldLayout.GATE_Z
        for (s in intArrayOf(-1, 1)) {
            val x0 = if (s < 0) -half - 9f else half + 3f
            marble.box(x0, 0f, gz - 3f, x0 + 6f, 31f, gz + 3f)
            colored.box(x0 + 2.6f, 0.3f, gz - 3.05f, x0 + 3.4f, 30f, gz - 3.0f, accent)
            colored.box(x0 + 2.6f, 0.3f, gz + 3.0f, x0 + 3.4f, 30f, gz + 3.05f, accent)
            obstacles += Rect2(x0, gz - 3f, x0 + 6f, gz + 3f)
        }
        marble.box(-half - 9f, 31f, gz - 3.2f, half + 9f, 36f, gz + 3.2f)
        val subtitle = "${def.doors.size} موقع في انتظارك"
        val gateSign = painter.citySign(def.nameAr, subtitle, accentArgb)
        val gateTex = upload(gateSign, textures)
        addSignTexture(gateTex, V3(0f, 25.2f, gz - 3.35f), V3(0f, 0f, -1f), 48f, 9.85f, meshes, instances, 1.8f)
        addSignTexture(gateTex, V3(0f, 25.2f, gz + 3.35f), V3.Z, 48f, 9.85f, meshes, instances, 1.8f)

        // Floating hologram with the city name, readable from the approach corridor.
        val holo = upload(painter.hologram(def.nameAr, accentArgb), textures)
        addSignTexture(holo, V3(0f, 520f, L * 0.45f), V3(0f, 0f, -1f), 330f, 58f, meshes, instances, 2.6f)
        addSignTexture(holo, V3(0f, 520f, L * 0.45f + 0.5f), V3.Z, 330f, 58f, meshes, instances, 2.6f)

        // Street lamps on both sidewalks.
        var lz = -40f
        while (lz < zEnd - 20f) {
            for (s in intArrayOf(-1, 1)) {
                val x = s * (half + 1.4f)
                colored.cylinder(x, lz, 0f, 9f, 0.16f, 10, steel)
                val armX0 = min(x, x - s * 2.4f); val armX1 = max(x, x - s * 2.4f)
                colored.box(armX0, 8.75f, lz - 0.12f, armX1, 9f, lz + 0.12f, steel)
                colored.box(x - s * 2.4f - 0.45f, 8.55f, lz - 0.32f, x - s * 2.4f + 0.45f, 8.75f, lz + 0.32f, warm)
                obstacles += Rect2(x - 0.3f, lz - 0.3f, x + 0.3f, lz + 0.3f)
            }
            lz += 34f
        }

        // Source towers with portals and welcome signs.
        val doorFronts = mutableListOf<V3>()
        val portalCenters = mutableListOf<V3>()
        def.doors.forEach { d ->
            val s = d.side.toFloat()
            val faceX = d.x
            val backX = d.x + s * d.towerDepth
            val x0 = min(faceX, backX); val x1 = max(faceX, backX)
            val z0 = d.z - d.towerWidth / 2f; val z1 = d.z + d.towerWidth / 2f
            podium.box(x0, 0f, z0, x1, 11f, z1)
            facades[d.facade].box(x0 + 1.2f, 11f, z0 + 1.2f, x1 - 1.2f, d.towerHeight, z1 - 1.2f, top = false)
            metal.box(x0 + 0.8f, d.towerHeight, z0 + 0.8f, x1 - 0.8f, d.towerHeight + 1.6f, z1 - 0.8f)
            colored.box(x0 - 0.3f, 10.6f, z0 - 0.3f, x1 + 0.3f, 11.4f, z1 + 0.3f, accent)
            obstacles += Rect2(x0, z0, x1, z1)

            // Portal frame protrudes toward the avenue.
            val out = -s
            val fx = faceX + out * 0.02f
            val frameOut = faceX + out * 0.9f
            val jx0 = min(faceX, frameOut); val jx1 = max(faceX, frameOut)
            marble.box(jx0, 0f, d.z - 4.4f, jx1, 8.4f, d.z - 3.3f)
            marble.box(jx0, 0f, d.z + 3.3f, jx1, 8.4f, d.z + 4.4f)
            marble.box(jx0, 7.4f, d.z - 4.4f, jx1, 8.4f, d.z + 4.4f)
            val cx0 = min(faceX, faceX + out * 3.6f); val cx1 = max(faceX, faceX + out * 3.6f)
            metal.box(cx0, 8.6f, d.z - 5.6f, cx1, 9.1f, d.z + 5.6f)
            colored.box(cx0, 8.5f, d.z - 5.6f, cx1, 8.6f, d.z + 5.6f, accent)

            val portal = palette.portalMaterial.createInstance().apply {
                val c = MeshBuilder.color(accentArgb.toLong() and 0xFFFFFFFFL)
                setParameter("glow", c[0] * 1.2f + 0.05f, c[1] * 1.2f + 0.05f, c[2] * 1.2f + 0.08f)
                setParameter("intensity", 2.4f)
                setParameter("phase", d.index * 1.37f)
            }
            instances += portal
            val pm = MeshBuilder()
            pm.quad(V3(fx + out * 0.05f, ground + 3.65f, d.z), V3(out, 0f, 0f), V3.Y, 3.3f, 3.6f, uvUnit = true)
            meshes += pm.build(engine, portal, castShadows = false, receiveShadows = false, priority = 6)
            portalCenters += V3(fx + out * 0.4f, ground + 0.2f, d.z)
            doorFronts += V3(faceX + out * 5.5f, ground, d.z)

            // "Welcome to site X" sign right above the canopy.
            addSign(
                painter.doorSign(d.source.nameAr, d.source.nameEn, accentArgb),
                V3(faceX + out * 0.35f, 12.9f, d.z), V3(out, 0f, 0f), 12.6f, 3.7f,
                meshes, instances, textures, intensity = 1.9f
            )
        }

        // Plaza with fountain and landmark spire at the end of the avenue.
        val plazaZ = L + 170f
        marble.annulus(0f, ground + 0.03f, plazaZ, 0f, 34f, 64, MeshBuilder.WHITE)
        decals.annulus(0f, ground + 0.031f, plazaZ, 34f, 35.2f, 72, accent)
        podium.box(-9f, 0f, plazaZ - 9f, 9f, 1.2f, plazaZ + 9f)
        obstacles += Rect2(-9f, plazaZ - 9f, 9f, plazaZ + 9f)
        val spireZ = plazaZ + 120f
        facades[0].box(-26f, 0f, spireZ - 26f, 26f, 300f, spireZ + 26f, top = false)
        facades[0].box(-17f, 300f, spireZ - 17f, 17f, 390f, spireZ + 17f, top = false)
        metal.box(-18f, 300f, spireZ - 18f, 18f, 301f, spireZ + 18f)
        metal.box(-18f, 390f, spireZ - 18f, 18f, 391f, spireZ + 18f)
        colored.box(-1.2f, 391f, spireZ - 1.2f, 1.2f, 470f, spireZ + 1.2f, accent)
        obstacles += Rect2(-26f, spireZ - 26f, 26f, spireZ + 26f)

        // Procedural skyline that frames the avenue in every direction.
        val cell = 72f
        var gx = -900f
        while (gx < 900f) {
            var gzc = -60f
            while (gzc < zEnd + 300f) {
                val cxm = gx + cell / 2f; val czm = gzc + cell / 2f
                val nearAvenue = abs(cxm) < 125f
                val nearSpire = abs(cxm) < 70f && abs(czm - spireZ) < 70f
                val nearPlaza = abs(cxm) < 80f && abs(czm - plazaZ) < 80f
                if (!nearAvenue && !nearSpire && !nearPlaza && rnd.nextFloat() < 0.8f) {
                    val w = rnd.range(30f, 58f); val dpt = rnd.range(30f, 58f)
                    val centerBias = 1f - min(1f, abs(cxm) / 900f)
                    val h = rnd.range(26f, 70f) + centerBias * centerBias * rnd.range(30f, 260f)
                    val bx0 = cxm - w / 2f; val bz0 = czm - dpt / 2f
                    val f = rnd.nextInt(6)
                    facades[f].box(bx0, 0f, bz0, bx0 + w, h, bz0 + dpt, top = false)
                    metal.box(bx0 - 0.3f, h, bz0 - 0.3f, bx0 + w + 0.3f, h + 1.2f, bz0 + dpt + 0.3f)
                    if (h > 120f && rnd.nextFloat() < 0.55f) {
                        val inset = rnd.range(5f, 11f)
                        val h2 = h + rnd.range(18f, 70f)
                        facades[(f + 1) % 6].box(bx0 + inset, h + 1.2f, bz0 + inset, bx0 + w - inset, h2, bz0 + dpt - inset, top = false)
                        metal.box(bx0 + inset - 0.3f, h2, bz0 + inset - 0.3f, bx0 + w - inset + 0.3f, h2 + 1f, bz0 + dpt - inset + 0.3f)
                        colored.box(bx0 + w / 2f - 0.3f, h2 + 1f, bz0 + dpt / 2f - 0.3f, bx0 + w / 2f + 0.3f, h2 + 16f, bz0 + dpt / 2f + 0.3f, floatArrayOf(1f, 0.1f, 0.08f, 3f))
                    }
                    obstacles += Rect2(bx0, bz0, bx0 + w, bz0 + dpt)
                }
                gzc += cell
            }
            gx += cell
        }

        fun commit(b: MeshBuilder, mi: MaterialInstance, shadows: Boolean = true) {
            if (!b.isEmpty) meshes += b.build(engine, mi, castShadows = shadows)
        }
        commit(pavers, palette.surfaces.getValue("pavers"), shadows = false)
        commit(asphalt, palette.surfaces.getValue("asphalt"), shadows = false)
        commit(hexpad, palette.surfaces.getValue("hexpad"), shadows = false)
        commit(marble, palette.surfaces.getValue("marble"))
        commit(metal, palette.surfaces.getValue("metal"))
        commit(podium, palette.surfaces.getValue("podium"))
        facades.forEachIndexed { i, b -> commit(b, palette.facades[i]) }
        commit(colored, palette.colorLit)
        commit(decals, palette.colorLitDecal, shadows = false)

        return CityScene(def, meshes, instances, textures, obstacles, doorFronts, V3(0f, ground + 1.2f, plazaZ), portalCenters)
    }

    private fun upload(bitmap: Bitmap, textures: MutableList<Texture>): Texture {
        val tex = TextureUpload.upload(engine, WorldFiles.fromBitmap(bitmap), srgb = true)
        bitmap.recycle()
        textures += tex
        return tex
    }

    private fun addSign(
        bitmap: Bitmap, center: V3, normal: V3, width: Float, height: Float,
        meshes: MutableList<Mesh>, instances: MutableList<MaterialInstance>, textures: MutableList<Texture>, intensity: Float
    ) {
        addSignTexture(upload(bitmap, textures), center, normal, width, height, meshes, instances, intensity)
    }

    private fun addSignTexture(
        texture: Texture, center: V3, normal: V3, width: Float, height: Float,
        meshes: MutableList<Mesh>, instances: MutableList<MaterialInstance>, intensity: Float
    ) {
        val mi = palette.signMaterial.createInstance().apply {
            setParameter("signMap", texture, TextureUpload.clampSampler)
            setParameter("intensity", intensity)
        }
        instances += mi
        val b = MeshBuilder()
        b.quad(center, normal, V3.Y, width / 2f, height / 2f, uvUnit = true)
        meshes += b.build(engine, mi, castShadows = false, receiveShadows = false, priority = 6)
    }
}
