package com.ruan.uberareafilter

import com.ruan.uberareafilter.engine.ZoneShare
import com.ruan.uberareafilter.model.MapZone
import com.ruan.uberareafilter.model.ZoneMode
import com.ruan.uberareafilter.model.ZoneShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trocar zonas com outro motorista (arquivo pelo WhatsApp). */
class ZoneShareTest {

    private fun poly(id: Long, name: String, dLat: Double = 0.0, mode: ZoneMode = ZoneMode.ALLOWED) = MapZone(
        id = id, name = name, mode = mode, shape = ZoneShape.POLYGON,
        points = listOf(-22.80 + dLat to -43.30, -22.80 + dLat to -43.28, -22.78 + dLat to -43.28, -22.78 + dLat to -43.30)
    )

    @Test
    fun mergeKeepsMyMainZoneFirstAndAddsTheReceivedOnes() {
        val mine = listOf(poly(1, "Zona 1"), poly(2, "Zona 2", dLat = 0.05))
        val friend = listOf(poly(1, "Zona 1", dLat = 0.10), poly(2, "Comunidade", dLat = 0.2, mode = ZoneMode.BLOCKED))
        val r = ZoneShare.merge(mine, friend)
        assertEquals(4, r.zones.size)
        assertEquals(2, r.added)
        assertEquals("Zona 1", r.zones.first().name)               // a sua Zona 1 continua a principal
        assertEquals(listOf(1L, 2L, 3L, 4L), r.zones.map { it.id }) // numeros novos, sem colidir
        assertEquals("Zona 1 (recebida)", r.zones[2].name)           // nome repetido ganha sufixo
    }

    @Test
    fun mergeSkipsZonesYouAlreadyHave() {
        val mine = listOf(poly(1, "Zona 1"))
        val r = ZoneShare.merge(mine, listOf(poly(7, "Mesma zona do amigo")))
        assertEquals(1, r.zones.size)
        assertEquals(0, r.added)
        assertEquals(1, r.skippedDuplicates)
    }

    @Test
    fun replaceUsesTheFileOrderAndRenumbers() {
        val friend = listOf(poly(10, "Centro"), poly(20, "Penha", dLat = 0.1))
        val r = ZoneShare.replace(friend)
        assertEquals(listOf(1L, 2L), r.zones.map { it.id })
        assertEquals("Centro", r.zones.first().name)
    }

    @Test
    fun brokenZonesAreIgnored() {
        assertFalse(ZoneShare.isValid(poly(1, "x").copy(points = listOf(-22.8 to -43.3, -22.7 to -43.2))))
        assertFalse(ZoneShare.isValid(MapZone(1, "c", ZoneMode.ALLOWED, ZoneShape.CIRCLE, centerLat = 0.0, centerLng = 0.0, radiusMeters = 800.0)))
        assertTrue(ZoneShare.isValid(MapZone(1, "c", ZoneMode.ALLOWED, ZoneShape.CIRCLE, centerLat = -22.8, centerLng = -43.3, radiusMeters = 800.0)))
        assertTrue(ZoneShare.describe(listOf(poly(1, "Zona 1"))).contains("liberada"))
    }
}
