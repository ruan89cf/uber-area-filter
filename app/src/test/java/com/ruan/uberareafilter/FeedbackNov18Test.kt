package com.ruan.uberareafilter

import com.ruan.uberareafilter.driver.AppCaptureRouter
import com.ruan.uberareafilter.engine.AreaEvaluator
import com.ruan.uberareafilter.engine.BlockedMatcher
import com.ruan.uberareafilter.engine.BlockedPlace
import com.ruan.uberareafilter.engine.ChainPlanner
import com.ruan.uberareafilter.engine.Decision
import com.ruan.uberareafilter.engine.DriverPresence
import com.ruan.uberareafilter.engine.DualRideCoordinator
import com.ruan.uberareafilter.engine.OfferSignature
import com.ruan.uberareafilter.engine.ParsedRideOffer
import com.ruan.uberareafilter.engine.RideLedgerTracker
import com.ruan.uberareafilter.engine.RideProgress
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.model.RunState
import com.ruan.uberareafilter.storage.EarningsStore
import com.ruan.uberareafilter.storage.LedgerStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Problemas relatados em 18/11 (log de 17/11). */
class FeedbackNov18Test {

    // ------------------------------------------------------------------ helpers
    private fun offer(
        pickup: String, dropoff: String, price: Double? = 10.0, hourly: Double? = 40.0,
        pickupKm: Double? = 1.0, dropoffKm: Double? = 3.0, app: String = "99",
        offerScreen: Boolean = true, active: Boolean = false, cancelled: Boolean = false,
        rating: Boolean = false, idle: Boolean = false, texts: List<String> = emptyList(), fp: String = ""
    ) = ParsedRideOffer(
        isRideOffer = offerScreen, isContinuePrompt = false, isRatingScreen = rating, ratingPassengerName = null,
        isInActiveRide = active, hasRiskAreaBadge = false, allTexts = texts.ifEmpty { listOf(pickup, dropoff) },
        pickupAddress = pickup, dropoffAddress = dropoff, ridePrice = price,
        pickupDistanceKm = pickupKm, dropoffDistanceKm = dropoffKm,
        totalDistanceKm = if (pickupKm != null && dropoffKm != null) pickupKm + dropoffKm else null,
        pricePerKm = price?.let { p -> (pickupKm ?: 0.0).plus(dropoffKm ?: 0.0).takeIf { it > 0 }?.let { p / it } },
        hourlyRate = hourly, passengerRating = 4.9, isNewPassenger = false, addressCandidates = emptyList(),
        rejectNode = null, rejectTapPoint = null, secondaryRejectTapPoint = null, rejectTapTargets = emptyList(),
        hasNaoAfetaTaPill = false, acceptNode = null, acceptTapPoint = null, continueNode = null,
        continueTapPoint = null, fingerprint = fp.ifEmpty { "$app|$pickup|$dropoff" }, appSource = app,
        isCancelledScreen = cancelled, isIdleHomeScreen = idle
    )

    private class FakeStore : LedgerStore {
        val rows = LinkedHashMap<Long, EarningsStore.Entry>()
        var onlineSec = 0L
        private var next = 1L
        override fun insert(e: EarningsStore.Entry): Long { val id = next++; rows[id] = e.copy(id = id); return id }
        override fun setStatus(id: Long, status: String): Boolean { rows[id] = rows.getValue(id).copy(status = status); return true }
        override fun setMinutes(id: Long, minutes: Double): Boolean { rows[id] = rows.getValue(id).copy(minutes = minutes); return true }
        override fun addOnline(ts: Long, seconds: Long) { onlineSec += seconds }
        override fun countOffer(ts: Long) {}
        override fun countRejected(ts: Long, reasonKeys: List<String>) {}
        override fun countAccepted(ts: Long) {}
        override fun openRidesSince(fromTs: Long) =
            rows.values.filter { it.status == EarningsStore.STATUS_OPEN && it.ts >= fromTs }.sortedBy { it.ts }
    }

    private val shopping = "Caxias Shopping, Rodovia Washington Luiz, 2895 - Parque Duque"
    private val entradaNorte = "Entrada norte, Rod. Washington Luíz, 2895 - Parque Duque"

    // ------------------------------------------------------------------ 1. bloqueio pelo endereco
    @Test
    fun blockedNameLearnsAddressAndCatchesOtherEntranceName() {
        val place = BlockedMatcher.learn("caxias shopping", shopping)
        assertNotNull(place)
        assertEquals("2895", place!!.number)
        // "Entrada norte" (outro nome do mesmo lugar) agora e pego pelo endereco
        val hit = BlockedMatcher.matchAddress(listOf(entradaNorte, "Rua X, 10 - Centro"), emptyList(), listOf(place))
        assertNotNull(hit)
        assertTrue(hit!!.contains("caxias shopping"))
        // outro numero da mesma rodovia continua livre
        assertNull(BlockedMatcher.matchAddress(listOf("Lions Seminovos, Rodovia Washington Luiz, 1375 - Parque Duque"), emptyList(), listOf(place)))
        // bairro bloqueado nao vira "local" (so o nome do POI aprende endereco)
        assertNull(BlockedMatcher.learn("parque duque", shopping))
    }

    @Test
    fun evaluatorRejectsSamePlaceWithOtherNameAndLearnsFromOffer() = runBlocking {
        val evaluator = AreaEvaluator(null)
        val base = FilterConfig(blockedKeywords = listOf("caxias shopping"))
        // a oferta com o nome do shopping ensina o endereco
        val learned = evaluator.evaluate(offer("Rua A, 10 - Centro", shopping), base)
        assertEquals(Decision.OUTSIDE_AREA, learned.decision)
        assertEquals(1, learned.learnedBlockedPlaces.size)
        // com o endereco aprendido, "Entrada norte" e recusada
        val cfg = base.copy(blockedPlaces = learned.learnedBlockedPlaces)
        val res = evaluator.evaluate(offer(entradaNorte, "Condominio Nova Esperança, Rua Maurício de Abreu, 105 - Vila Oito de Maio"), cfg)
        assertEquals(Decision.OUTSIDE_AREA, res.decision)
        assertTrue(res.reason, res.reason.contains("caxias shopping"))
        // sem o endereco aprendido, passava (o erro do dia 17/11)
        val before = evaluator.evaluate(offer(entradaNorte, "Condominio Nova Esperança, Rua Maurício de Abreu, 105 - Vila Oito de Maio"), base)
        assertFalse(before.reason.contains("Bloqueado"))
    }

    @Test
    fun addressKeywordBlocksOnlyThatNumber() = runBlocking {
        val evaluator = AreaEvaluator(null)
        val cfg = FilterConfig(blockedKeywords = listOf("Rodovia Washington Luiz nº 2895"))
        assertEquals(Decision.OUTSIDE_AREA, evaluator.evaluate(offer(entradaNorte, "Rua B, 20 - Centro"), cfg).decision)
        val other = evaluator.evaluate(offer("Rod. Washington Luiz, 2113, Pqe", "Rua B, 20 - Centro"), cfg)
        assertFalse(other.reason.contains("Bloqueado"))
    }

    // ------------------------------------------------------------------ 2 e 5. cancelamento feito pelo motorista
    @Test
    fun driverCancellationScreenIsDetectedButReasonListIsNot() {
        val cfg = FilterConfig()
        val cancelled = AppCaptureRouter.parseOrderedLines(
            listOf("Você cancelou a corrida", "Você cancelou a corrida antes de chegar ao local de embarque",
                "Política de cancelamento", "Ajuda", "Confirm").map { it to null },
            null, 1220, 2712, cfg, preferredAppSource = "99"
        )
        assertNotNull(cancelled)
        assertTrue(cancelled!!.isCancelledScreen)
        // Tela de motivos: o motorista ainda pode desistir de cancelar
        val reasons = AppCaptureRouter.parseOrderedLines(
            listOf("Por favor, nos conte o motivo do", "cancelamento", "Pickup too far away", "Wrong pickup point").map { it to null },
            null, 1220, 2712, cfg, preferredAppSource = "99"
        )
        assertTrue(reasons == null || !reasons.isCancelledScreen)
    }

    @Test
    fun ledgerAndLockReleaseOnDriverCancel() {
        val store = FakeStore()
        val ledger = RideLedgerTracker(store) {}
        val o = offer(entradaNorte, "Condominio Nova Esperança, Rua Maurício de Abreu, 105")
        ledger.onScreen(o, 0L)
        ledger.onOfferEvaluated(o, "INSIDE_AREA", 2.0, 0L)
        ledger.onAccepted(o, 0L)
        ledger.onScreen(offer("", "", offerScreen = false, active = true), 8_000L)
        ledger.onScreen(offer("", "", offerScreen = false, cancelled = true), 48_000L)
        assertEquals(EarningsStore.STATUS_CANCELLED, store.rows.getValue(1).status)
    }

    @Test
    fun acceptedByMistakeThenBackToHomeIsCancelledNotDone() {
        val store = FakeStore()
        val ledger = RideLedgerTracker(store) {}
        val base = 1_700_000_000_000L
        val o = offer(entradaNorte, "Condominio Nova Esperança, Rua Maurício de Abreu, 105", price = 19.0, hourly = 48.0)
        ledger.onScreen(o, base)
        ledger.onOfferEvaluated(o, "INSIDE_AREA", 2.0, base)
        ledger.onAccepted(o, base)
        ledger.onScreen(offer("", "", offerScreen = false, active = true, texts = listOf("Cheguei", "Entrada norte")), base + 8_000L)
        ledger.onScreen(offer("", "", offerScreen = false, idle = true), base + 60_000L)
        assertEquals(EarningsStore.STATUS_CANCELLED, store.rows.getValue(1).status)
        // corrida que iniciou a viagem ("Finalizar corrida") e voltou para a tela livre = concluida
        val o2 = offer("Rua A, 1 - Centro", "Rua B, 2 - Centro", fp = "x2")
        ledger.onScreen(o2, base + 120_000L)
        ledger.onOfferEvaluated(o2, "INSIDE_AREA", 0.5, base + 120_000L)
        ledger.onAccepted(o2, base + 120_000L)
        ledger.onScreen(offer("", "", offerScreen = false, active = true, texts = listOf("Finalizar corrida")), base + 130_000L)
        ledger.onScreen(offer("", "", offerScreen = false, idle = true), base + 160_000L)
        assertEquals(EarningsStore.STATUS_DONE, store.rows.getValue(2).status)
    }

    // ------------------------------------------------------------------ 3. horas online
    @Test
    fun hoursCountWhileOffersArriveNotTheTailAfterward() {
        val store = FakeStore()
        val ledger = RideLedgerTracker(store) {}
        val base = 1_700_000_000_000L
        val offers = setOf(base, base + 300_000L, base + 600_000L) // 0, 5 e 10 min
        var t = base
        while (t <= base + 15 * 60_000L) {
            if (t in offers) {
                val o = offer("Rua A, $t", "Rua B, 1", fp = "f$t")
                ledger.onScreen(o, t)
                ledger.onOfferEvaluated(o, "OUTSIDE_AREA", 0.0, t)
            }
            ledger.tickWork(t, RunState.ON)
            t += 1000L
        }
        ledger.flush(t)
        // 0 -> 10 min contados; os 5 min depois da ultima oferta ainda nao (pode ser que tenha ido embora)
        assertEquals(600.0, store.onlineSec.toDouble(), 5.0)
    }

    @Test
    fun longPauseAndOfflineScreenAreNotCounted() {
        val store = FakeStore()
        val ledger = RideLedgerTracker(store) {}
        val base = 1_700_000_000_000L
        var t = base
        while (t <= base + 40 * 60_000L) {
            val rel = t - base
            if (rel == 0L || rel == 120_000L || rel == 30 * 60_000L) {
                val o = offer("Rua A, $rel", "Rua B, 1", fp = "g$rel")
                ledger.onScreen(o, t)
                ledger.onOfferEvaluated(o, "OUTSIDE_AREA", 0.0, t)
            }
            if (rel == 180_000L) ledger.onPresence("99", online = false, now = t) // "Voce esta offline"
            ledger.tickWork(t, RunState.ON)
            t += 1000L
        }
        ledger.flush(t)
        // So os 2 primeiros minutos (0 -> 2 min). Offline e a pausa de 27 min nao contam.
        assertEquals(120.0, store.onlineSec.toDouble(), 5.0)
    }

    @Test
    fun rideInProgressCountsEvenWithoutScreensForAWhileAndSavesRealDuration() {
        val store = FakeStore()
        val ledger = RideLedgerTracker(store) {}
        val base = 1_700_000_000_000L
        val o = offer("Rua A, 1", "Rua B, 2", fp = "r1")
        ledger.onScreen(o, base)
        ledger.onOfferEvaluated(o, "INSIDE_AREA", 0.5, base)
        ledger.onAccepted(o, base)
        var t = base
        while (t <= base + 30 * 60_000L) {
            val rel = t - base
            if (rel == 10_000L || rel == 25 * 60_000L) ledger.onScreen(offer("", "", offerScreen = false, active = true), t)
            ledger.tickWork(t, RunState.ON)
            t += 1000L
        }
        ledger.onScreen(offer("", "", offerScreen = false, rating = true), t)
        ledger.flush(t)
        assertEquals(30 * 60.0, store.onlineSec.toDouble(), 10.0)
        assertEquals(EarningsStore.STATUS_DONE, store.rows.getValue(1).status)
        assertEquals(30.0, store.rows.getValue(1).minutes, 0.5)
    }

    @Test
    fun ghostRidesAreNotRestoredForeverAndExpire() {
        val store = FakeStore()
        val now = 10 * 3_600_000L
        store.insert(EarningsStore.Entry(ts = now - 3 * 3_600_000L, kind = EarningsStore.KIND_RIDE, app = "99", amount = 5.3, status = EarningsStore.STATUS_OPEN))
        store.insert(EarningsStore.Entry(ts = now - 20 * 60_000L, kind = EarningsStore.KIND_RIDE, app = "99", amount = 5.1, status = EarningsStore.STATUS_OPEN))
        val ledger = RideLedgerTracker(store) {}
        ledger.restoreOpenRides(now)
        assertEquals(EarningsStore.STATUS_DONE, store.rows.getValue(1).status) // velha demais: concluida
        assertEquals(1, ledger.openCount("99"))
        // 45 min sem a tela da corrida: o app fecha sozinho
        ledger.tickWork(now + 46 * 60_000L, RunState.ON)
        assertEquals(EarningsStore.STATUS_DONE, store.rows.getValue(2).status)
        assertFalse(ledger.hasOpenRide())
    }

    @Test
    fun restoredRideDoesNotCountHoursOrBlockTheQueueUntilSeenAgain() {
        val store = FakeStore()
        val now = 10 * 3_600_000L
        store.insert(EarningsStore.Entry(ts = now - 45 * 60_000L, kind = EarningsStore.KIND_RIDE, app = "99", amount = 5.2, status = EarningsStore.STATUS_OPEN))
        val ledger = RideLedgerTracker(store) {}
        ledger.restoreOpenRides(now)
        var t = now
        while (t <= now + 10 * 60_000L) { ledger.tickWork(t, RunState.ON); t += 1000L }
        ledger.flush(t)
        assertEquals(0L, store.onlineSec) // em casa, sem oferta nem tela da corrida: nao conta hora
        // chega corrida nova: a retomada (fantasma) sai da frente e a nova vira a atual
        val o = offer("Rua A, 1 - Centro", "Rua B, 2 - Centro", fp = "n1")
        ledger.onScreen(o, t)
        ledger.onOfferEvaluated(o, "INSIDE_AREA", 0.3, t)
        ledger.onAccepted(o, t)
        assertEquals(EarningsStore.STATUS_DONE, store.rows.getValue(1).status)
        assertEquals(1, ledger.openCount("99"))
        ledger.onScreen(offer("", "", offerScreen = false, rating = true), t + 8 * 60_000L)
        assertEquals(EarningsStore.STATUS_DONE, store.rows.getValue(2).status)
    }

    @Test
    fun presenceScreens() {
        assertEquals(false, DriverPresence.detect(listOf("13:25", "Você está offline", "Tudo pronto?", "Ficar online")))
        assertEquals(true, DriverPresence.detect(listOf("R$ 0,00", "Você está online", "Procurando viagens")))
        assertNull(DriverPresence.detect(listOf("A demanda está alta agora", "Voltar a ficar online", "Ficar offline")))
        assertNull(DriverPresence.detect(listOf("Rua A", "Finalizar corrida")))
    }

    // ------------------------------------------------------------------ 4. "Aceitar se tocar de novo"
    @Test
    fun sameRideRingingAgainIsRecognizedDespiteOcrNoise() {
        val a = OfferSignature.of("99", "Rua Marta de Souza Renha, 201, Parque Santa Marta", "Praça do Parque das Missões, Rua Dois")!!
        val b = OfferSignature.of("99", "Rua Marta de SouZa Renha, 201 Parque Santa Marta", "Praca do Parque das Missoes Rua Dois")!!
        assertTrue(a.matches(b))
        val other = OfferSignature.of("99", "Rua Marta de Souza Renha, 350, Parque Santa Marta", "Praça do Parque das Missões, Rua Dois")!!
        assertFalse(a.matches(other))
        val uber = OfferSignature.of("UBER", "Rua Marta de Souza Renha, 201, Parque Santa Marta", "Praça do Parque das Missões, Rua Dois")!!
        assertFalse(a.matches(uber))
    }

    // ------------------------------------------------------------------ 7. encaixe 99 <-> Uber
    @Test
    fun chainRecomputesPickupFromCurrentDestination() = runBlocking {
        val uber = offer("Rua C, 30 - Penha", "Rua D, 40 - Centro", price = 10.0, hourly = 40.0, pickupKm = 0.8, dropoffKm = 3.0, app = "UBER")
        val planner = ChainPlanner { addr -> if (addr.contains("Destino 99")) 0.0 to 0.0 else 0.0 to 0.01 }
        val plan = planner.plan("99", "Destino 99, 100 - Olaria", uber, remainingMin = 1.0, maxTotalMin = 5)!!
        assertEquals(1.5, plan.chainKm, 0.05)                 // ~1,1 km em linha reta x 1,35
        assertEquals(1.5, plan.adjusted.pickupDistanceKm!!, 0.05)
        assertEquals(4.5, plan.adjusted.totalDistanceKm!!, 0.05)
        assertTrue(plan.adjusted.hourlyRate!! < 40.0)          // busca maior = R$/h menor
        assertEquals(3.6, plan.pickupMin, 0.05)               // 1,5 km x 2,4 min/km
        assertTrue(plan.waitOk)                                // falta 1 + 3,6 = 4,6 <= 5
        assertTrue(plan.summary().contains("Depois da 99"))
        assertFalse(planner.plan("99", "Destino 99, 100 - Olaria", uber, remainingMin = null, maxTotalMin = 5)!!.waitOk)
        assertFalse(planner.plan("99", "Destino 99, 100 - Olaria", uber, remainingMin = 2.0, maxTotalMin = 5)!!.waitOk) // 5,6 > 5
    }

    /** Regra do motorista: limite 3 min = o que falta da corrida + ida ate o proximo passageiro. */
    @Test
    fun chainTimeBudgetIsRemainingPlusTimeToNextPickup() = runBlocking {
        val uber = offer("Rua C, 30 - Penha", "Rua D, 40 - Centro", pickupKm = 0.8, dropoffKm = 3.0, app = "UBER")
        fun plannerAt(lng: Double) = ChainPlanner { addr -> if (addr.contains("Destino 99")) 0.0 to 0.0 else 0.0 to lng }
        // embarque a ~0,4 km do destino = ~1 min
        val near = plannerAt(0.0027).plan("99", "Destino 99, 100", uber, remainingMin = 2.0, maxTotalMin = 3)!!
        assertEquals(0.4, near.chainKm, 0.05)
        assertTrue(near.timeText(), near.waitOk)              // 2 + ~1 = 3: aceita
        // faltando 3 min, o mesmo embarque estoura
        assertFalse(plannerAt(0.0027).plan("99", "Destino 99, 100", uber, remainingMin = 3.0, maxTotalMin = 3)!!.waitOk)
        // mais perto do destino libera mais tempo para a ida: faltando 1 min, embarque a ~2 min passa
        val farther = plannerAt(0.0055).plan("99", "Destino 99, 100", uber, remainingMin = 1.0, maxTotalMin = 3)!!
        assertEquals(0.8, farther.chainKm, 0.05)
        assertTrue(farther.timeText(), farther.waitOk)        // 1 + ~1,9 = 2,9
        assertTrue(near.timeText().contains("= 3 min"))
    }

    @Test
    fun chainedOfferGoesThroughTheSameRules() = runBlocking {
        val evaluator = AreaEvaluator(null)
        val cfg = FilterConfig(filterByPickupDistEnabled = true, maxPickupDistanceKm = 2.0)
        val uber = offer("Rua C, 30 - Penha", "Rua D, 40 - Centro", pickupKm = 0.8, dropoffKm = 3.0, app = "UBER")
        // busca real de 4 km a partir do destino da 99 -> recusa por "Emb. longe" (o card dizia 0,8 km)
        val far = ChainPlanner.adjustOffer(uber, 4.0)
        val res = evaluator.evaluate(far, cfg)
        assertEquals(Decision.OUTSIDE_AREA, res.decision)
        assertTrue(res.reason, res.reason.contains("Emb. longe"))
    }

    @Test
    fun rideProgressReadsEtaAndStage() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val trip = RideProgress.parse(listOf("3 min", "671 m", "Chegada prevista: 21:34", "Finalizar corrida"), now)
        assertEquals(RideProgress.Stage.TRIP, trip.stage)
        assertEquals(4, trip.etaMinutes)
        val pickup = RideProgress.parse(listOf("Cheguei", "Chegada prevista: 21:33"), now)
        assertEquals(RideProgress.Stage.PICKUP, pickup.stage)
        assertEquals(3, pickup.etaMinutes)
    }

    @Test
    fun coordinatorKnowsHowLongTheCurrentRideTakesAndHandlesQueuedCancel() {
        val coord = DualRideCoordinator()
        val cfg = FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = true, dualConflictGuardEnabled = true)
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val first = offer("Rua A, 10 - Penha", "Destino 1, 100 - Olaria", fp = "c1")
        coord.onRideAccepted(first, cfg, "R$ 10,00", now)
        assertEquals(RideProgress.Stage.UNKNOWN, coord.activeStage())
        // indo buscar o passageiro: ainda NAO pode encaixar
        coord.onScreenObserved(
            offer("", "", offerScreen = false, active = true, texts = listOf("Cheguei", "Chegada prevista: 21:31")), cfg, now
        )
        assertEquals(RideProgress.Stage.PICKUP, coord.activeStage())
        // passageiro a bordo ("Finalizar corrida"): agora vale a regra do encaixe
        coord.onScreenObserved(
            offer("", "", offerScreen = false, active = true, texts = listOf("Chegada prevista: 21:35", "Finalizar corrida")), cfg, now + 1000L
        )
        assertEquals(RideProgress.Stage.TRIP, coord.activeStage())
        assertEquals(5.0, coord.remainingMinutes(now + 1000L)!!, 0.1)
        assertEquals(3.0, coord.remainingMinutes(now + 2 * 60_000L + 1000L)!!, 0.1)
        // proxima corrida da 99 na fila; passageiro dela cancela -> a atual continua
        coord.onRideAccepted(offer("Rua B, 20 - Olaria", "Destino 2, 200 - Ramos", fp = "c2"), cfg, "R$ 11,00", now + 60_000L)
        assertNotNull(coord.queuedRide)
        val evt = coord.onScreenObserved(offer("", "", offerScreen = false, cancelled = true, texts = listOf("Passageiro cancelou a corrida.")), cfg, now + 90_000L)
        assertFalse(evt!!.unlocked)
        assertTrue(coord.primaryRide!!.dropoffAddress.contains("Destino 1"))
        assertNull(coord.queuedRide)
    }

    @Test
    fun rejectedOfferNeverBecomesTheLockedRide() {
        val coord = DualRideCoordinator()
        val cfg = FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = true, dualConflictGuardEnabled = true)
        // 22:14:53: oferta para "Rua das Paineiras" recusada; logo depois a tela da corrida real aparece
        coord.rememberCandidateOffer(offer("Rua Itabira, 729, Itatiaia", "Rua das Paineiras, 107, Vila Leopoldina"), "R$ 5,00", 0L)
        coord.forgetCandidateOffer("99")
        coord.onScreenObserved(offer("", "", offerScreen = false, active = true, texts = listOf("Finalizar corrida")), cfg, 3_000L)
        assertFalse(coord.primaryRide!!.dropoffAddress.contains("Paineiras"))
    }

    @Test
    fun outsideReasonSaysHowFar() {
        assertEquals("350 m", com.ruan.uberareafilter.engine.LocationRules.outsideBy(-356.0))
        assertEquals("1,8 km", com.ruan.uberareafilter.engine.LocationRules.outsideBy(-1830.0))
    }

    @Test
    fun blockedPlaceNearPoint() {
        val p = BlockedPlace("caxias shopping", "Rodovia Washington Luiz", "2895", -22.776, -43.300)
        assertNotNull(BlockedMatcher.nearPlace(-22.7765, -43.3005, listOf(p)))   // ~75 m
        assertNull(BlockedMatcher.nearPlace(-22.780, -43.300, listOf(p)))        // ~440 m
    }
}
