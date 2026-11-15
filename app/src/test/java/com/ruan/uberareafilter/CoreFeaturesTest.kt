package com.ruan.uberareafilter

import com.ruan.uberareafilter.driver.UberCaptureModule
import com.ruan.uberareafilter.engine.FuelCalculator
import com.ruan.uberareafilter.map.OpenFreeMapStreetFetcher
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.model.MapZone
import com.ruan.uberareafilter.model.ZoneMode
import com.ruan.uberareafilter.model.ZoneShape
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreFeaturesTest {

    private val module = UberCaptureModule()

    @Test
    fun parsesNewUberPopupWithParenthesesAndBonusAndEstKm() {
        val lines = listOf(
            "Moto",
            "R$ 11,06",
            "Est. de R$0,84/km",
            "★ 4,91 (380)",
            "Verificado",
            "+R$ 2,25 incluído",
            "12 min (7.1 km)",
            "Avenida Pastor Martin Luther King Junior,",
            "Inhaúma, Rio de Janeiro",
            "9 minutos (6.1 km)",
            "Rua Leopoldina Rego, 212, Olaria, Rio de",
            "Janeiro",
            "Tenho interesse"
        ).map { it to null }

        val parsed = module.parseFromOrderedLines(lines, 1080, 2400)
        assertNotNull(parsed)
        assertTrue(parsed!!.isRideOffer)
        assertEquals("UBER", parsed.appSource)
        assertEquals(11.06, parsed.ridePrice!!, 0.01)
        assertEquals(7.1, parsed.pickupDistanceKm!!, 0.05)
        assertEquals(6.1, parsed.dropoffDistanceKm!!, 0.05)
        assertEquals(13.2, parsed.totalDistanceKm!!, 0.05)
        assertEquals(4.91, parsed.passengerRating!!, 0.01)
        assertTrue(parsed.pickupAddress!!.contains("Martin Luther King"))
        assertTrue(parsed.dropoffAddress!!.contains("Leopoldina Rego"))
        // Total minutos = 12 + 9 + 2 (espera) = 23 min -> R$/h = 11.06 / 23 * 60 = 28.85
        assertEquals(28.85, parsed.hourlyRate!!, 0.2)
    }

    @Test
    fun parsesUberIntegerPriceCardWithParentheses() {
        val lines = listOf(
            "Moto",
            "R$ 25",
            "Est. de R$1,02/km",
            "★ 4,96 (785)",
            "Verificado",
            "12 min (6.1 km)",
            "Rua Cuba, Penha, Rio de Janeiro",
            "21 minutos (18.3 km)",
            "Rua Galvão Filho, 42, Belford Roxo, Belford",
            "Roxo",
            "Tenho interesse"
        ).map { it to null }

        val parsed = module.parseFromOrderedLines(lines, 1080, 2400)
        assertNotNull(parsed)
        assertEquals(25.0, parsed!!.ridePrice!!, 0.01)
        assertEquals(6.1, parsed.pickupDistanceKm!!, 0.05)
        assertEquals(18.3, parsed.dropoffDistanceKm!!, 0.05)
        assertFalse(parsed.hasMultipleStops)
    }

    @Test
    fun parsesUberExclusiveCardWithBulletAndMultiStop() {
        val lines = listOf(
            "Moto",
            "Exclusivo",
            "R$ 14,09",
            "6 min • 2.5 km",
            "Rua Pirangi, Olaria",
            "★ 4,94 (211)",
            "1 parada",
            "20 minutos • 11.0 km",
            "Estrada do Dendê, 126, Tauá",
            "Aceitar"
        ).map { it to null }

        val parsed = module.parseFromOrderedLines(lines, 1080, 2400)
        assertNotNull(parsed)
        assertEquals(14.09, parsed!!.ridePrice!!, 0.01)
        assertTrue(parsed.hasMultipleStops)
        assertEquals(1, parsed.stopsCount)
        assertTrue(parsed.pickupAddress!!.contains("Pirangi"))
    }

    @Test
    fun fuelCalculatorComputesExactFuelCostAndNetProfit() {
        val cfg = FilterConfig(
            fuelCostEnabled = true,
            fuelType = FilterConfig.FUEL_GASOLINA,
            fuelPricePerUnit = 6.29,
            kmPerUnit = 35.0
        )
        // Em 6.7 km a 35 km/L -> 0.1914 L * 6.29 = R$ 1.204
        val est = FuelCalculator.estimate(6.7, 18.90, 42.0, cfg)
        assertNotNull(est)
        assertEquals(1.20, est!!.cost, 0.02)
        assertTrue(est.costText.contains("de gasolina"))
        assertTrue(est.costText.contains("1,20"))
        assertEquals(17.70, est.netProfit!!, 0.02)
    }

    @Test
    fun liveOpenFreeMapExtractsRuaPirangiInOlariaZone() = runBlocking {
        val zone = MapZone(
            id = 1L,
            name = "Olaria",
            mode = ZoneMode.ALLOWED,
            shape = ZoneShape.CIRCLE,
            centerLat = -22.839317,
            centerLng = -43.257107,
            radiusMeters = 600.0
        )
        val streets = OpenFreeMapStreetFetcher.fetchStreets(listOf(zone))
        println("OpenFreeMap extracted ${streets.size} streets around Rua Pirangi")
        assertTrue("Expected > 20 streets, got ${streets.size}", streets.size > 20)
        assertTrue("Expected Rua Pirangi in $streets", streets.any { it.contains("Pirangi", ignoreCase = true) })
    }
}
