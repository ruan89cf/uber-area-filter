package com.ruan.uberareafilter

import com.ruan.uberareafilter.driver.AppCaptureRouter
import com.ruan.uberareafilter.driver.CaptureMode
import com.ruan.uberareafilter.driver.UberCaptureModule
import com.ruan.uberareafilter.engine.AddressParser
import com.ruan.uberareafilter.engine.AreaEvaluator
import com.ruan.uberareafilter.engine.AreaVerdictKind
import com.ruan.uberareafilter.engine.DriverFix
import com.ruan.uberareafilter.engine.FixPrecision
import com.ruan.uberareafilter.engine.GeoCandidate
import com.ruan.uberareafilter.engine.GeoCandidateRules
import com.ruan.uberareafilter.engine.GeoFix
import com.ruan.uberareafilter.engine.GeoMath
import com.ruan.uberareafilter.engine.LocationRules
import com.ruan.uberareafilter.engine.StreetEvidence
import com.ruan.uberareafilter.engine.StreetKnowledge
import com.ruan.uberareafilter.engine.Decision
import com.ruan.uberareafilter.engine.DualRideCoordinator
import com.ruan.uberareafilter.engine.FuelCalculator
import com.ruan.uberareafilter.engine.RideOfferParser
import com.ruan.uberareafilter.model.DualConflictPolicy
import com.ruan.uberareafilter.map.OpenFreeMapStreetFetcher
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.model.MapZone
import com.ruan.uberareafilter.model.RideHistoryItem
import com.ruan.uberareafilter.model.ZoneMode
import com.ruan.uberareafilter.model.ZoneShape
import com.ruan.uberareafilter.model.ZoneStreetIndex
import com.ruan.uberareafilter.overlay.CardPalette
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertTrue(parsed.dropoffAddress!!.contains("Estrada do Dendê"))
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
    fun recoversLeg2WhenOcrReadsLookalikeDigitsOrSplitsLines() {
        val lines = listOf(
            "o Moto",
            "R$ 14,43",
            "Est. de R$1,41/km",
            "★ 4,67 (18)",
            "+R$ 3,00 incluído",
            "6 min (2.6 km)",
            "R. Teixeira Franco, Ramos, Rio de Janeiro",
            "ll minutos (7.6 km)",
            "R. São Luiz Gonzaga, 1612, Benfica, Rio de",
            "Janeiro",
            "Tenho interesse"
        ).map { it to null }

        val parsed = module.parseFromOrderedLines(lines, 1220, 2712)
        assertNotNull(parsed)
        assertEquals(14.43, parsed!!.ridePrice!!, 0.01)
        assertEquals(2.6, parsed.pickupDistanceKm!!, 0.05)
        assertEquals(7.6, parsed.dropoffDistanceKm!!, 0.05)
        assertEquals(10.2, parsed.totalDistanceKm!!, 0.05)
        assertTrue(parsed.pickupAddress!!.contains("Teixeira Franco"))
        assertTrue(parsed.dropoffAddress!!.contains("Benfica"))
        // Total min = 6 + 11 + 2 = 19 -> 14.43 / 19 * 60 = 45.57 (~R$ 46/h, never R$ 105/h)
        assertEquals(45.57, parsed.hourlyRate!!, 0.3)
    }

    @Test
    fun recoversDropoffDistanceAndAddressFromEstPerKmWhenLeg2HeaderMissing() {
        val lines = listOf(
            "Moto",
            "R$ 14,43",
            "Est. de R$1,41/km",
            "4,67 (18)",
            "+R$ 3,00 incluído",
            "6 min (2.6 km)",
            "R. Teixeira Franco, Ramos, Rio de Janeiro",
            "R. São Luiz Gonzaga, 1612, Benfica, Rio de",
            "Janeiro",
            "Tenho interesse"
        ).map { it to null }

        val parsed = module.parseFromOrderedLines(lines, 1220, 2712)
        assertNotNull(parsed)
        assertEquals(2.6, parsed!!.pickupDistanceKm!!, 0.05)
        assertEquals(7.6, parsed.dropoffDistanceKm!!, 0.15)
        assertEquals(10.2, parsed.totalDistanceKm!!, 0.2)
        assertTrue(parsed.pickupAddress!!.contains("Teixeira Franco"))
        assertTrue(parsed.dropoffAddress!!.contains("Benfica"))
        assertTrue("Hourly rate should be ~R$ 40/h instead of inflated R$ 105/h, was ${parsed.hourlyRate}", parsed.hourlyRate!! < 50.0)
    }

    @Test
    fun separateUberHourlyRateOverridesDefaultOnlyForUber() {
        val cfg = FilterConfig(
            minHourlyRate = 30.0,
            uberCustomHourlyEnabled = true,
            uberMinHourlyRate = 45.0
        )
        assertEquals(30.0, cfg.effectiveMinHourlyRate("99"), 0.01)
        assertEquals(45.0, cfg.effectiveMinHourlyRate("UBER"), 0.01)
        assertEquals(30.0, cfg.copy(uberCustomHourlyEnabled = false).effectiveMinHourlyRate("UBER"), 0.01)
    }

    @Test
    fun uberModuleNeverHijacksNineNineEscolherCarouselCard() {
        val lines = listOf(
            "Moto Nova",
            "R$18,10",
            "100% dos ganhos",
            "4,91 • 152 corridas",
            "(12 min 5,7 km) Unidos do Jacarezinho,",
            "Jacarezinho",
            "(18 min 16,6 km) Rua Central, 2 , Pitangueiras",
            "Escolher"
        ).map { it to null }

        val parsed = module.parseFromOrderedLines(lines, 1220, 2712)
        org.junit.Assert.assertNull("99 Escolher carousel must NEVER be parsed as UBER", parsed)
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

    @Test
    fun fixesMixedOcr1lAnd21lAndInlineLegSplitFromRealLogs() {
        val lines = listOf(
            "Moto",
            "R$ 12,49",
            "Est. de R$1,58/km",
            "★ 4,91 (120)",
            "5 min (2.4 km)",
            "R. Bonsucesso, Bonsucesso, Rio deJaneiro b 1l minutos (5.5 km)",
            "Rua Monsenhor Alves Rocha, 81, Penha, Rio de Janeiro",
            "Aceitar"
        ).map { it to null }

        val parsed = module.parseFromOrderedLines(lines, 1220, 2712)
        assertNotNull(parsed)
        assertEquals(2.4, parsed!!.pickupDistanceKm!!, 0.05)
        assertEquals(5.5, parsed.dropoffDistanceKm!!, 0.05)
        assertEquals(7.9, parsed.totalDistanceKm!!, 0.05)
        assertEquals("R. Bonsucesso, Bonsucesso, Rio deJaneiro", parsed.pickupAddress)
        assertTrue(parsed.dropoffAddress!!.contains("Monsenhor Alves Rocha"))
        // 5 + 11 + 2 = 18 min -> 12.49 / 18 * 60 = 41.63
        assertEquals(41.63, parsed.hourlyRate!!, 0.3)

        val leg2With21l = module.normalizeLegOcrNoise("b 23 minutos (21.l km)")
        assertEquals("23 minutos (21.1 km)", leg2With21l)
    }

    @Test
    fun enforcesRealisticMinimumDrivingTimeFloorOnUberAbsurdShortMinutes() {
        // Caso real da imagem 58159.png: 4 min (1.2 km) + 8 minutos (12.6 km) -> 8 min em 12.6 km e fisicamente impossivel
        val lines = listOf(
            "Moto",
            "R$ 14,94",
            "★ 4,90 (50)",
            "4 min (1.2 km)",
            "Rua A, Ramos, Rio de Janeiro",
            "8 minutos (12.6 km)",
            "Rua B, Centro, Rio de Janeiro",
            "Aceitar"
        ).map { it to null }

        val parsed = module.parseFromOrderedLines(lines, 1220, 2712)
        assertNotNull(parsed)
        // Sem piso: (4 + 8 + 2) = 14 min -> R$ 64/h absurdos.
        // Com piso 1.4 min/km: max(4, 1.68) + max(8, 17.64) + 2 = 23.64 min -> ~R$ 37.9/h realista!
        assertTrue("Hourly rate must apply distance time floor (< 42/h), was ${parsed!!.hourlyRate}", parsed.hourlyRate!! < 42.0)
    }

    @Test
    fun configDefaultsSupportInterZoneAutoAcceptAndBannerControls() {
        val cfg = FilterConfig()
        assertFalse(cfg.autoAcceptInterZone)
        assertTrue(cfg.showRideInfoBannerEnabled)
        assertEquals(5, cfg.bannerAutoHideSeconds)
    }

    @Test
    fun fetchStreetsPerZoneMapsEachZoneBoxSeparately() = runBlocking {
        val zoneA = MapZone(
            id = 101L,
            name = "Olaria",
            mode = ZoneMode.ALLOWED,
            shape = ZoneShape.CIRCLE,
            centerLat = -22.839317,
            centerLng = -43.257107,
            radiusMeters = 450.0
        )
        val zoneB = MapZone(
            id = 202L,
            name = "Bonsucesso",
            mode = ZoneMode.ALLOWED,
            shape = ZoneShape.CIRCLE,
            centerLat = -22.8635,
            centerLng = -43.2535,
            radiusMeters = 450.0
        )
        val perZone = OpenFreeMapStreetFetcher.fetchStreetsPerZone(listOf(zoneA, zoneB))
        assertTrue((perZone[101L]?.size ?: 0) > 5)
        assertTrue((perZone[202L]?.size ?: 0) > 5)
    }

    @Test
    fun recoversMissingDecimalDotInUberLegDistanceUsingEstPerKm() {
        // Caso real do video XRecorder_20261115_04.mp4 (01:00): OCR leu "71 km" em vez de "7.1 km", gerando 76.0 km
        val module = UberCaptureModule()
        val lines = listOf(
            "Moto",
            "R$ 13,06",
            "Est. de R$1,08/km",
            "4,74 (229)",
            "9 min (5.0 km)",
            "Rua Montevidéu 782, Penha, Rio de Janeiro",
            "8 minutos (71 km)",
            "Pinheiro, Maré, Rio de Janeiro",
            "Tenho interesse"
        ).map { it to null }

        val parsed = module.parseFromOrderedLines(lines, 1220, 2712)
        assertNotNull(parsed)
        assertEquals(5.0, parsed!!.pickupDistanceKm!!, 0.05)
        assertEquals(7.1, parsed.dropoffDistanceKm!!, 0.05)
        assertEquals(12.1, parsed.totalDistanceKm!!, 0.05)
        assertEquals(1.08, parsed.pricePerKm!!, 0.03)
    }

    @Test
    fun buildsValidSelfSignedX509CertificateWithoutBouncyCastle() {
        val kpg = java.security.KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val cert = com.ruan.uberareafilter.adb.StandaloneAdbBridge.buildSelfSignedX509Certificate(kp)
        cert.verify(kp.public)
        val x509 = cert as java.security.cert.X509Certificate
        assertTrue(x509.subjectX500Principal.name.contains("FiltroInteligente"))
    }

    @Test
    fun parses58407NineNineCardWithMetersAndTaxa99InDualAndSingleMode() {
        // Caso real da imagem 58407.png (09:30) e log 09:30:07 que falhava por causa de "2 min (584 m)" + "Taxa99"
        val lines = listOf(
            "Moto",
            "R$6,80",
            "R$41,21/hora",
            "0% de Taxa99 semanal",
            "R$0,84 Tarifa base dinâmica incl.",
            "4,77",
            "+999 corridas",
            "CPF e Cartão verif.",
            "2 min (584 m)",
            "CAPS III Fernando Diniz, Rua Leopoldina",
            "Rego, 754 - Penha",
            "8 min (4,3 km)",
            "Rua Fernandes Valdez, 67 , Higienópolis",
            "Aceitar"
        ).map { it to null }

        val dualCfg = FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = true)
        val dualParsed = AppCaptureRouter.parseOrderedLines(lines, null, 1220, 2712, dualCfg, preferredAppSource = "99")
        assertNotNull(dualParsed)
        assertTrue(dualParsed!!.isRideOffer)
        assertEquals("99", dualParsed.appSource)
        assertEquals(6.80, dualParsed.ridePrice!!, 0.01)
        assertEquals(0.584, dualParsed.pickupDistanceKm!!, 0.005)
        assertEquals(4.3, dualParsed.dropoffDistanceKm!!, 0.05)
        assertTrue(dualParsed.pickupAddress!!.contains("Penha"))
        assertTrue(dualParsed.dropoffAddress!!.contains("Higien"))

        val single99Cfg = FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = false)
        assertEquals(CaptureMode.SINGLE_99, CaptureMode.from(single99Cfg))
        val singleParsed = AppCaptureRouter.parseOrderedLines(lines, null, 1220, 2712, single99Cfg)
        assertNotNull(singleParsed)
        assertEquals("99", singleParsed!!.appSource)
    }

    @Test
    fun parses58409NineNineCardWithPerfilEssencialAndTaxa99InDualMode() {
        // Caso real da imagem 58409.png (09:29) e log 09:29:21
        val lines = listOf(
            "Moto",
            "R$5,10",
            "R$31,33/hora",
            "0% de Taxa99 semanal",
            "R$1,02 Tarifa base dinâmica incl.",
            "4,91 • 706 corridas",
            "Perfil Essencial",
            "6 min (2,4 km)",
            "Igreja Universal, Avenida Nossa Sra. da",
            "Penha, 497 - Penha",
            "4 min (1,9 km)",
            "Rua Leopoldina Rego, 662, Olaria",
            "Aceitar"
        ).map { it to null }

        val dualCfg = FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = true)
        val parsed = AppCaptureRouter.parseOrderedLines(lines, null, 1220, 2712, dualCfg)
        assertNotNull(parsed)
        assertTrue(parsed!!.isRideOffer)
        assertEquals("99", parsed.appSource)
        assertEquals(5.10, parsed.ridePrice!!, 0.01)
        assertEquals(2.4, parsed.pickupDistanceKm!!, 0.05)
        assertEquals(1.9, parsed.dropoffDistanceKm!!, 0.05)
        assertEquals(4.91, parsed.passengerRating!!, 0.01)
    }

    @Test
    fun specializesSingle99AndSingleUberWithoutCrossInterference() {
        val uberOnlyCfg = FilterConfig(nineNineModuleEnabled = false, uberModuleEnabled = true)
        val nineOnlyCfg = FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = false)
        assertEquals(CaptureMode.SINGLE_UBER, CaptureMode.from(uberOnlyCfg))
        assertEquals(CaptureMode.SINGLE_99, CaptureMode.from(nineOnlyCfg))

        val uberLines = listOf(
            "Moto",
            "Exclusivo",
            "R$ 9,53",
            "Est. de R$2,38/km",
            "★ 4,85 (120)",
            "5 min (1.4 km)",
            "Rua Ismael da Rocha, Ramos, Rio de Janeiro",
            "7 min (2.6 km)",
            "R. Cardoso de Moraes, 328, Rio de Janeiro",
            "Aceitar"
        ).map { it to null }

        val nineLines = listOf(
            "Moto",
            "R$7,00",
            "R$40,00/hora",
            "0% de Taxa99 semanal",
            "4,90 • 310 corridas",
            "4 min (1,2 km)",
            "Rua Santiago, 215 - Penha",
            "6 min (2,1 km)",
            "Rua Quito, 424 - Penha",
            "Aceitar"
        ).map { it to null }

        // Em SINGLE_UBER, oferta 99 e ignorada e oferta Uber funciona
        assertTrue(AppCaptureRouter.parseOrderedLines(nineLines, null, 1220, 2712, uberOnlyCfg) == null)
        val uberRes = AppCaptureRouter.parseOrderedLines(uberLines, null, 1220, 2712, uberOnlyCfg)
        assertNotNull(uberRes)
        assertEquals("UBER", uberRes!!.appSource)

        // Em SINGLE_99, oferta Uber e ignorada e oferta 99 com "Est. de Rio..." nao sofre falso bloqueio
        assertTrue(AppCaptureRouter.parseOrderedLines(uberLines, null, 1220, 2712, nineOnlyCfg) == null)
        val nineWithEstStreet = listOf(
            "Moto",
            "R$8,50",
            "R$44,00/hora",
            "0% de Taxa99 semanal",
            "4,92 • 400 corridas",
            "4 min (1,1 km)",
            "Est. de Rio do Pau, 220, Penha",
            "6 min (2,3 km)",
            "Rua Quito, 100, Penha",
            "Aceitar"
        ).map { it to null }
        val streetRes = AppCaptureRouter.parseOrderedLines(nineWithEstStreet, null, 1220, 2712, dualConfigWithBoth())
        assertNotNull(streetRes)
        assertEquals("99", streetRes!!.appSource)
    }

    @Test
    fun avoidsCorruptedHugeMinutesExplosionUsingCardHourlyRate() {
        val lines = listOf(
            "Moto",
            "R$6,90",
            "R$42,75/hora",
            "0% de Taxa99 semanal",
            "4,95 • 883 corridas",
            "180 min (1,6 km)",
            "Rua Eng. Edmundo, 632 - Olaria",
            "10 min (3,4 km)",
            "Avenida Lobo Junior, 1502 - Penha Circular",
            "Aceitar"
        ).map { it to null }

        val parsed = AppCaptureRouter.parseOrderedLines(lines, null, 1220, 2712, dualConfigWithBoth())
        assertNotNull(parsed)
        assertEquals("99", parsed!!.appSource)
        assertTrue("Hourly rate should not drop to R$2/h, was ${parsed.hourlyRate}", parsed.hourlyRate!! >= 30.0)
    }

    @Test
    fun calculatesNetHourlyRateAndOptionalFloorRoundingAccurately() {
        // Corrida: R$ 15,00, 10 km total, 20 min total (R$ 45,00/h bruto)
        // Gasolina: R$ 6,29/L, 35 km/L -> 10/35 * 6,29 = R$ 1,7971 -> Lucro R$ 13,2029 -> R$/h liq = 45 * (13.2029/15) = R$ 39,6086/h
        val offer = AppCaptureRouter.parseOrderedLines(
            listOf(
                "Moto",
                "R$ 15,00",
                "R$ 45,00/hora",
                "0% de Taxa99 semanal",
                "4,92 • 400 corridas",
                "5 min (3,0 km)",
                "Rua Santiago, 215 - Penha",
                "13 min (7,0 km)",
                "Rua Quito, 100 - Penha",
                "Aceitar"
            ).map { it to null },
            bitmap = null,
            screenWidth = 1220,
            screenHeight = 2712,
            config = dualConfigWithBoth()
        )!!

        val grossCfg = FilterConfig(
            fuelCostEnabled = true,
            fuelPricePerUnit = 6.29,
            kmPerUnit = 35.0,
            useNetHourlyRate = false,
            roundDownNetHourlyRate = false
        )
        val grossRes = FuelCalculator.estimateForFilter(offer, grossCfg)
        assertFalse(grossRes.isNet)
        assertEquals(45.0, grossRes.effectiveHourlyRate!!, 0.05)

        val netExactCfg = grossCfg.copy(useNetHourlyRate = true, roundDownNetHourlyRate = false)
        val netExactRes = FuelCalculator.estimateForFilter(offer, netExactCfg)
        assertTrue(netExactRes.isNet)
        assertEquals(39.61, netExactRes.effectiveHourlyRate!!, 0.05)

        // Com arredondamento para baixo: R$ 39,61 -> R$ 39,00 (e funciona mesmo se exibir combustivel no card estiver off)
        val netFloorCfg = netExactCfg.copy(fuelCostEnabled = false, roundDownNetHourlyRate = true)
        val netFloorRes = FuelCalculator.estimateForFilter(offer, netFloorCfg)
        assertTrue(netFloorRes.isNet)
        assertEquals(39.0, netFloorRes.effectiveHourlyRate!!, 0.001)

        val estFloor = FuelCalculator.estimate(10.0, 15.0, 45.0, netExactCfg.copy(roundDownNetHourlyRate = true))
        assertNotNull(estFloor)
        assertEquals("R$ 39/h líq.", estFloor!!.netHourlyText)
    }

    @Test
    fun dualCoordinatorEnforcesConflictPoliciesOnlyInDualModeAndHandlesQueuePromotionAndCancel() {
        val coord = DualRideCoordinator()
        val offer99 = AppCaptureRouter.parseOrderedLines(
            listOf(
                "Moto", "R$ 8,00", "R$ 40,00/hora", "0% de Taxa99 semanal", "4,90 • 200 corridas",
                "3 min (1,0 km)", "Rua A, 10 - Penha",
                "6 min (2,0 km)", "Destino Primeira Corrida, 100 - Olaria",
                "Aceitar"
            ).map { it to null },
            null, 1220, 2712, dualConfigWithBoth()
        )!!
        val offer99Queued = AppCaptureRouter.parseOrderedLines(
            listOf(
                "Moto", "R$ 11,00", "R$ 44,00/hora", "0% de Taxa99 semanal", "4,95 • 320 corridas",
                "4 min (1,2 km)", "Rua B, 20 - Olaria",
                "8 min (3,0 km)", "Destino Segunda Corrida, 250 - Ramos",
                "Aceitar"
            ).map { it to null },
            null, 1220, 2712, dualConfigWithBoth()
        )!!
        val offerUber = AppCaptureRouter.parseOrderedLines(
            listOf(
                "Moto", "Exclusivo", "R$ 14,00", "Est. de R$1,75/km", "★ 4,92 (180)",
                "5 min (1.5 km)", "Rua C, 30 - Penha",
                "10 min (6.5 km)", "Rua D, 40 - Centro",
                "Tenho interesse"
            ).map { it to null },
            null, 1220, 2712, dualConfigWithBoth()
        )!!

        // 1. Inativo quando apenas 1 app esta ligado (SINGLE_99 ou SINGLE_UBER)
        val single99Cfg = FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = false, dualConflictGuardEnabled = true)
        assertFalse(coord.isActive(single99Cfg))
        assertEquals(DualRideCoordinator.ConflictAction.ALLOW, coord.evaluateOfferConflict(offerUber, single99Cfg).action)

        // 2. Em modo DUAL, aceita Corrida #1 (99) e grava Destino #1
        val dualBlockCfg = FilterConfig(
            nineNineModuleEnabled = true,
            uberModuleEnabled = true,
            dualConflictGuardEnabled = true,
            dualConflictPolicy = DualConflictPolicy.BLOCK_AUTO_ACCEPT_ONLY
        )
        assertTrue(coord.isActive(dualBlockCfg))
        coord.onRideAccepted(offer99, dualBlockCfg, "R$ 8,00", nowMs = 1_000L)
        assertEquals("99", coord.primaryRide?.appSource)
        assertTrue(coord.primaryRide!!.dropoffAddress.contains("Destino Primeira Corrida"))

        // 3. Enquanto em viagem na 99, Uber toca -> Opcao 1 bloqueia apenas auto-aceite; Opcao 2 recusa tudo
        assertEquals(
            DualRideCoordinator.ConflictAction.BLOCK_AUTO_ACCEPT_ONLY,
            coord.evaluateOfferConflict(offerUber, dualBlockCfg).action
        )
        val dualRejectCfg = dualBlockCfg.copy(dualConflictPolicy = DualConflictPolicy.AUTO_REJECT_OTHER_APP)
        assertEquals(
            DualRideCoordinator.ConflictAction.AUTO_REJECT_OTHER_APP,
            coord.evaluateOfferConflict(offerUber, dualRejectCfg).action
        )

        // 4. Sequencia na mesma 99 -> grava Corrida #2 com Destino #2
        assertEquals(
            DualRideCoordinator.ConflictAction.ALLOW_AS_SEQUENTIAL,
            coord.evaluateOfferConflict(offer99Queued, dualBlockCfg).action
        )
        coord.onRideAccepted(offer99Queued, dualBlockCfg, "R$ 11,00", nowMs = 15_000L)
        assertTrue(coord.queuedRide!!.dropoffAddress.contains("Destino Segunda Corrida"))

        // 5. Corrida #1 termina (tela 5 estrelas da 99) -> promove Corrida #2 e mantem Destino #2 ativo!
        val finish99Screen = AppCaptureRouter.parseOrderedLines(
            listOf("Como foi sua corrida com Camila?", "Avalie", "Enviar avaliação").map { it to null },
            null, 1220, 2712, dualBlockCfg, preferredAppSource = "99"
        )!!
        val promotedEvt = coord.onScreenObserved(finish99Screen, dualBlockCfg, nowMs = 30_000L)
        assertNotNull(promotedEvt)
        assertFalse(promotedEvt!!.unlocked)
        assertTrue(coord.primaryRide!!.dropoffAddress.contains("Destino Segunda Corrida"))
        assertTrue(coord.queuedRide == null)

        // 6. Agora simulamos Corrida #2 na 99 entrando em viagem e depois 99 voltando silenciosamente p/ tela livre (cancelamento por voz)
        val active99Screen = AppCaptureRouter.parseOrderedLines(
            listOf("Finalizar corrida", "Em viagem").map { it to null },
            null, 1220, 2712, dualBlockCfg, preferredAppSource = "99"
        )!!
        coord.onScreenObserved(active99Screen, dualBlockCfg, nowMs = 40_000L)
        val idle99Screen = AppCaptureRouter.parseOrderedLines(
            listOf("Recompensas (2)", "Ganhos desta semana").map { it to null },
            null, 1220, 2712, dualBlockCfg, preferredAppSource = "99"
        )!!
        val unlockEvt = coord.onScreenObserved(idle99Screen, dualBlockCfg, nowMs = 42_000L)
        assertNotNull(unlockEvt)
        assertTrue(unlockEvt!!.unlocked)
        assertTrue(coord.primaryRide == null)
        assertTrue(coord.lastReleasedDestination!!.contains("Destino Segunda Corrida"))
        assertEquals(DualRideCoordinator.ConflictAction.ALLOW, coord.evaluateOfferConflict(offerUber, dualBlockCfg).action)

        // 7. Cancelamento explicito na Uber tambem libera a trava imediatamente e preserva ultimo destino
        coord.onRideAccepted(offerUber, dualBlockCfg, "R$ 14,00", nowMs = 50_000L)
        val uberCancelScreen = AppCaptureRouter.parseOrderedLines(
            listOf("O usuário cancelou a viagem", "OK").map { it to null },
            null, 1220, 2712, dualBlockCfg, preferredAppSource = "UBER"
        )!!
        val uberUnlock = coord.onScreenObserved(uberCancelScreen, dualBlockCfg, nowMs = 55_000L)
        assertNotNull(uberUnlock)
        assertTrue(uberUnlock!!.unlocked)
        assertTrue(coord.primaryRide == null)
        assertTrue(coord.lastReleasedDestination!!.contains("Rua D"))
    }

    @Test
    fun doesNotFalseTriggerStopsOnStreetNamesContainingParada() {
        val streetWithParadaLines = listOf(
            "Moto",
            "Exclusivo",
            "R$ 12,50",
            "Est. de R$1,80/km",
            "★ 4,94 (150)",
            "4 min (1.2 km)",
            "Rua Parada de Lucas, 45 - Parada de Lucas",
            "9 min (4.5 km)",
            "Ponto de Parada de Onibus Central - Ramos",
            "Tenho interesse"
        ).map { it to null }

        val parsedStreet = AppCaptureRouter.parseOrderedLines(
            streetWithParadaLines, null, 1220, 2712, dualConfigWithBoth(), preferredAppSource = "UBER"
        )
        assertNotNull(parsedStreet)
        assertFalse(parsedStreet!!.hasMultipleStops)
        assertEquals(0, parsedStreet.stopsCount)
        assertTrue(parsedStreet.pickupAddress!!.contains("Parada de Lucas"))

        val genuineStopLines = listOf(
            "UberX",
            "Exclusivo",
            "R$ 24,00",
            "1 parada",
            "★ 4,88 (210)",
            "5 min (1.5 km)",
            "Av. Brasil, 5000 - Olaria",
            "14 min (8.0 km)",
            "Rua Quito, 100 - Penha",
            "Aceitar"
        ).map { it to null }

        val parsedRealStop = AppCaptureRouter.parseOrderedLines(
            genuineStopLines, null, 1220, 2712, dualConfigWithBoth(), preferredAppSource = "UBER"
        )
        assertNotNull(parsedRealStop)
        assertTrue(parsedRealStop!!.hasMultipleStops)
        assertEquals(1, parsedRealStop.stopsCount)
    }

    @Test
    fun rideScreenshotDefaultsAndHistoryItemPathSupport() {
        val cfg = FilterConfig()
        assertTrue(cfg.saveRideScreenshotsEnabled)

        val itemWithoutShot = RideHistoryItem(
            id = 1L,
            timeFormatted = "14:00",
            pickupAddress = "99: Rua A",
            dropoffAddress = "Rua B",
            priceText = "R$ 10,00",
            pricePerKmText = "R$ 2,50/km",
            hourlyRateText = "R$ 40/h",
            ratingText = "★ 4,95",
            isInsideArea = true,
            reason = "✔ Área permitida"
        )
        assertEquals("", itemWithoutShot.screenshotPath)

        val itemWithShot = itemWithoutShot.copy(screenshotPath = "/data/user/0/com.ruan.uberareafilter/files/ride_shots/ride_1.jpg")
        assertTrue(itemWithShot.screenshotPath.endsWith("ride_1.jpg"))
    }

    @Test
    fun fixesRealDeviceLogBugsEstradaCommaLossLeakedHistoryRatingBoundaryAndTransitionFrame() {
        // 1. Log [19:02:39]: "Estrada Jose Rucas" NAO pode ser descartada como "Est." nem trocar Destino por Embarque
        val uberEstradaLines = listOf(
            "2 Moto",
            "Exclusivo",
            "R$ 9,06",
            "Est. de R$1,33/km",
            "★ 4,87 (984)",
            "Verificado",
            "9 min (3.1 km)",
            "Estrada José Rucas, Penha, Rio de Janeiro",
            "d 9 minutos (3.7 km)",
            "Rua Conde Pereira Carneiro, 41, Penha",
            "Circular, Rio de Janeiro",
            "Tenho interesse"
        ).map { it to null }
        val parsedEstrada = AppCaptureRouter.parseOrderedLines(
            uberEstradaLines, null, 1220, 2712, dualConfigWithBoth(), preferredAppSource = "UBER"
        )!!
        assertTrue(parsedEstrada.pickupAddress!!.contains("Estrada José Rucas"))
        assertTrue(parsedEstrada.dropoffAddress!!.contains("Conde Pereira Carneiro"))

        // 2. Log [19:00:00]: OCR leu "R$ 750" sem virgula no card "R$ 7,50" (Est. R$3,41/km * 2,2km)
        val uberDroppedCommaLines = listOf(
            "Moto",
            "R$ 750",
            "Est. de R$3,41/km",
            "★ 4,80 (157)",
            "+R$ 2,25 incluído",
            "3 min (1.0 km)",
            "Olaria, Rio de Janeiro",
            "2 minutos (1.2 km)",
            "Rua Paranhos, 264, Olaria, Rio de Janeiro",
            "Tenho interesse"
        ).map { it to null }
        val parsed750 = AppCaptureRouter.parseOrderedLines(
            uberDroppedCommaLines, null, 1220, 2712, dualConfigWithBoth(), preferredAppSource = "UBER"
        )!!
        assertEquals(7.50, parsed750.ridePrice!!, 0.01)
        assertTrue("Hourly rate should recover near R$64/h, was ${parsed750.hourlyRate}", parsed750.hourlyRate!! < 100.0)

        // 3. Log [19:01:01]: Linha do proprio app/historico atras da Uber ("★ 4,85 • R$ 46,00") nao pode roubar o preco R$ 8,09
        val leakedHistoryLines = listOf(
            "★ 4,85 • R$ 46,00",
            "Emb: UBER: Rua José Maurício, Penha",
            "2 Moto",
            "R$ 8,09",
            "Est. de R$0,62/km",
            "★ 4,91 (126)",
            "14 min (8.4 km)",
            "Avenida Brasil, Penha, Rio de Janeiro",
            "11 minutos (4.7 km)",
            "Estrada do Gabinal, Rio de Janeiro",
            "Tenho interesse"
        ).map { it to null }
        val parsedLeaked = AppCaptureRouter.parseOrderedLines(
            leakedHistoryLines, null, 1220, 2712, dualConfigWithBoth(), preferredAppSource = "UBER"
        )!!
        assertEquals(8.09, parsedLeaked.ridePrice!!, 0.01)
        assertTrue(parsedLeaked.dropoffAddress!!.contains("Estrada do Gabinal"))

        // 4. Log [09:39:40]: 99 leu "R$ 540" sem virgula no card "R$ 5,40" com R$ 41/hora e 2,1 km
        val nineDroppedComma = listOf(
            "Moto",
            "R$ 540",
            "R$41,00/hora",
            "0% de Taxa99 semanal",
            "4,92 • 200 corridas",
            "1 min (0,3 km)",
            "Rua Santiago, 215, Penha",
            "5 min (1,8 km)",
            "Estação de Trem Penha, Rua Jose Mauricio",
            "Aceitar"
        ).map { it to null }
        val parsed540 = AppCaptureRouter.parseOrderedLines(
            nineDroppedComma, null, 1220, 2712, dualConfigWithBoth(), preferredAppSource = "99"
        )!!
        assertEquals(5.40, parsed540.ridePrice!!, 0.01)

        // 5. Log [18:56:41] & [19:00:00]: Nota exata 4,80 nao pode reprovar quando minRating = 4,80
        val zone = MapZone(
            id = 1L,
            name = "Permitida 1",
            mode = ZoneMode.ALLOWED,
            shape = ZoneShape.CIRCLE,
            centerLat = -22.84,
            centerLng = -43.26,
            radiusMeters = 2000.0,
            streetNames = listOf("Rua Santiago", "Rua Jose Mauricio")
        )
        val ratingCfg = FilterConfig(
            isEnabled = true,
            filterByRatingEnabled = true,
            minRating = 4.80,
            filterByHourlyRateEnabled = false,
            filterByPickupDistEnabled = false,
            filterByTotalDistEnabled = false,
            mapZones = listOf(zone),
            // Sem Geocoder no teste: as ruas precisam estar no indice como INTERNAS para aprovar so pelo nome
            streetIndex = mapOf(
                1L to ZoneStreetIndex(
                    zoneId = 1L,
                    geometryHash = GeoMath.indexKeyFor(zone, listOf(zone)),
                    complete = true,
                    internalStreets = listOf("Rua Santiago", "Rua Jose Mauricio"),
                    touchingStreets = listOf("Rua Santiago", "Rua Jose Mauricio"),
                    builtAtMs = 1L
                )
            )
        )
        val offerAtExactMin = parsed540.copy(passengerRating = 4.80)
        val evalRes = runBlocking { AreaEvaluator().evaluate(offerAtExactMin, ratingCfg) }
        assertEquals(Decision.INSIDE_AREA, evalRes.decision)
        assertTrue(evalRes.checklistText.contains("✔ Nota 4,80"))

        // 6. Log [18:34:12]: Frame de transicao pos-aceite/parcial sem destino ou com "Corrida aceita" NAO dispara oferta
        val partialTransitionLines = listOf(
            "X Não afeta a TA",
            "Moto",
            "R$5,50",
            "4 min (23,0 km)",
            "Rua Professor Lacé, 598, Ramos",
            "Aceitar"
        ).map { it to null }
        val parsedTransition = RideOfferParser.parseFromOrderedLines(
            partialTransitionLines, null, 1220, 2712, FilterConfig.DEFAULT_REJECT_HINTS
        )
        assertFalse(parsedTransition.isRideOffer)
    }

    // ==================================================================
    // Motor de localizacao com nivel de confianca (demarcacao de area)
    // ==================================================================

    // Quadrado ~2,2 km x ~2,0 km em Olaria/Penha (so para geometria)
    private val squareZone = MapZone(
        id = 10L,
        name = "Quadrado",
        mode = ZoneMode.ALLOWED,
        shape = ZoneShape.POLYGON,
        points = listOf(-22.84 to -43.27, -22.84 to -43.25, -22.82 to -43.25, -22.82 to -43.27)
    )
    private val blockedSquare = MapZone(
        id = 20L,
        name = "Comunidade",
        mode = ZoneMode.BLOCKED,
        shape = ZoneShape.POLYGON,
        points = listOf(-22.835 to -43.265, -22.835 to -43.260, -22.830 to -43.260, -22.830 to -43.265)
    )

    @Test
    fun addressParserSplitsUberAnd99Formats() {
        val a = AddressParser.parse("Rua Leopoldina Rego, 212, Olaria, Rio de Janeiro")
        assertEquals("212", a.number)
        assertEquals("olaria", a.bairro)
        assertEquals("leopoldina rego", a.street!!.core)

        val b = AddressParser.parse("CAPS III Fernando Diniz, Rua Leopoldina Rego, 754 - Penha")
        assertEquals("CAPS III Fernando Diniz", b.poi)
        assertEquals("754", b.number)
        assertEquals("penha", b.bairro)

        val c = AddressParser.parse("Rua Montevidéu 782, Penha, Rio de Janeiro")
        assertEquals("782", c.number)
        assertEquals("montevideu", c.street!!.core)

        val onlyBairro = AddressParser.parse("Olaria, Rio de Janeiro")
        assertNull(onlyBairro.street)
        assertEquals("olaria", onlyBairro.bairro)

        val baixada = AddressParser.parse("Rua Galvão Filho, 42, Belford Roxo, Belford Roxo")
        assertEquals("belford roxo", baixada.city)

        val est = AddressParser.parse("Est. de Rio do Pau, 220, Penha")
        assertEquals("estrada", est.street!!.type)
        assertEquals("rio pau", est.street!!.core)
    }

    @Test
    fun streetMatchingIsStrictForApprovalAndLooseForTouching() {
        val card = AddressParser.streetKeyOf("R. São Luiz Gonzaga")!!
        val osm = AddressParser.streetKeyOf("Rua São Luís Gonzaga")!!
        assertTrue(AddressParser.strictMatch(card, osm))

        val shorter = AddressParser.streetKeyOf("Rua Santa Maria")!!
        val longer = AddressParser.streetKeyOf("Rua Santa Maria Goretti")!!
        assertFalse("Nome contido NAO pode aprovar", AddressParser.strictMatch(shorter, longer))
        assertTrue(AddressParser.looseMatch(shorter, longer))

        val rua = AddressParser.streetKeyOf("Rua Uranos")!!
        val travessa = AddressParser.streetKeyOf("Travessa Uranos")!!
        assertFalse("Rua X e Travessa X sao vias diferentes", AddressParser.looseMatch(rua, travessa))

        val abbrev = AddressParser.streetKeyOf("Av. Nossa Sra. da Penha")!!
        val full = AddressParser.streetKeyOf("Avenida Nossa Senhora da Penha")!!
        assertTrue(AddressParser.strictMatch(abbrev, full))
    }

    @Test
    fun geoMathDepthIsSignedDistanceToBorder() {
        val center = GeoMath.depthMeters(-22.83, -43.26, squareZone)
        assertTrue("centro ~1 km dentro, foi $center", center in 900.0..1200.0)
        val justInside = GeoMath.depthMeters(-22.8391, -43.26, squareZone)
        assertEquals(100.0, justInside, 5.0)
        val justOutside = GeoMath.depthMeters(-22.8409, -43.26, squareZone)
        assertEquals(-100.0, justOutside, 5.0)
        // O antigo "ate 45 m fora conta como dentro" acabou: 20 m fora e NEGATIVO
        assertTrue(GeoMath.depthMeters(-22.83, -43.2702, squareZone) < 0.0)
    }

    @Test
    fun borderStreetWithoutNumberIsNeverAutoApproved() {
        // Rua que nasce dentro e entra na comunidade (Rua Uranos / Intendente Magalhaes): so o nome NAO basta
        val parts = AddressParser.parse("Rua Uranos, Olaria, Rio de Janeiro")
        val verdict = LocationRules.decide(
            parts,
            StreetEvidence(StreetKnowledge.TOUCHES, squareZone, "Rua Uranos"),
            fixes = listOf(GeoFix(-22.83, -43.26, FixPrecision.STREET)), // centro da rua cai dentro...
            allowedZones = listOf(squareZone),
            blockedZones = emptyList()
        )
        assertEquals(AreaVerdictKind.UNCERTAIN, verdict.kind)
    }

    @Test
    fun numberCoordinateOutsideWinsOverStreetName() {
        // Bug antigo: GPS do numero fora + nome da rua na lista => aprovava. Agora recusa.
        val parts = AddressParser.parse("Rua Uranos, 1500, Olaria, Rio de Janeiro")
        val verdict = LocationRules.decide(
            parts,
            StreetEvidence(StreetKnowledge.TOUCHES, squareZone, "Rua Uranos"),
            fixes = listOf(GeoFix(-22.8420, -43.26, FixPrecision.NUMBER)), // ~220 m fora
            allowedZones = listOf(squareZone),
            blockedZones = emptyList()
        )
        assertEquals(AreaVerdictKind.OUTSIDE, verdict.kind)
    }

    @Test
    fun numberCoordinateNearBorderIsUncertainUnlessStreetIsInternal() {
        val parts = AddressParser.parse("Rua Uranos, 300, Olaria, Rio de Janeiro")
        val nearBorder = listOf(GeoFix(-22.83985, -43.26, FixPrecision.NUMBER)) // ~17 m dentro
        val border = LocationRules.decide(
            parts, StreetEvidence(StreetKnowledge.TOUCHES, squareZone), nearBorder, listOf(squareZone), emptyList()
        )
        assertEquals(AreaVerdictKind.UNCERTAIN, border.kind)

        val internal = LocationRules.decide(
            parts, StreetEvidence(StreetKnowledge.INTERNAL, squareZone), nearBorder, listOf(squareZone), emptyList()
        )
        assertEquals(AreaVerdictKind.INSIDE, internal.kind)

        val deep = LocationRules.decide(
            parts, StreetEvidence(StreetKnowledge.TOUCHES, squareZone),
            listOf(GeoFix(-22.835, -43.26, FixPrecision.NUMBER)), listOf(squareZone), emptyList()
        )
        assertEquals(AreaVerdictKind.INSIDE, deep.kind)
    }

    @Test
    fun adjacentGreenZonesCountAsOneAreaAtTheSharedBorder() {
        val zoneA = squareZone.copy(id = 11L, name = "Olaria", points = listOf(-22.84 to -43.27, -22.84 to -43.26, -22.82 to -43.26, -22.82 to -43.27))
        val zoneB = squareZone.copy(id = 12L, name = "Penha", points = listOf(-22.84 to -43.26, -22.84 to -43.25, -22.82 to -43.25, -22.82 to -43.26))
        val fixOnSharedBorder = listOf(GeoFix(-22.83, -43.26005, FixPrecision.NUMBER)) // ~5 m da divisa A|B
        val verdict = LocationRules.decide(
            AddressParser.parse("Rua Leopoldina Rego, 900, Olaria"),
            StreetEvidence(StreetKnowledge.TOUCHES, zoneA),
            fixOnSharedBorder, listOf(zoneA, zoneB), emptyList()
        )
        assertEquals(AreaVerdictKind.INSIDE, verdict.kind)
        assertTrue(GeoMath.unionDepthAtLeast(-22.83, -43.26005, listOf(zoneA, zoneB), 40.0))
        assertFalse(GeoMath.unionDepthAtLeast(-22.83, -43.26005, listOf(zoneA), 40.0))
    }

    @Test
    fun internalStreetApprovesByNameAndUnknownStreetIsRejected() {
        val parts = AddressParser.parse("Rua Pirangi, Olaria")
        val ok = LocationRules.decide(
            parts, StreetEvidence(StreetKnowledge.INTERNAL, squareZone, "Rua Pirangi"), emptyList(), listOf(squareZone), emptyList()
        )
        assertEquals(AreaVerdictKind.INSIDE, ok.kind)
        assertEquals(squareZone.id, ok.zoneId)

        val outside = LocationRules.decide(
            AddressParser.parse("Estrada do Gabinal, Rio de Janeiro"),
            StreetEvidence(StreetKnowledge.NOT_IN_ZONE), emptyList(), listOf(squareZone), emptyList()
        )
        assertEquals(AreaVerdictKind.OUTSIDE, outside.kind)
    }

    @Test
    fun blockedZoneBeatsEverythingAndBairroOnlyIsNeverApproved() {
        val inBlocked = LocationRules.decide(
            AddressParser.parse("Rua X, 10, Olaria"),
            StreetEvidence(StreetKnowledge.INTERNAL, squareZone),
            listOf(GeoFix(-22.8325, -43.2625, FixPrecision.NUMBER)),
            listOf(squareZone), listOf(blockedSquare)
        )
        assertEquals(AreaVerdictKind.OUTSIDE, inBlocked.kind)
        assertTrue(inBlocked.explicitBlocked)

        val bairroOnly = LocationRules.decide(
            AddressParser.parse("Olaria, Rio de Janeiro"),
            StreetEvidence(StreetKnowledge.UNKNOWN),
            listOf(GeoFix(-22.83, -43.26, FixPrecision.AREA)),
            listOf(squareZone), emptyList()
        )
        assertEquals(AreaVerdictKind.UNCERTAIN, bairroOnly.kind)
    }

    @Test
    fun geocoderCandidatesMustMatchStreetBairroAndNumber() {
        val parts = AddressParser.parse("Rua Leopoldina Rego, 212, Olaria, Rio de Janeiro")
        fun cand(street: String?, number: String?, bairro: String?) = GeoCandidate(
            lat = -22.83, lng = -43.26, thoroughfare = street, subThoroughfare = number, featureName = number,
            subLocality = bairro, locality = null, subAdminArea = "Rio de Janeiro", adminArea = "Rio de Janeiro",
            addressLine = null
        )
        assertEquals(FixPrecision.NUMBER, GeoCandidateRules.classify(cand("Rua Leopoldina Rego", "212", "Olaria"), parts))
        assertEquals(FixPrecision.STREET, GeoCandidateRules.classify(cand("Rua Leopoldina Rego", null, "Olaria"), parts))
        assertNull("Outra rua (palpite fraco) e descartada", GeoCandidateRules.classify(cand("Rua Uranos", "212", "Olaria"), parts))
        assertNull("Homonimo em outro bairro e descartado", GeoCandidateRules.classify(cand("Rua Leopoldina Rego", "212", "Campo Grande"), parts))
        assertTrue(GeoCandidateRules.numberMatches("212", "200-220"))
        assertFalse(GeoCandidateRules.numberMatches("213", "200-220"))
    }

    @Test
    fun internalStreetIsNotApprovedByNameWhenHomonymIsSuspected() {
        // "Rua Sao Jorge" e interna na zona, mas o card diz Campo Grande e o mapa so acha a rua em outro bairro
        val parts = AddressParser.parse("Rua São Jorge, Campo Grande, Rio de Janeiro")
        val homonym = GeoCandidate(
            lat = -22.83, lng = -43.26, thoroughfare = "Rua São Jorge", subThoroughfare = null, featureName = null,
            subLocality = "Olaria", locality = null, subAdminArea = "Rio de Janeiro", adminArea = "Rio de Janeiro",
            addressLine = null
        )
        assertNull(GeoCandidateRules.classify(homonym, parts))
        assertTrue(GeoCandidateRules.isSameStreetInOtherBairro(homonym, parts))

        val verdict = LocationRules.decide(
            parts, StreetEvidence(StreetKnowledge.INTERNAL, squareZone, "Rua São Jorge"),
            emptyList(), listOf(squareZone), emptyList(), homonymSuspected = true
        )
        assertEquals(AreaVerdictKind.UNCERTAIN, verdict.kind)

        // Sem indicio de homonimo (ex.: Uber diz "Olaria", Google diz "Ramos" para a mesma rua na divisa de bairros)
        // a rua interna continua aprovada
        val sameStreetNeighbourBairro = LocationRules.decide(
            parts, StreetEvidence(StreetKnowledge.INTERNAL, squareZone, "Rua São Jorge"),
            emptyList(), listOf(squareZone), emptyList(), homonymSuspected = false
        )
        assertEquals(AreaVerdictKind.INSIDE, sameStreetNeighbourBairro.kind)

        val bairroResult = homonym.copy(thoroughfare = null, subLocality = "Campo Grande")
        assertTrue(GeoCandidateRules.isBairroResult(bairroResult, parts))

        // Mesmo caso, mas o mapa confirmou a rua no bairro do card dentro da zona -> aprova
        val ok = LocationRules.decide(
            AddressParser.parse("Rua São Jorge, Olaria, Rio de Janeiro"),
            StreetEvidence(StreetKnowledge.INTERNAL, squareZone, "Rua São Jorge"),
            listOf(GeoFix(-22.83, -43.26, FixPrecision.STREET)), listOf(squareZone), emptyList()
        )
        assertEquals(AreaVerdictKind.INSIDE, ok.kind)

        // Erro de uma letra NAO aprova pelo nome (vira rua de divisa)
        val lucia = AddressParser.streetKeyOf("Rua Santa Lúcia")!!
        val luzia = AddressParser.streetKeyOf("Rua Santa Luzia")!!
        assertFalse(AddressParser.strictMatch(lucia, luzia))
    }

    @Test
    fun wordBoundaryFixesMarechalAndBlockedKeywordFalsePositives() {
        assertFalse(AreaEvaluator.wordRegex("mare").containsMatchIn("praca marechal rondon, marechal hermes"))
        assertTrue(AreaEvaluator.wordRegex("mare").containsMatchIn("pinheiro, mare, rio de janeiro"))
        assertFalse(AreaEvaluator.wordRegex("luz").containsMatchIn("rua santa luzia, 10"))
        assertTrue(AreaEvaluator.wordRegex("luz").containsMatchIn("rua da luz, centro"))
    }

    @Test
    fun driverGpsProvesPickupOnlyWhenDeepInsideAndFresh() {
        val bigCircle = MapZone(
            id = 30L, name = "Circulo", mode = ZoneMode.ALLOWED, shape = ZoneShape.CIRCLE,
            centerLat = -22.83, centerLng = -43.26, radiusMeters = 2000.0
        )
        val center = DriverFix(-22.83, -43.26, accuracyMeters = 10.0, ageMs = 2_000L)
        val ev = LocationRules.driverPickupEvidence(center, 0.3, listOf(bigCircle), emptyList())
        assertEquals(AreaVerdictKind.INSIDE, ev!!.kind)

        // Perto da borda: embarque a 1,2 km pode estar fora -> sem conclusao
        val nearEdge = DriverFix(-22.8455, -43.26, accuracyMeters = 10.0, ageMs = 2_000L)
        assertNull(LocationRules.driverPickupEvidence(nearEdge, 1.2, listOf(bigCircle), emptyList()))

        // Posicao velha nao vale
        assertNull(LocationRules.driverPickupEvidence(center.copy(ageMs = 60_000L), 0.3, listOf(bigCircle), emptyList()))
    }

    @Test
    fun evaluateEndToEndUsesStreetIndexAndNeverAcceptsBorderStreetByName() {
        val zone = MapZone(
            id = 1L, name = "Olaria", mode = ZoneMode.ALLOWED, shape = ZoneShape.CIRCLE,
            centerLat = -22.845, centerLng = -43.266, radiusMeters = 1500.0,
            streetNames = listOf("Rua Pirangi", "Rua Leopoldina Rego", "Rua Uranos")
        )
        val index = ZoneStreetIndex(
            zoneId = 1L, geometryHash = GeoMath.indexKeyFor(zone, listOf(zone)), complete = true,
            internalStreets = listOf("Rua Pirangi", "Rua Leopoldina Rego"),
            touchingStreets = listOf("Rua Pirangi", "Rua Leopoldina Rego", "Rua Uranos"),
            builtAtMs = 1L
        )
        val cfg = FilterConfig(
            filterByHourlyRateEnabled = false, filterByPickupDistEnabled = false,
            mapZones = listOf(zone), streetIndex = mapOf(1L to index)
        )
        fun offer(pickup: String, dropoff: String) = AppCaptureRouter.parseOrderedLines(
            listOf("Moto", "Exclusivo", "R$ 14,09", "Est. de R$1,40/km", "★ 4,94 (211)",
                "6 min (2.5 km)", pickup, "20 minutos (8.0 km)", dropoff, "Aceitar").map { it to null },
            null, 1220, 2712, FilterConfig(nineNineModuleEnabled = false, uberModuleEnabled = true)
        )!!

        val inside = runBlocking { AreaEvaluator().evaluate(offer("Rua Pirangi, Olaria", "Rua Leopoldina Rego, Olaria"), cfg) }
        assertEquals(Decision.INSIDE_AREA, inside.decision)

        val border = runBlocking { AreaEvaluator().evaluate(offer("Rua Pirangi, Olaria", "Rua Uranos, Olaria"), cfg) }
        assertEquals(Decision.UNCERTAIN, border.decision)

        val outside = runBlocking { AreaEvaluator().evaluate(offer("Rua Pirangi, Olaria", "Estrada do Gabinal, Freguesia"), cfg) }
        assertEquals(Decision.OUTSIDE_AREA, outside.decision)

        // Zona redesenhada (hash diferente) -> indice ignorado -> nada e aprovado so pelo nome
        val moved = zone.copy(centerLat = -22.846)
        val staleCfg = cfg.copy(mapZones = listOf(moved))
        val stale = runBlocking { AreaEvaluator().evaluate(offer("Rua Pirangi, Olaria", "Rua Leopoldina Rego, Olaria"), staleCfg) }
        assertEquals(Decision.UNCERTAIN, stale.decision)
    }

    // ==================================================================
    // Paleta do card flutuante (legibilidade e conforto a noite)
    // ==================================================================

    @Test
    fun cardPaletteKeepsTextReadableForAnyChosenColorDayAndNight() {
        val presets = listOf(
            "#0A2E1D", "#1B5E20", "#330A0A", "#B71C1C", "#271252", "#6A1B9A", "#3B2806", "#E65100",
            "#0D2A4A", "#1565C0", "#880E4F", "#006064", "#263238", "#111111", "#FFEB3B", "#FFFFFF"
        ).map { CardPalette.parseHex(it) } + listOf<Int?>(null)

        for (user in presets) {
            for (decision in Decision.values()) {
                for (night in listOf(true, false)) {
                    val c = CardPalette.build(user, decision, night)
                    val top = CardPalette.opaque(c.bgTop)
                    val bottom = CardPalette.opaque(c.bgBottom)
                    for (bg in listOf(top, bottom)) {
                        val tag = "${user?.let { CardPalette.toHex(it) }} $decision night=$night"
                        assertTrue("texto principal $tag", CardPalette.contrast(c.textPrimary, bg) >= 7.0)
                        assertTrue("texto terciario $tag", CardPalette.contrast(c.textTertiary, bg) >= 4.5)
                        assertTrue("acento $tag", CardPalette.contrast(c.accent, bg) >= 4.5)
                        assertTrue("falha $tag", CardPalette.contrast(c.bad, bg) >= 4.5)
                    }
                    // Fundo nunca claro: luminancia baixa = nao ofusca a noite
                    assertTrue(CardPalette.relativeLuminance(top) < 0.04)
                }
            }
        }
    }

    @Test
    fun cardPaletteIsDarkerAtNightAndKeepsNeutralChoicesSemantic() {
        val green = CardPalette.parseHex("#1B5E20")
        val day = CardPalette.build(green, Decision.INSIDE_AREA, night = false)
        val night = CardPalette.build(green, Decision.INSIDE_AREA, night = true)
        assertTrue(
            CardPalette.relativeLuminance(CardPalette.opaque(night.bgTop)) <
                CardPalette.relativeLuminance(CardPalette.opaque(day.bgTop))
        )
        // Texto noturno nao e branco puro (reduz o "halo")
        assertTrue(night.textPrimary != 0xFFFFFFFF.toInt())

        // "Preto" escolhido para Recusa: fundo neutro, mas o acento continua vermelho (matiz da decisao)
        val black = CardPalette.build(CardPalette.parseHex("#111111"), Decision.OUTSIDE_AREA, night = true)
        val hue = CardPalette.rgbToHsl(black.accent)[0]
        assertTrue("acento deveria ser vermelho, matiz=$hue", hue < 20f || hue > 340f)

        // Laranja vibrante: antes texto branco tinha 3,8:1; agora o fundo vira um marrom-escuro legivel
        val orange = CardPalette.build(CardPalette.parseHex("#E65100"), Decision.INSIDE_AREA, night = false)
        assertTrue(CardPalette.contrast(orange.textPrimary, CardPalette.opaque(orange.bgTop)) > 12.0)
    }

    private fun dualConfigWithBoth() = FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = true)
}
