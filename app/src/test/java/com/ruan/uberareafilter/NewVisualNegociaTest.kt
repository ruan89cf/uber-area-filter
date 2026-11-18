package com.ruan.uberareafilter

import com.ruan.uberareafilter.driver.AppCaptureRouter
import com.ruan.uberareafilter.driver.UberCaptureModule
import com.ruan.uberareafilter.engine.AreaEvaluator
import com.ruan.uberareafilter.engine.Decision
import com.ruan.uberareafilter.engine.NegotiationAdvisor
import com.ruan.uberareafilter.engine.ParsedRideOffer
import com.ruan.uberareafilter.engine.RideLedgerTracker
import com.ruan.uberareafilter.engine.RideOfferParser
import com.ruan.uberareafilter.engine.TextNormalizer
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.storage.EarningsStore
import com.ruan.uberareafilter.storage.LedgerStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 18/11: visual novo da 99 (icone redondo do trecho lido como "O": "O6 min (2,7 km)"), telas da corrida
 * com SO a 99 ligada e corridas "Negocia". Textos copiados do log do celular desse dia.
 * O visual antigo continua coberto em todos os testes: se a 99 voltar atras, nada quebra.
 */
class NewVisualNegociaTest {

    private val only99 = FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = false)
    private val dual = FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = true)
    private val onlyUber = FilterConfig(nineNineModuleEnabled = false, uberModuleEnabled = true)

    private fun parse(lines: List<String>, cfg: FilterConfig = only99, prefer: String? = "99"): ParsedRideOffer? =
        AppCaptureRouter.parseOrderedLines(lines.map { it to null }, null, 1220, 2712, cfg, preferredAppSource = prefer)

    // 06:35:30 (print do motorista, R$ 13,00): o circulo verde do embarque vira "O" colado no tempo
    private fun card0635(pickupLeg: String) = listOf(
        "Moto",
        "R$13,00",
        "R$1,15/km",
        "0% de Taxa99 semanal",
        "R\$O,84 Tarifa base dinâmica incl.",
        "4,89 392 corridas",
        "Perfil Premium",
        pickupLeg,
        "Rua Adílson Lisboa Gonçalves, 26, Olaria",
        "14 min (8,6 km)",
        "Rua Dr. Rodrigues de Santana, 53, Benfica",
        "Aceitar"
    )

    @Test
    fun newVisualIconBeforeMinutesParsesExactlyLikeTheOldVisual() {
        val old = parse(card0635("6 min (2,7 km)"))!!
        assertTrue(old.isRideOffer)
        val variants = listOf("O6 min (2,7 km)", "O 6 min (2,7 km)", "o6 min (2,7 km)", "O6min (2,7 km)", "06 min (2,7 km)")
        for (variant in variants) {
            val now = parse(card0635(variant))
            assertNotNull(variant, now)
            assertTrue(variant, now!!.isRideOffer)
            assertEquals(variant, old.ridePrice!!, now.ridePrice!!, 0.001)
            assertEquals(variant, 2.7, now.pickupDistanceKm!!, 0.001)
            assertEquals(variant, 8.6, now.dropoffDistanceKm!!, 0.001)
            assertEquals(variant, 6.0, now.pickupMinutes!!, 0.001)
            assertEquals(variant, 14.0, now.dropoffMinutes!!, 0.001)
            assertEquals(variant, old.pickupAddress, now.pickupAddress)
            assertEquals(variant, old.dropoffAddress, now.dropoffAddress)
            assertEquals(variant, old.hourlyRate!!, now.hourlyRate!!, 0.001)
            assertFalse(variant, now.isNegotiable)
        }
    }

    @Test
    fun newVisualIconWithTheLegSplitInTwoLines() {
        // OCR quebrou o trecho: "O 4 min" numa linha e "(1,1 km)" na outra
        val lines = card0635("O 4 min").toMutableList().apply { add(indexOf("O 4 min") + 1, "(1,1 km)") }
        val p = parse(lines)!!
        assertTrue(p.isRideOffer)
        assertEquals(1.1, p.pickupDistanceKm!!, 0.001)
        assertEquals(4.0, p.pickupMinutes!!, 0.001)
    }

    @Test
    fun newVisualWorksInEveryCaptureMode() {
        val lines = card0635("O6 min (2,7 km)")
        val single = parse(lines, only99)!!
        val both = parse(lines, dual)!!
        assertTrue(single.isRideOffer)
        assertTrue(both.isRideOffer)
        assertEquals("99", both.appSource)
        assertEquals(2.7, both.pickupDistanceKm!!, 0.001)
        // so a Uber ligada: card da 99 continua sem ser lido
        assertNull(parse(lines, onlyUber, prefer = null))
    }

    @Test
    fun ocrLettersInsideNumbersAreFixed() {
        assertEquals("6 min (2,7 km)", RideOfferParser.stripLegIconNoise("O6 min (2,7 km)"))
        assertEquals("4 min (1,1 km)", RideOfferParser.stripLegIconNoise("O 4 min (1,1 km)"))
        assertEquals("7 min (3,2 km)", RideOfferParser.stripLegIconNoise("- 7 min (3,2 km)"))
        // endereco/linha normal nao muda
        assertEquals("Rua Ourique, 451, Penha Circular", RideOfferParser.stripLegIconNoise("Rua Ourique, 451, Penha Circular"))
        assertEquals("R$ 50,00", RideOfferParser.fixCurrencyOcr("R$ 5O,00"))
        assertEquals("R$10,80 1,1x", RideOfferParser.fixCurrencyOcr("R$1O,80 1,1x"))
        assertEquals("R$ Oferta", RideOfferParser.fixCurrencyOcr("R$ Oferta"))
        // Uber usa a mesma limpeza do icone
        assertEquals("6 min (2.1 km)", UberCaptureModule().normalizeLegOcrNoise("O6 min (2.1 km)"))
    }

    @Test
    fun legWithFiveReadAsSAndTenReadAsOneO() {
        // 11:27:20: "S min (3,1 km)" era "5 min (3,1 km)"
        val p = parse(
            listOf(
                "Moto", "R$5,60", "0% de Taxa99 semanal", "R$1,02 Tarifa base dinâmica incl.",
                "5,00 22 corridas", "CPF e Cartão verif.", "O4 min (1,8 km)", "Estr. de Itararé, 161, Ramos",
                "S min (3,1 km)", "Passarela Luiz Pacheco Drumond, Ramos", "Aceitar"
            )
        )!!
        assertTrue(p.isRideOffer)
        assertEquals(1.8, p.pickupDistanceKm!!, 0.001)
        assertEquals(3.1, p.dropoffDistanceKm!!, 0.001)
        assertEquals(5.0, p.dropoffMinutes!!, 0.001)

        val ten = parse(card0635("6 min (2,7 km)").map { if (it == "14 min (8,6 km)") "1O min (8,6 km)" else it })!!
        assertEquals(10.0, ten.dropoffMinutes!!, 0.001)
    }

    @Test
    fun pickupLegWithIconNoLongerSwapsPickupAndDestination() {
        // 09:27:22 (Negocia R$ 12,94): sem ler "O4 min (1,8 km)" o app usava o DESTINO (9,3 km) como embarque
        val p = parse(
            listOf(
                "Moto Negocia", "R$12,94", "R$34,46/hora", "3 0% de Taxa99 semanal",
                "4,96 324 corridasCartão verif.", "O4 min (1,8 km)", "Hospital Balbino: Pronto Atendimento,",
                "Emergência, Pronto Socorro", "19 min (9,3 km)", "Rua Dias da Cruz, 210 (Dias da Cruz, 210)",
                "Aceitar por R$12,94", "R$13,59 R$14,23", "R$14,88"
            )
        )!!
        assertTrue(p.isRideOffer)
        assertEquals(12.94, p.ridePrice!!, 0.001)
        assertEquals(1.8, p.pickupDistanceKm!!, 0.001)
        assertEquals(9.3, p.dropoffDistanceKm!!, 0.001)
        assertTrue(p.pickupAddress!!.startsWith("Hospital Balbino"))
        assertTrue(p.dropoffAddress!!.startsWith("Rua Dias da Cruz"))
        assertTrue(p.isNegotiable)
        assertEquals(listOf(13.59, 14.23, 14.88), p.counterOfferPrices)
    }

    // ---------------- Telas da corrida com 1 app so ----------------

    // Prints 59133-59135 (fluxo de cancelar na 99) e log de 18/11 07:16, com SO a 99 ligada
    @Test
    fun onlyNineNineEnabledStillReadsTheRideScreens() {
        val mais = parse(
            listOf(
                "Moto", "Silvana >", "5,00★ • 96 corridas",
                "Rua Antônio Rêgo, 815, Olaria, Rio de Janeiro - RJ,", "21073-310, Brasil",
                "Rua Euclídes Faria, 22 , Ramos, Rio de Janeiro - RJ,", "21060-100, Brasil",
                "Cancelar Corrida", "Central de Ajuda", "Recusar novas corridas"
            )
        )
        assertNotNull(mais)
        assertTrue(mais!!.isInActiveRide)
        assertFalse(mais.isRideOffer)

        val cancelled = parse(
            listOf(
                "Você cancelou a corrida", "Você cancelou a corrida antes de chegar ao local de embarque",
                "Política de cancelamento", "Ajuda", "Confirm"
            )
        )
        assertNotNull(cancelled)
        assertTrue(cancelled!!.isCancelledScreen)

        val accepted = parse(acceptedCard0716())
        assertNotNull(accepted)
        assertTrue(accepted!!.isInActiveRide)
        assertFalse(accepted.isRideOffer)

        val finishing = parse(listOf("Vanessa chegou ao seu destino?", "Se você, por engano, finalizou uma corrida sem pegar o passageiro"))
        assertTrue(finishing!!.isInActiveRide)

        // corrida que OUTRO motorista pegou nao pode virar corrida sua no painel
        val taken = parse(listOf("Moto", "Corrida aceita por outro motorista", "R$5,00"))
        assertTrue(taken == null || !taken.isInActiveRide)
    }

    private fun acceptedCard0716() = listOf(
        "Moto", "Corrida aceita", "R$5,00", "Carregando...", "0% de Taxa99 semanal",
        "R\$O,56 Tarifa base dinämica inc.", "t 4,90 +999 corridas", "Perfil Essencial", "O 4 min (1,5 km)",
        "Rua Cardoso de Morais, 336, Bonsucesso", "3 min (1,2 km)", "Avenida Brasil, 5843, Bonsucesso", ") Buscando"
    )

    @Test
    fun onlyUberEnabledReadsUberRideScreensToo() {
        val p = parse(listOf("Viagem cancelada", "O usuário cancelou a viagem"), onlyUber, prefer = "UBER")
        assertNotNull(p)
        assertTrue(p!!.isCancelledScreen)
        assertEquals("UBER", p.appSource)
    }

    @Test
    fun dualModeReadsRideScreensEvenWithTheLockOff() {
        val noGuard = dual.copy(dualConflictGuardEnabled = false)
        val p = parse(listOf("Você cancelou a corrida", "Política de cancelamento"), noGuard, prefer = "99")
        assertNotNull(p)
        assertTrue(p!!.isCancelledScreen)
        assertEquals("99", p.appSource)
    }

    private class FakeStore : LedgerStore {
        val rows = LinkedHashMap<Long, EarningsStore.Entry>()
        private var next = 1L
        override fun insert(e: EarningsStore.Entry): Long { val id = next++; rows[id] = e.copy(id = id); return id }
        override fun setStatus(id: Long, status: String): Boolean { rows[id] = rows.getValue(id).copy(status = status); return true }
        override fun setMinutes(id: Long, minutes: Double): Boolean { rows[id] = rows.getValue(id).copy(minutes = minutes); return true }
        override fun addOnline(ts: Long, seconds: Long) {}
        override fun countOffer(ts: Long) {}
        override fun countRejected(ts: Long, reasonKeys: List<String>) {}
        override fun countAccepted(ts: Long) {}
        override fun openRidesSince(fromTs: Long) =
            rows.values.filter { it.status == EarningsStore.STATUS_OPEN && it.ts >= fromTs }.sortedBy { it.ts }
    }

    /**
     * 18/11 07:15-07:24 com so a 99 ligada: #23 (Negocia) aceita sozinha, voce cancelou em 1 min e aceitou
     * NA MAO a de R$ 5,00. Antes: #23 virava "concluida" e a de R$ 5,00 nem entrava no painel.
     */
    @Test
    fun cancelThenManualAcceptGoToTheRightRidesWithOnlyNineNine() {
        val store = FakeStore()
        val t = RideLedgerTracker(store) { }
        val base = 1_763_456_143_000L
        fun screen(lines: List<String>, at: Long): ParsedRideOffer {
            val p = parse(lines)!!
            t.onScreen(p, at)
            return p
        }

        val negocia = screen(
            listOf(
                "Moto Negocia", "R$6,77", "0% de Taxa99 semanal", "4,90 +999 corridas", "CPF e Cartão verif.",
                "O5 min (2,2 km)", "Rua Uranos, 761, Ramos", "7 min (1,8 km)", "Avenida Paris, 568, Bonsucesso",
                "Aceitar por R$6,77", "R$7,11 R$7,45", "R$7,79"
            ), base
        )
        assertTrue(negocia.isRideOffer)
        t.onOfferEvaluated(negocia, "INSIDE_AREA", 0.3, base)
        t.onAccepted(negocia, base)

        screen(listOf("Moto Negocia", "Adilson >", "4,90* +999 corridas", "Rua Uranos, 761, Ramos, Rio de Janeiro - RJ,", "Cancelar Corrida"), base + 44_000L)
        screen(listOf("Você cancelou a corrida", "Você cancelou a corrida antes de chegar ao local de embarque", "Política de cancelamento"), base + 52_000L)
        assertEquals(EarningsStore.STATUS_CANCELLED, store.rows.getValue(1L).status)

        val manual = screen(
            listOf(
                "Moto", "R$5,00", "0% de Taxa99 semanal", "R\$O,56 Tarifa base dinâmica incl.", "4,90 +999 corridas",
                "Perfil Essencial", "O4 min (1,5 km)", "Rua Cardoso de Morais, 336, Bonsucesso", "3 min (1,2 km)",
                "Avenida Brasil, 5843, Bonsucesso", "Aceitar"
            ), base + 59_000L
        )
        assertTrue(manual.isRideOffer)
        t.onOfferEvaluated(manual, "INSIDE_AREA", 0.2, base + 59_000L)
        screen(acceptedCard0716(), base + 64_000L)
        assertEquals(2, store.rows.size)
        assertEquals(EarningsStore.SOURCE_MANUAL, store.rows.getValue(2L).source)
        assertEquals(5.00, store.rows.getValue(2L).amount, 0.001)

        screen(listOf("Como foi sua corrida com Estela?", "Avalie"), base + 9 * 60_000L)
        assertEquals(EarningsStore.STATUS_DONE, store.rows.getValue(2L).status)
        assertEquals(EarningsStore.STATUS_CANCELLED, store.rows.getValue(1L).status)
    }

    // ---------------- Negocia ----------------

    // 07:59:01 (print 59192): Moto Negocia R$ 21,13 para Copacabana, botoes R$22,19 / R$23,24 / R$24,30
    private val negocia0759 = listOf(
        "Moto Negocia", "R$21,13 1,3x", "R$1,07/km", "0% de Taxa99 semanal", "t 4,87 584 corridas",
        "V CPF e Cartão verif.", "O5 min (2,6 km)", "Rua Leopoldo Bulhões, 800 - Rua Leopoldo", "de Bulhões",
        "28 min (17 km)", "Avenida Nossa Sra. de Copacabana, 386,", "Copacabana",
        "Aceitar por R$21,13", "R$22,19 R$23,24", "R$24,30"
    )

    private val priceRules = FilterConfig(
        nineNineModuleEnabled = true, uberModuleEnabled = false,
        filterByPricePerKmEnabled = true, minPricePerKm = 1.20,
        filterByHourlyRateEnabled = true, minHourlyRate = 30.0, useNetHourlyRate = false,
        filterByPickupDistEnabled = false
    )

    @Test
    fun negociaCardIsReadWithTheSuggestedButtons() {
        val p = parse(negocia0759)!!
        assertTrue(p.isRideOffer)
        assertTrue(p.isNegotiable)
        assertEquals(21.13, p.ridePrice!!, 0.001)
        assertEquals(19.6, p.totalDistanceKm!!, 0.001)
        assertEquals(listOf(22.19, 23.24, 24.30), p.counterOfferPrices)
        // 06:51:51: os botoes vieram fora de ordem no OCR ("R$8,87" e depois "R$8,46 R$8,70")
        val p2 = parse(
            listOf(
                "Moto Negocia", "R$8,06", "R$1,18/km", "0% de Taxa99 semanal", "4,93 306 corridas", "Perfil Premium",
                "O3 min (1,3 km)", "Rua André Pinto, 24, Ramos", "10 min (5,5 km)", "Rua Ourique, 451, Penha Circular",
                "Aceitar por R$8,06", "R$8,87", "R$8,46 R$8,70"
            )
        )!!
        assertEquals(listOf(8.46, 8.70, 8.87), p2.counterOfferPrices)
    }

    @Test
    fun negociaAdviceSaysWhichButtonReachesYourRules() {
        val p = parse(negocia0759)!!
        val a = NegotiationAdvisor.advise(p, priceRules)!!
        assertFalse(a.passesAtPassengerPrice)
        assertEquals(23.52, a.minPrice, 0.001)      // 19,6 km x R$ 1,20
        assertEquals(24.30, a.button!!, 0.001)      // R$ 23,24 nao alcanca; R$ 24,30 sim
        assertEquals(12, a.raisePercent)
        assertTrue(a.withinReach)
        assertEquals("Negocie: peça 24,30", NegotiationAdvisor.title(a))
        assertTrue(NegotiationAdvisor.cardLines(a).any { it.contains("toque no botão 24,30") })

        // O minimo bate com o avaliador de verdade: no minimo passa, 1 centavo abaixo nao
        val evaluator = AreaEvaluator()
        val atMin = runBlocking { evaluator.evaluate(NegotiationAdvisor.withPrice(p, a.minPrice), priceRules) }
        val below = runBlocking { evaluator.evaluate(NegotiationAdvisor.withPrice(p, a.minPrice - 0.01), priceRules) }
        assertEquals(Decision.UNKNOWN_KEEP, atMin.decision) // sem zonas no teste: nenhuma regra violada
        assertEquals(Decision.OUTSIDE_AREA, below.decision)
        assertTrue(below.reason.contains("R$/km"))
    }

    @Test
    fun negociaAdviceWithNetHourlyAndNoButtonBigEnough() {
        // 08:07 (print 59196): R$ 5,10, 4 min (1,3 km) + 9 min (4,4 km), botoes ate R$ 5,87
        val p = parse(
            listOf(
                "Moto Negocia", "R$5,10 1,1x", "0% de Taxa99 semanal", "4,85 719 corridas", "Perfil Essencial",
                "O4 min (1,3 km)", "Rua João Romariz, 52, Ramos", "9 min (4,4 km)", "Estrada Adhemar Bebiano, 177, Del Castilho",
                "Aceitar por R$5,10", "R$5,36 R$5,61", "R$5,87"
            )
        )!!
        assertTrue(p.isNegotiable)
        // So R$/km: 5,7 km x R$ 1,20 = R$ 6,84 -> nenhum botao alcanca, digitar 6,90 no lapis
        val perKmOnly = priceRules.copy(filterByHourlyRateEnabled = false)
        val a = NegotiationAdvisor.advise(p, perKmOnly)!!
        assertEquals(6.84, a.minPrice, 0.001)
        assertNull(a.button)
        assertEquals(6.9, a.typedValue, 0.001)
        assertTrue(a.withinReach)
        assertTrue(NegotiationAdvisor.cardLines(a).any { it.contains("no lápis, digite 6,90") })

        // R$/h LIQUIDO 30 (gasolina 6,29 a 35 km/l): 4+9 min +2 de espera = 15 min -> precisa R$ 8,53 (+68%)
        val netCfg = priceRules.copy(useNetHourlyRate = true)
        val b = NegotiationAdvisor.advise(p, netCfg)!!
        assertEquals(8.53, b.minPrice, 0.001)
        assertEquals(68, b.raisePercent)
        assertFalse(b.withinReach)                     // mais de +50%: recusa normal
        assertTrue(NegotiationAdvisor.passesPriceRules(NegotiationAdvisor.withPrice(p, b.minPrice), netCfg))
        assertFalse(NegotiationAdvisor.passesPriceRules(NegotiationAdvisor.withPrice(p, b.minPrice - 0.01), netCfg))
    }

    @Test
    fun negociaThatAlreadyPassesAndOneTooFarAway() {
        val p = parse(negocia0759)!!
        val loose = priceRules.copy(minPricePerKm = 1.0, minHourlyRate = 20.0)
        val ok = NegotiationAdvisor.advise(p, loose)!!
        assertTrue(ok.passesAtPassengerPrice)
        assertEquals("Negocie: valor já passa", NegotiationAdvisor.title(ok))

        val strict = priceRules.copy(minPricePerKm = 2.5)
        val far = NegotiationAdvisor.advise(p, strict)!!
        assertEquals(49.0, far.minPrice, 0.001)       // 19,6 km x R$ 2,50 = +132%
        assertFalse(far.withinReach)
        assertTrue(NegotiationAdvisor.cardLines(far).single().contains("precisaria +132%"))
    }

    @Test
    fun negociaOwnHourlyReplacesTheDefaultOnlyWhenEnabled() {
        // padrao R$ 50/h; Negocia ligada com R$ 40/h -> vale 40 (so nas Negocia)
        val p = parse(negocia0759)!!                      // 5 + 28 min + 2 de espera = 35 min
        val base = priceRules.copy(filterByPricePerKmEnabled = false, minHourlyRate = 50.0)
        val off = base.copy(negociaHourlyEnabled = false, negociaMinHourlyRate = 40.0)
        val on = base.copy(negociaHourlyEnabled = true, negociaMinHourlyRate = 40.0)
        assertEquals(50.0, off.forNegotiation().minHourlyRate, 0.001)
        assertEquals(40.0, on.forNegotiation().minHourlyRate, 0.001)
        assertEquals(29.17, NegotiationAdvisor.advise(p, off.forNegotiation())!!.minPrice, 0.001) // 50/h
        assertEquals(23.34, NegotiationAdvisor.advise(p, on.forNegotiation())!!.minPrice, 0.001)  // 40/h
        // as outras regras continuam: R$/km 1,20 ainda pede 23,52
        val withKm = on.copy(filterByPricePerKmEnabled = true, minPricePerKm = 1.20)
        assertEquals(23.52, NegotiationAdvisor.advise(p, withKm.forNegotiation())!!.minPrice, 0.001)
        // ligar a Negocia liga o filtro de R$/h para ela, mesmo com o R$/h padrao desligado
        assertTrue(on.copy(filterByHourlyRateEnabled = false).forNegotiation().filterByHourlyRateEnabled)
    }

    @Test
    fun counterOfferEditorScreenIsNeverAnOffer() {
        // 11:26 (print 59332): lapis da Negocia aberto ("Sua oferta" + teclado). Nao pode tocar em nada.
        val editor = listOf(
            "Moto Negocia", "R$23,88", "R$1,42/km", "0% de Taxa99 semanal", "321 corridas",
            "Ofertas menores têm mais chances!", "Sua oferta", "A oferta feita pelo passageiro é de R$23,88",
            "-0,5", "R$23,9", "+0,5", "Confirmar oferta", "Senhas", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0"
        )
        val p = parse(editor)
        assertTrue(p == null || !p.isRideOffer)
        // mesmo se os trechos aparecessem atras do teclado
        val withLegs = editor.toMutableList().apply {
            addAll(5, listOf("5 min (2,6 km)", "Rua Leopoldo Bulhões, 800", "12 min (6,2 km)", "Rua Uranos, 1, Ramos", "Aceitar por R$23,88"))
        }
        val p2 = parse(withLegs)
        assertTrue(p2 == null || !p2.isRideOffer)
    }

    @Test
    fun ourNegociaCardTextIsNeverReadAsTheNinetyNineCard() {
        // nosso card da Negocia ainda na tela quando chega uma corrida normal da 99
        val ownCard = listOf(
            "Negocie: peça 24,30", "VOCÊ NEGOCIA", "💬 Negocie: suas regras pedem 23,52 (+12%)",
            "💬 Negocie: toque no botão 24,30", "Negocie: valor já passa", "💬 Negocie: no lápis, digite 6,90",
            "Negocie: pedindo 23,60", "NEGOCIANDO", "💬 Negocie: o app está pedindo 23,60",
            "💬 Negocie: pedido de 23,60 enviado", "Negocie: digite 23,60", "💬 Negocie: não consegui enviar sozinho"
        )
        ownCard.forEach { line ->
            val norm = TextNormalizer.normalize(line)
            assertTrue(line, RideOfferParser.COMMON_OWN_OVERLAY_MARKERS.any { norm.contains(it) })
        }
        val p = parse(ownCard + card0635("O6 min (2,7 km)"))!!
        assertTrue(p.isRideOffer)
        assertFalse(p.isNegotiable)
        assertEquals(13.00, p.ridePrice!!, 0.001)
    }
}
