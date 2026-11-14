package com.knowledgeatlas.app.world

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** One knowledge domain rendered as its own city. Pure data so it can be unit-tested. */
data class WorldDomain(val categoryId: Int, val titleAr: String, val colorArgb: Long)

/** Minimal view of a catalog source needed by the world (keeps the world independent from Room). */
data class WorldSourceRef(val id: String, val nameAr: String, val nameEn: String, val categoryId: Int)

data class DoorDef(
    val source: WorldSourceRef,
    val index: Int,
    /** Door center on the ground, local city coordinates (meters). */
    val x: Float,
    val z: Float,
    /** +1 when the building is on the +X side of the boulevard (door faces -X), -1 otherwise. */
    val side: Int,
    val towerWidth: Float,
    val towerDepth: Float,
    val towerHeight: Float,
    val facade: Int
) {
    /** Center of the tower footprint. */
    val towerCenterX: Float get() = x + side * towerDepth / 2f
}

data class CityDef(
    val domain: WorldDomain,
    val index: Int,
    val nameAr: String,
    /** Position of the city in the star system, astronomical units. */
    val ax: Double,
    val ay: Double,
    val az: Double,
    val doors: List<DoorDef>,
    val seed: Long
) {
    /** Length of the main avenue (meters), at least long enough for the gate and a plaza. */
    val avenueLength: Float get() = max(700f, (doors.maxOfOrNull { it.z } ?: 0f) + 260f)
}

object WorldLayout {
    const val AU_METERS = 1.495978707e11
    const val LIGHT_SPEED = 299_792_458.0

    /** Every trip between two different cities is exactly this long, in kilometres. */
    const val CITY_DISTANCE_KM = 980_000.0

    /** Warp peak speed as a multiple of c, and the allowed trip duration in seconds. */
    const val WARP_PEAK_C = 1.5
    const val MIN_WARP_SECONDS = 3.0
    const val MAX_WARP_SECONDS = 12.0

    const val AVENUE_HALF_WIDTH = 16f
    const val SIDEWALK = 9f
    const val DOOR_SPACING = 220f
    const val FIRST_DOOR_Z = 150f
    const val GATE_Z = 40f

    /** Spaceport pad where the ship parks, local coordinates. */
    const val PAD_X = 0f
    const val PAD_Z = -190f

    /** Ship drops out of warp this far from the pad. */
    const val WARP_EXIT_DISTANCE = 60_000f
    const val WARP_EXIT_ALTITUDE = 4_200f

    val defaultDomains = listOf(
        WorldDomain(1, "التعليم", 0xFF54D6C7),
        WorldDomain(2, "الأبحاث", 0xFF8CA7FF),
        WorldDomain(3, "البرمجة", 0xFF63C8FF),
        WorldDomain(4, "الذكاء الاصطناعي", 0xFFB18CFF),
        WorldDomain(5, "الزراعة", 0xFF83D88A),
        WorldDomain(6, "العلوم", 0xFF54D6E8),
        WorldDomain(7, "الأعمال", 0xFFFFC477),
        WorldDomain(8, "البيانات", 0xFF6EA8FF),
        WorldDomain(9, "الصحة العامة", 0xFFFF8FA3),
        WorldDomain(10, "الفكر والتاريخ", 0xFFD6B47C),
        WorldDomain(11, "الهندسة والطاقة", 0xFF9AC7FF),
        WorldDomain(12, "الإنتاجية", 0xFFB8A0FF)
    )

    fun buildCities(sources: List<WorldSourceRef>, domains: List<WorldDomain> = defaultDomains): List<CityDef> =
        domains.mapIndexed { i, domain ->
            val seed = 0x5EED_0000L + domain.categoryId * 7919L
            val rnd = SeededRandom(seed)
            val list = sources.filter { it.categoryId == domain.categoryId }.sortedBy { it.nameEn.lowercase() }
            val doors = list.mapIndexed { k, src ->
                val side = if (k % 2 == 0) -1 else 1
                val z = FIRST_DOOR_Z + (k / 2) * DOOR_SPACING + if (side > 0) DOOR_SPACING / 2f else 0f
                val width = 38f + rnd.nextFloat() * 14f
                val depth = 34f + rnd.nextFloat() * 14f
                DoorDef(
                    source = src,
                    index = k,
                    x = side * (AVENUE_HALF_WIDTH + SIDEWALK + 6f),
                    z = z,
                    side = side,
                    towerWidth = width,
                    towerDepth = depth,
                    towerHeight = 70f + rnd.nextFloat() * 140f,
                    facade = rnd.nextInt(6)
                )
            }
            // Cities sit on a gentle spiral around the system's star; units are AU.
            val angle = i * (2 * PI / domains.size) + 0.21 * i
            val radius = 9.0 + 4.5 * (i % 4) + 0.8 * i
            CityDef(
                domain = domain,
                index = i,
                nameAr = "مدينة ${domain.titleAr}",
                ax = radius * cos(angle),
                ay = (i % 3 - 1) * 1.7,
                az = radius * sin(angle),
                doors = doors,
                seed = seed
            )
        }

    fun distanceAu(a: CityDef, b: CityDef): Double =
        if (a.index == b.index) 0.0 else CITY_DISTANCE_KM * 1000.0 / AU_METERS

    /** Seconds spent inside warp; long trips last longer but never become boring. */
    fun warpSeconds(distanceAu: Double): Float {
        val meters = distanceAu * AU_METERS
        val seconds = meters / (WARP_PEAK_C * LIGHT_SPEED * 2.0 / 3.0)
        return seconds.coerceIn(MIN_WARP_SECONDS, MAX_WARP_SECONDS).toFloat()
    }

    /** Peak warp speed as a multiple of c, for a smoothstep velocity profile over [warpSeconds]. */
    fun peakWarpFactor(distanceAu: Double): Double {
        val meters = distanceAu * AU_METERS
        val seconds = warpSeconds(distanceAu).toDouble()
        // The speed profile 4x(1-x) (derivative of smoothstep, normalised) integrates to 2/3 * peak * seconds.
        return meters / (seconds * 2.0 / 3.0) / LIGHT_SPEED
    }

    /** Fraction of the trip completed after [t] seconds of a [total] second warp (smooth start and stop). */
    fun warpProgress(t: Float, total: Float): Float {
        val x = (t / total).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** Current speed factor (0..1 of peak) matching [warpProgress]. */
    fun warpSpeedShape(t: Float, total: Float): Float {
        val x = (t / total).coerceIn(0f, 1f)
        return 4f * x * (1f - x)
    }
}

/** Fires each spoken distance callout exactly once while a distance shrinks. */
class ApproachCallouts(private val thresholdsMeters: List<Float> = listOf(30_000f, 10_000f, 3_000f, 1_000f)) {
    private val fired = mutableSetOf<Float>()

    fun reset() = fired.clear()

    /** Returns the threshold that was just crossed, or null. */
    fun update(distanceMeters: Float): Float? {
        // The closest threshold we are already inside is the one worth announcing.
        for (t in thresholdsMeters.sorted()) {
            if (distanceMeters <= t && t !in fired) {
                // Mark all larger thresholds as fired too so a jump never replays stale callouts.
                thresholdsMeters.filter { it >= t }.forEach { fired += it }
                return t
            }
        }
        return null
    }
}

object WorldText {
    fun calloutFor(thresholdMeters: Float, cityName: String): String = when {
        thresholdMeters >= 30_000f -> "أنت على بعد ثلاثين كيلو من $cityName"
        thresholdMeters >= 10_000f -> "هدّي السرعة، إنت على بعد عشرة كيلو من الوجهة"
        thresholdMeters >= 3_000f -> "قرّبنا، فاضل ثلاثة كيلو. جهّز للهبوط"
        else -> "فاضل كيلو واحد على مهبط $cityName"
    }

    fun formatDistance(meters: Double): String = when {
        meters >= WorldLayout.AU_METERS * 0.1 -> "${formatNumber(meters / WorldLayout.AU_METERS, 2)} وحدة فلكية"
        meters >= 1_000_000 -> "${formatNumber(meters / 1000.0, 0)} كم"
        meters >= 1_000 -> "${formatNumber(meters / 1000.0, 1)} كم"
        else -> "${meters.roundToInt()} م"
    }

    fun formatSpeed(metersPerSecond: Double): String = when {
        metersPerSecond >= WorldLayout.LIGHT_SPEED * 0.5 -> "${formatNumber(metersPerSecond / WorldLayout.LIGHT_SPEED, if (metersPerSecond < WorldLayout.LIGHT_SPEED * 10.0) 1 else 0)} × سرعة الضوء"
        metersPerSecond >= 1000 -> "${formatNumber(metersPerSecond * 3.6, 0)} كم/س"
        else -> "${(metersPerSecond * 3.6).roundToInt()} كم/س"
    }

    fun formatNumber(value: Double, decimals: Int): String {
        if (decimals <= 0) {
            val n = value.roundToLong()
            return groupThousands(n)
        }
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val scaled = (abs(value) * scale).roundToLong()
        val whole = scaled / scale
        val frac = (scaled % scale).toString().padStart(decimals, '0')
        return (if (value < 0) "-" else "") + groupThousands(whole) + "." + frac
    }

    private fun groupThousands(n: Long): String {
        val s = abs(n).toString()
        val sb = StringBuilder()
        for (i in s.indices) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(s[i])
        }
        return (if (n < 0) "-" else "") + sb
    }
}

/** Small deterministic PRNG (SplitMix64) so every city is laid out identically on every device. */
class SeededRandom(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextFloat(): Float = ((nextLong() ushr 40).toFloat() / (1L shl 24).toFloat())
    fun nextInt(bound: Int): Int = min(bound - 1, (nextFloat() * bound).toInt())
    fun range(a: Float, b: Float): Float = a + (b - a) * nextFloat()
}
