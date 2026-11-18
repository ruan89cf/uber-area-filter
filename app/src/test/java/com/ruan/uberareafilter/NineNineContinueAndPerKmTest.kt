package com.ruan.uberareafilter

import com.ruan.uberareafilter.driver.NineNineCaptureModule
import com.ruan.uberareafilter.engine.AreaEvaluator
import com.ruan.uberareafilter.engine.Decision
import com.ruan.uberareafilter.engine.ParsedRideOffer
import com.ruan.uberareafilter.engine.RideOfferParser
import com.ruan.uberareafilter.model.FilterConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NineNineContinueAndPerKmTest {

    // Tela real do log de 17/11 (09:01:32): aviso da 99 por baixo do NOSSO card ("RECUSANDO", "Lucro R$"...)
    private val continueScreen = listOf(
        "09:01 9 MIM",
        "Várias solicitacÕes seguidas recUsadas",
        "X Fora dos critérios 99",
        "RECUSANDO",
        "31,00",
        "R$ 1,37/km : 22,6 km ★ 4,94",
        "R$ 3,12 de gasolina",
        "Lucro R$ 27,88",
        "X Emb 2,6km (máx 2,5)",
        "Continuar aceitando solicitações",
        "Desconectar"
    ).map { it to null }

    @Test
    fun detectsContinuePromptEvenWithOwnCardOnTop() {
        val parsed = NineNineCaptureModule().parseOrderedLines(continueScreen, null, 1220, 2712, FilterConfig())
        assertTrue(parsed != null && parsed.isContinuePrompt)
        assertFalse(parsed!!.isRideOffer)
    }

    @Test
    fun continueTitleToleratesOcrNoise() {
        assertTrue(RideOfferParser.isContinuePromptTitle("varias solicitacoes seauidas recusadas"))
        assertFalse(RideOfferParser.isContinuePromptTitle("as solicitacoes seguidas estao ativadas"))
        assertTrue(RideOfferParser.isContinueButtonText("continuar aceitando solicitacoes"))
        assertFalse(RideOfferParser.isContinueButtonText("aceitando"))
    }

    private fun offer(price: Double, km: Double) = ParsedRideOffer(
        isRideOffer = true, isContinuePrompt = false, isRatingScreen = false, ratingPassengerName = null,
        isInActiveRide = false, hasRiskAreaBadge = false, allTexts = emptyList(),
        pickupAddress = null, dropoffAddress = null, ridePrice = price,
        pickupDistanceKm = null, dropoffDistanceKm = km, totalDistanceKm = km, pricePerKm = price / km,
        hourlyRate = null, passengerRating = null, isNewPassenger = false, addressCandidates = emptyList(),
        rejectNode = null, rejectTapPoint = null, secondaryRejectTapPoint = null, rejectTapTargets = emptyList(),
        hasNaoAfetaTaPill = false, acceptNode = null, acceptTapPoint = null, continueNode = null,
        continueTapPoint = null, fingerprint = "t"
    )

    private val perKmCfg = FilterConfig(
        filterByPricePerKmEnabled = true,
        minPricePerKm = 1.20,
        filterByHourlyRateEnabled = false,
        filterByPickupDistEnabled = false,
        fuelCostEnabled = false
    )

    @Test
    fun rejectsBelowMinPricePerKm() {
        val res = runBlocking { AreaEvaluator().evaluate(offer(10.0, 10.0), perKmCfg) }
        assertEquals(Decision.OUTSIDE_AREA, res.decision)
        assertTrue(res.checklistText.contains("✘ R$1,00/km (mín 1,20)"))
    }

    @Test
    fun keepsAtOrAboveMinPricePerKm() {
        val res = runBlocking { AreaEvaluator().evaluate(offer(12.0, 10.0), perKmCfg) }
        assertEquals(Decision.UNKNOWN_KEEP, res.decision)
    }

    @Test
    fun perKmFilterOffByDefault() {
        assertFalse(FilterConfig().filterByPricePerKmEnabled)
        assertFalse(FilterConfig().zoneCardColorsEnabled)
        val cfg = perKmCfg.copy(filterByPricePerKmEnabled = false)
        val res = runBlocking { AreaEvaluator().evaluate(offer(5.0, 10.0), cfg) }
        assertEquals(Decision.UNKNOWN_KEEP, res.decision)
    }
}
