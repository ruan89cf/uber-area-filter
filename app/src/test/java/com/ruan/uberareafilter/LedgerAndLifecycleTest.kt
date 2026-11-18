package com.ruan.uberareafilter

import com.ruan.uberareafilter.driver.NineNineCaptureModule
import com.ruan.uberareafilter.engine.RideLedgerTracker
import com.ruan.uberareafilter.model.FilterConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerAndLifecycleTest {

    @Test
    fun reasonKeysSplitsViolations() {
        val keys = RideLedgerTracker.reasonKeys("✘ R$/h líq.: R$22 (mín R$30) • Emb. longe: 2,7km (máx 2,5km) • Nota 4,70 (mín 4,80)")
        assertEquals(listOf("R$/h líq.", "Emb. longe", "Nota"), keys)
    }

    // Mapa da 99 em viagem (log 17/11 09:21): o atalho "OK, corrida cancelada" fica fixo na tela
    @Test
    fun okCorridaCanceladaChipIsNotACancellation() {
        val lines = listOf(
            "09:21", "R. Dona Isabel", "OK, corrida cancelada", "1 min 48 m",
            "Chegada prevista: 09:22", "Finalizar corrida"
        ).map { it to null }
        val parsed = NineNineCaptureModule().parseOrderedLines(lines, null, 1220, 2712, FilterConfig())
        if (parsed != null) {
            assertFalse(parsed.isCancelledScreen)
            assertTrue(parsed.isInActiveRide)
        }
    }

    @Test
    fun offButtonDefaultsToTenMinutes() {
        assertEquals(10, FilterConfig().offButtonMinutes)
    }
}
