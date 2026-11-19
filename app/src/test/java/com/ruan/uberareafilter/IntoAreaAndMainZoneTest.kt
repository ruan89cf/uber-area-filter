package com.ruan.uberareafilter

import com.ruan.uberareafilter.engine.AreaEvaluator
import com.ruan.uberareafilter.engine.Decision
import com.ruan.uberareafilter.engine.ParsedRideOffer
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.model.MapZone
import com.ruan.uberareafilter.model.ZoneMode
import com.ruan.uberareafilter.model.ZoneShape
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntoAreaAndMainZoneTest {

    private fun offer(pickup: String, dropoff: String) = ParsedRideOffer(
        isRideOffer = true, isContinuePrompt = false, isRatingScreen = false, ratingPassengerName = null,
        isInActiveRide = false, hasRiskAreaBadge = false, allTexts = emptyList(),
        pickupAddress = pickup, dropoffAddress = dropoff, ridePrice = 15.0,
        pickupDistanceKm = 1.8, dropoffDistanceKm = 4.0, totalDistanceKm = 5.8, pricePerKm = 2.6,
        hourlyRate = 40.0, passengerRating = 4.9, isNewPassenger = false, addressCandidates = emptyList(),
        rejectNode = null, rejectTapPoint = null, secondaryRejectTapPoint = null, rejectTapTargets = emptyList(),
        hasNaoAfetaTaPill = false, acceptNode = null, acceptTapPoint = null, continueNode = null,
        continueTapPoint = null, fingerprint = "t"
    )

    private val zone1 = MapZone(
        id = 1L, name = "Zona 1", mode = ZoneMode.ALLOWED, shape = ZoneShape.CIRCLE,
        centerLat = -22.84, centerLng = -43.26, radiusMeters = 800.0, streetNames = listOf("Rua Pirangi")
    )
    private val zone2 = MapZone(
        id = 2L, name = "Zona 2", mode = ZoneMode.ALLOWED, shape = ZoneShape.CIRCLE,
        centerLat = -22.86, centerLng = -43.25, radiusMeters = 800.0, streetNames = listOf("Rua Uranos")
    )

    private fun cfg(vararg zones: MapZone, into: Boolean = true, inter: Boolean = false) = FilterConfig(
        mapZones = zones.toList(),
        trustedStreets = listOf("Rua Pirangi", "Rua Uranos"),
        requireBothPickupAndDropoff = true,
        acceptRidesIntoArea = into,
        allowInterZoneRides = inter,
        filterByHourlyRateEnabled = false,
        filterByPickupDistEnabled = false,
        filterByRatingEnabled = false,
        fuelCostEnabled = false
    )

    @Test
    fun pickupOutsideDestinationInsideIsAccepted() {
        val res = runBlocking { AreaEvaluator().evaluate(offer("Rua Muito Distante, 10", "Rua Pirangi, 120"), cfg(zone1)) }
        assertEquals(res.reason, Decision.INSIDE_AREA, res.decision)
        assertTrue(res.checklistText, res.checklistText.contains("✔ Embarque fora, leva p/ área"))
    }

    @Test
    fun pickupOutsideDestinationInsideRejectedWhenOptionOff() {
        val res = runBlocking { AreaEvaluator().evaluate(offer("Rua Muito Distante, 10", "Rua Pirangi, 120"), cfg(zone1, into = false)) }
        assertTrue(res.reason, res.decision != Decision.INSIDE_AREA)
    }

    @Test
    fun goingBackToMainZoneIsNotInterZone() {
        // Motorista na Zona 2 voltando para casa (Zona 1): aceita como dentro
        val inZone2 = -22.86 to -43.25
        val res = runBlocking { AreaEvaluator().evaluate(offer("Rua Uranos, 50", "Rua Pirangi, 120"), cfg(zone1, zone2), driverPoint = inZone2) }
        assertEquals(res.reason, Decision.INSIDE_AREA, res.decision)
    }

    @Test
    fun outAndBackFromMainZoneAsksTheDriver() {
        // Caso de 19/11 10:14: motorista na Zona 1, embarque na Zona 2, destino na Zona 1 -> card roxo, voce decide
        val inZone1 = -22.84 to -43.26
        val res = runBlocking { AreaEvaluator().evaluate(offer("Rua Uranos, 50", "Rua Pirangi, 120"), cfg(zone1, zone2), driverPoint = inZone1) }
        assertEquals(res.reason, Decision.INTER_ZONE, res.decision)
        assertTrue(res.reason, res.reason.contains("Ida e volta"))
        // Sem GPS: na duvida, tambem pergunta
        val noGps = runBlocking { AreaEvaluator().evaluate(offer("Rua Uranos, 50", "Rua Pirangi, 120"), cfg(zone1, zone2)) }
        assertEquals(noGps.reason, Decision.INTER_ZONE, noGps.decision)
    }

    @Test
    fun inActiveRidePickupOutsideZoneIsNotAccepted() {
        // Caso de 19/11 09:12: em corrida, embarque fora (estacao do outro lado da passarela), destino dentro
        val res = runBlocking { AreaEvaluator().evaluate(offer("Rua Muito Distante, 10", "Rua Pirangi, 120"), cfg(zone1), inActiveRide = true) }
        assertTrue(res.reason, res.decision != Decision.INSIDE_AREA)
    }

    @Test
    fun leavingMainZoneIsInterZoneAndRejectedWhenDisabled() {
        val res = runBlocking { AreaEvaluator().evaluate(offer("Rua Pirangi, 120", "Rua Uranos, 50"), cfg(zone1, zone2)) }
        assertEquals(res.reason, Decision.OUTSIDE_AREA, res.decision)
        assertTrue(res.reason, res.reason.contains("Entre zonas"))
    }
}
