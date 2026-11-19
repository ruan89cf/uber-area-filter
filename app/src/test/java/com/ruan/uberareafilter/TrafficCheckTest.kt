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
        assertTrue(TrafficCheck.shouldCheck(ride36(), cfg.copy(trafficApiKey = "")))         // sem chave: vai pelo servidor
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

    @Test
    fun realKmCatchesThePasserelaDetour() {
        // A 99 diz busca de 1,8 km (a pe pela passarela); de moto/carro a volta real e 3,6 km
        val v = TrafficCheck.judge(ride36(), 12.0, cfg, pickupKm = 3.6, rideKm = 2.3)
        assertFalse(v.passes)
        assertTrue(v.summary(), v.summary().contains("Busca real 3,6 km (a 99 dizia 1,8): passarela ou volta"))
        // Rota real parecida com a da 99: passa e mostra os km reais
        val ok = TrafficCheck.judge(ride36(), 11.0, cfg, pickupKm = 1.9, rideKm = 2.3)
        assertTrue(ok.passes)
        assertTrue(ok.okSummary(), ok.okSummary().contains("4,2 km reais"))
    }

    @Test
    fun realKmAboveMaxPickupOrBelowPricePerKmFails() {
        val withPickup = cfg.copy(filterByPickupDistEnabled = true, maxPickupDistanceKm = 2.5)
        // 15:25 de 19/11: busca real 2,1 km com maximo 2,0 -> passa (folga de 0,5 km para GPS/ponto do endereco)
        assertTrue(TrafficCheck.judge(ride36(), 11.0, withPickup.copy(maxPickupDistanceKm = 2.0), pickupKm = 2.1, rideKm = 2.3).passes)
        val far = TrafficCheck.judge(ride36(), 11.0, withPickup, pickupKm = 3.1, rideKm = 2.3)
        assertFalse(far.passes)
        assertTrue(far.summary(), far.summary().contains("Busca real 3,1 km (máx 2,5)"))
        // R$/km com os km reais: R$ 7,40 / 6,0 km = R$ 1,23 < R$ 1,50
        val perKm = cfg.copy(filterByPricePerKmEnabled = true, minPricePerKm = 1.50, filterByPickupDistEnabled = false)
        val low = TrafficCheck.judge(ride36(), 11.0, perKm, pickupKm = 2.0, rideKm = 4.0)
        assertFalse(low.passes)
        assertEquals(1.23, low.offerNow.pricePerKm!!, 0.01)
        // Rota real menor que a da 99 nunca melhora a corrida
        val shorter = TrafficCheck.judge(ride36(), 11.0, cfg, pickupKm = 1.0, rideKm = 1.0)
        assertEquals(4.1, shorter.offerNow.totalDistanceKm!!, 0.001)
    }

    @Test
    fun awsLegKmAreRead() {
        val json = """{"Routes":[{"Legs":[{"VehicleLegDetails":{"Summary":{"Overview":{"BestCaseDuration":295,"Distance":2029,"Duration":304}}}},""" +
            """{"VehicleLegDetails":{"Summary":{"Overview":{"Distance":814,"Duration":125}}}}],"Summary":{"Distance":2843,"Duration":429}}]}"""
        assertEquals(listOf(2.029, 0.814), TrafficCheck.parseAwsLegKm(json))
        assertEquals(7.15, TrafficCheck.parseAwsDurationMinutes(json)!!, 0.01)
    }
}
