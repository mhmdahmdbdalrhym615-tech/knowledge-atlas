package com.knowledgeatlas.app.world

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max

/** RGBA8 pixels decoded off the render thread, ready for upload. */
class DecodedImage(val width: Int, val height: Int, val pixels: ByteBuffer)

object WorldFiles {
    const val MATERIAL_WORLD = "world/materials/world_lit.filamat"
    const val MATERIAL_COLOR = "world/materials/color_lit.filamat"
    const val MATERIAL_SIGN = "world/materials/sign_unlit.filamat"
    const val MATERIAL_PARTICLE = "world/materials/particle_add.filamat"
    const val MATERIAL_PORTAL = "world/materials/portal.filamat"
    const val GUIDE = "world/models/atlas_guide.glb"
    const val SHUTTLE = "world/models/atlas_shuttle.glb"
    const val SKY_DAY = "world/env/day_skybox.ktx"
    const val IBL_DAY = "world/env/day_ibl.ktx"
    const val SKY_SPACE = "world/env/space_skybox.ktx"
    const val IBL_SPACE = "world/env/space_ibl.ktx"
    const val FONT = "world/fonts/Cairo.ttf"

    /** Surface sets: color (sRGB), normal (OpenGL convention) and AO/roughness/metallic. */
    val surfaces = listOf(
        "terrain", "asphalt", "pavers", "hexpad", "marble", "metal", "podium",
        "facade0", "facade1", "facade2", "facade3", "facade4", "facade5"
    )

    fun readAsset(assets: AssetManager, path: String): ByteBuffer {
        val bytes = assets.open(path).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }
    }

    fun decode(assets: AssetManager, path: String): DecodedImage {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
        }
        val bitmap = assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Unable to decode $path")
        return fromBitmap(bitmap).also { bitmap.recycle() }
    }

    fun fromBitmap(bitmap: Bitmap): DecodedImage {
        val buffer = ByteBuffer.allocateDirect(bitmap.byteCount).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(buffer)
        buffer.flip()
        return DecodedImage(bitmap.width, bitmap.height, buffer)
    }
}

/** Uploads decoded images as mip-mapped Filament textures. Must be called on the render thread. */
object TextureUpload {
    val repeatSampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT
    ).apply { anisotropy = 8f }

    val clampSampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE
    ).apply { anisotropy = 4f }

    fun upload(engine: Engine, image: DecodedImage, srgb: Boolean): Texture {
        val levels = floor(log2(max(image.width, image.height).toFloat())).toInt() + 1
        val texture = Texture.Builder()
            .width(image.width)
            .height(image.height)
            .levels(levels)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(if (srgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .build(engine)
        texture.setImage(engine, 0, Texture.PixelBufferDescriptor(image.pixels, Texture.Format.RGBA, Texture.Type.UBYTE))
        texture.generateMipmaps(engine)
        return texture
    }

    fun loadMaterial(engine: Engine, assets: AssetManager, path: String): Material {
        val buffer = WorldFiles.readAsset(assets, path)
        return Material.Builder().payload(buffer, buffer.remaining()).build(engine)
    }
}

/** A PBR surface (three textures) shared by every mesh that uses it. */
class SurfaceSet(val name: String, val color: Texture, val normal: Texture, val arm: Texture) {
    fun instance(
        material: Material,
        tileMeters: Float,
        tint: FloatArray = floatArrayOf(1f, 1f, 1f),
        normalStrength: Float = 1f,
        roughnessBias: Float = 0f,
        variation: Float = 0.35f,
        tileMetersV: Float = tileMeters
    ): MaterialInstance = material.createInstance().apply {
        setParameter("baseColorMap", color, TextureUpload.repeatSampler)
        setParameter("normalMap", normal, TextureUpload.repeatSampler)
        setParameter("armMap", arm, TextureUpload.repeatSampler)
        setParameter("uvScale", 1f / tileMeters, 1f / tileMetersV)
        setParameter("tint", tint[0], tint[1], tint[2])
        setParameter("normalStrength", normalStrength)
        setParameter("roughnessBias", roughnessBias)
        setParameter("variation", variation)
    }

    fun destroy(engine: Engine) {
        engine.destroyTexture(color)
        engine.destroyTexture(normal)
        engine.destroyTexture(arm)
    }
}
