package com.knowledgeatlas.app.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WorldModelTest {
    private val sources = (1..12).flatMap { cat ->
        (0 until 10).map { k -> WorldSourceRef("c$cat-s$k", "موقع $k", "Site $cat-$k", cat) }
    }
    private val cities = WorldLayout.buildCities(sources)

    @Test fun everyDomainGetsItsOwnCityWithItsOwnDoors() {
        assertEquals(12, cities.size)
        cities.forEach { city ->
            assertEquals(10, city.doors.size)
            assertTrue(city.doors.all { it.source.categoryId == city.domain.categoryId })
            assertTrue(city.nameAr.startsWith("مدينة "))
        }
        assertEquals("مدينة الذكاء الاصطناعي", cities.first { it.domain.categoryId == 4 }.nameAr)
    }

    @Test fun doorsAlternateSidesAndStayFarApart() {
        cities.forEach { city ->
            city.doors.zipWithNext().forEach { (a, b) ->
                assertTrue(a.side != b.side)
                assertTrue(b.z - a.z >= 110f - 0.01f)
            }
            city.doors.groupBy { it.side }.values.forEach { sideDoors ->
                sideDoors.zipWithNext().forEach { (a, b) -> assertTrue(abs(b.z - a.z) >= WorldLayout.DOOR_SPACING - 0.01f) }
            }
            assertTrue(city.avenueLength > city.doors.maxOf { it.z })
        }
    }

    @Test fun layoutIsDeterministic() {
        val again = WorldLayout.buildCities(sources)
        assertEquals(cities, again)
    }

    @Test fun tenKilometerCalloutFiresOnce() {
        val c = ApproachCallouts()
        assertNull(c.update(60_000f))
        assertEquals(30_000f, c.update(29_000f))
        assertNull(c.update(28_000f))
        assertEquals(10_000f, c.update(9_900f))
        assertNull(c.update(9_000f))
        assertEquals("هدّي السرعة، إنت على بعد عشرة كيلو من الوجهة", WorldText.calloutFor(10_000f, "x"))
        // Jumping straight past several thresholds only reports the closest one.
        val j = ApproachCallouts()
        assertEquals(1_000f, j.update(500f))
        assertNull(j.update(100f))
    }

    @Test fun warpIsFasterThanLightAndEndsExactly() {
        cities.forEach { a ->
            cities.filter { it != a }.forEach { b ->
                val d = WorldLayout.distanceAu(a, b)
                assertTrue(WorldLayout.peakWarpFactor(d) > 1.0)
                val t = WorldLayout.warpSeconds(d)
                assertTrue(t in 8f..19f)
                assertEquals(1f, WorldLayout.warpProgress(t, t), 1e-6f)
                assertEquals(0f, WorldLayout.warpProgress(0f, t), 1e-6f)
            }
        }
    }

    @Test fun formatting() {
        assertEquals("500 م", WorldText.formatDistance(500.0))
        assertEquals("10.0 كم", WorldText.formatDistance(10_000.0))
        assertEquals("1,500 كم", WorldText.formatDistance(1_500_000.0))
        assertTrue(WorldText.formatDistance(WorldLayout.AU_METERS * 3).endsWith("وحدة فلكية"))
        assertTrue(WorldText.formatSpeed(WorldLayout.LIGHT_SPEED * 4000).contains("سرعة الضوء"))
        assertEquals("36 كم/س", WorldText.formatSpeed(10.0))
    }
}
