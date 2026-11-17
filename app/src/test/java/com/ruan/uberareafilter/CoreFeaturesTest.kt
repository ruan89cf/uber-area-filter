package com.ruan.uberareafilter

import com.ruan.uberareafilter.driver.AppCaptureRouter
import com.ruan.uberareafilter.driver.CaptureMode
import com.ruan.uberareafilter.driver.UberCaptureModule
import com.ruan.uberareafilter.engine.AreaEvaluator
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
            mapZones = listOf(zone)
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

    private fun dualConfigWithBoth() = FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = true)
}
