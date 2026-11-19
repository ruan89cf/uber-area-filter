package com.ruan.uberareafilter

import com.ruan.uberareafilter.engine.ParsedRideOffer
import com.ruan.uberareafilter.engine.TrafficCheck
import com.ruan.uberareafilter.model.FilterConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ultima checagem antes de aceitar: tempo com o transito de agora (corrida #36 de 18/11 para Bonsucesso). */
class TrafficCheckTest {

    // #36: R$ 7,40, 1,8 + 2,3 km, a 99 dizia 11 min -> R$ 7,40 / (11 + 2) min = R$ 34/h
    private fun ride36(total: Double = 4.1, dest: String = "Stop Time, Avenida Brasil, 6505 - Bonsucesso") = ParsedRideOffer(
        isRideOffer = true, isContinuePrompt = false, isRatingScreen = false, ratingPassengerName = null,
        isInActiveRide = false, hasRiskAreaBadge = false, allTexts = emptyList(),
        pickupAddress = "Rua Felizardo Fortes, 300, Ramos", dropoffAddress = dest, ridePrice = 7.40,
        pickupDistanceKm = 1.8, dropoffDistanceKm = total - 1.8, totalDistanceKm = total, pricePerKm = 7.40 / total,
        hourlyRate = 7.40 / 13.0 * 60.0, passengerRating = 4.9, isNewPassenger = false, addressCandidates = emptyList(),
        rejectNode = null, rejectTapPoint = null, secondaryRejectTapPoint = null, rejectTapTargets = emptyList(),
        hasNaoAfetaTaPill = false, acceptNode = null, acceptTapPoint = null, continueNode = null,
        continueTapPoint = null, fingerprint = "36"
    )

    private val cfg = FilterConfig(
        filterByHourlyRateEnabled = true, minHourlyRate = 30.0, useNetHourlyRate = false,
        trafficCheckEnabled = true, trafficApiKey = "chave", trafficMinTotalKm = 0.0
    )

    @Test
    fun heavyTrafficDropsTheHourlyBelowTheMinimum() {
        assertEquals(11.0, TrafficCheck.estimatedMinutes(ride36())!!, 0.01)
        // Av. Brasil parada: Google diz 25 min -> R$ 7,40 / 27 min = R$ 16/h
        val v = TrafficCheck.judge(ride36(), 25.0, cfg)
        assertFalse(v.passes)
        assertEquals(16.4, v.hourlyNow!!, 0.1)
        assertTrue(v.summary().contains("Trânsito agora: 25 min (a 99 dizia 11)"))
        // transito normal (10 min): fica com a estimativa da 99 (nunca melhora a corrida) e passa
        val ok = TrafficCheck.judge(ride36(), 10.0, cfg)
        assertTrue(ok.passes)
        assertEquals(34.2, ok.hourlyNow!!, 0.1)
    }

    @Test
    fun whenToCheck() {
        assertTrue(TrafficCheck.shouldCheck(ride36(), cfg))                                   // 0 km = todas
        assertFalse(TrafficCheck.shouldCheck(ride36(), cfg.copy(trafficApiKey = "")))        // sem chave: nao consulta
        assertFalse(TrafficCheck.shouldCheck(ride36(), cfg.copy(trafficCheckEnabled = false)))
        val from25 = cfg.copy(trafficMinTotalKm = 2.5)
        assertTrue(TrafficCheck.shouldCheck(ride36(total = 4.1), from25))
        assertFalse(TrafficCheck.shouldCheck(ride36(total = 2.0, dest = "Rua Uranos, 821, Ramos"), from25))
        // abaixo de 2,5 km, mas com Av. Brasil: confere
        assertTrue(TrafficCheck.shouldCheck(ride36(total = 2.0), from25))
    }

    @Test
    fun googleResponseIsRead() {
        assertEquals(25.0, TrafficCheck.parseDurationMinutes("""{"routes":[{"duration":"1500s"}]}""")!!, 0.001)
        assertNull(TrafficCheck.parseDurationMinutes("""{"error":{"code":403}}"""))
    }

    @Test
    fun awsResponseIsRead() {
        // fim da resposta real da Amazon Location (18/11 21:57, voce -> embarque -> destino, modo moto)
        val json = """{"Routes":[{"Legs":[{"VehicleLegDetails":{"TravelSteps":[{"Distance":71,"Duration":22}]}}],""" +
            """"MajorRoadLabels":[{"RoadName":{"Language":"pt","Value":"Avenida Brasil"}}],"Summary":{"Distance":5074,"Duration":900}}]}"""
        assertEquals(15.0, TrafficCheck.parseAwsDurationMinutes(json)!!, 0.001)
        assertNull(TrafficCheck.parseAwsDurationMinutes("""{"message":"Forbidden"}"""))
        assertTrue(TrafficCheck.isAwsKey("v1.public.eyJqdGkiOi..."))
        assertFalse(TrafficCheck.isAwsKey("AIzaSyExemplo"))
    }
}
