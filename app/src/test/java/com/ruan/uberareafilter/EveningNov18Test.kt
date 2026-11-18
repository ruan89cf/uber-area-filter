package com.ruan.uberareafilter

import android.graphics.Rect
import com.ruan.uberareafilter.driver.AppCaptureRouter
import com.ruan.uberareafilter.engine.DualRideCoordinator
import com.ruan.uberareafilter.engine.ParsedRideOffer
import com.ruan.uberareafilter.engine.RideLedgerTracker
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.storage.EarningsStore
import com.ruan.uberareafilter.storage.LedgerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registros de 18/11 a noite (17:28-19:52), com as posicoes reais das linhas na tela (1220x2712). */
class EveningNov18Test {

    private val only99 = FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = false)

    private fun box(top: Int, bottom: Int) = Rect().apply { left = 40; right = 1180; this.top = top; this.bottom = bottom }

    private fun parse(lines: List<Pair<String, Rect?>>, cfg: FilterConfig = only99): ParsedRideOffer? =
        AppCaptureRouter.parseOrderedLines(lines, null, 1220, 2712, cfg, preferredAppSource = "99")

    // 18:45:31: titulo no topo da tela (8% da altura). Antes o app ignorava o topo e nao via o cancelamento.
    @Test
    fun cancelScreenTitleAtTheTopIsRecognized() {
        val p = parse(
            listOf(
                "18:45" to box(49, 76),
                "Você cancelou a corrida" to box(203, 256),
                "Você cancelou a corrida antes de chegar ao local de embarque" to box(296, 332),
                "Política de cancelamento" to box(466, 501),
                "Ajuda" to box(626, 670),
                "• ON" to box(1007, 1034),
                "Confirm" to box(2448, 2492)
            )
        )
        assertNotNull(p)
        assertTrue(p!!.isCancelledScreen)
        // tambem no modo 99 + Uber
        val dual = parse(listOf("Você cancelou a corrida" to box(203, 256), "Política de cancelamento" to box(466, 501)),
            FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = true))
        assertTrue(dual!!.isCancelledScreen)
    }

    // 18:00:03 "J min (1,4 km)" e 18:02:49 "min (2,9 km)": minutos ilegiveis, km lido -> a corrida e lida
    @Test
    fun legWithUnreadableMinutesStillReadsTheOffer() {
        fun card(pickupLeg: String, dropLeg: String) = listOf(
            "Moto", "R\$5,90 1,2x", "0% de Taxa99 semanal", "4,91 308 corridas", "Cartão verif.",
            pickupLeg, "Caixa Econômica Federal, Rua Cardoso de", "Morais, NUM 251 - Bonsucesso",
            dropLeg, "Rua Uranos, 821, Manguinhos", "Aceitar"
        ).map { it to null }
        val a = parse(card("J min (1,4 km)", "3 min (1,2 km)"))!!
        assertTrue(a.isRideOffer)
        assertEquals(1.4, a.pickupDistanceKm!!, 0.001)
        assertEquals(1.2, a.dropoffDistanceKm!!, 0.001)
        assertTrue(a.pickupAddress!!.startsWith("Caixa"))
        val b = parse(card("4 min (1,6 km)", "min (2,9 km)"))!!
        assertTrue(b.isRideOffer)
        assertEquals(2.9, b.dropoffDistanceKm!!, 0.001)
        assertTrue(b.hourlyRate!! > 0.0)
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

    private fun screen(
        offer: Boolean = false, rating: Boolean = false, active: Boolean = false, cancelled: Boolean = false, fp: String = ""
    ) = ParsedRideOffer(
        isRideOffer = offer, isContinuePrompt = false, isRatingScreen = rating, ratingPassengerName = null,
        isInActiveRide = active, hasRiskAreaBadge = false, allTexts = emptyList(),
        pickupAddress = "Rua A", dropoffAddress = "Rua B $fp", ridePrice = 6.0,
        pickupDistanceKm = 1.0, dropoffDistanceKm = 2.0, totalDistanceKm = 3.0, pricePerKm = null,
        hourlyRate = 40.0, passengerRating = 4.9, isNewPassenger = false, addressCandidates = emptyList(),
        rejectNode = null, rejectTapPoint = null, secondaryRejectTapPoint = null, rejectTapTargets = emptyList(),
        hasNaoAfetaTaPill = false, acceptNode = null, acceptTapPoint = null, continueNode = null,
        continueTapPoint = null, fingerprint = fp, appSource = "99", isCancelledScreen = cancelled
    )

    // 18:19:50/51: a MESMA avaliacao lida 2x fechava a #32 e depois a #33 (que tinha acabado de virar a atual)
    @Test
    fun sameRatingScreenTwiceDoesNotCloseTheQueuedRide() {
        val store = FakeStore()
        val t = RideLedgerTracker(store) { }
        val base = 1_763_500_000_000L
        fun accept(fp: String, at: Long) {
            val o = screen(offer = true, fp = fp)
            t.onScreen(o, at); t.onOfferEvaluated(o, "INSIDE_AREA", 0.3, at); t.onAccepted(o, at)
        }
        accept("A", base)
        t.onScreen(screen(active = true), base + 30_000L)
        accept("B", base + 300_000L)                 // #2 entra na fila
        t.onScreen(screen(active = true), base + 301_000L)
        t.onScreen(screen(rating = true), base + 420_000L)
        t.onScreen(screen(rating = true), base + 421_000L) // 2a leitura da mesma tela
        assertEquals(EarningsStore.STATUS_DONE, store.rows.getValue(1L).status)
        assertEquals(EarningsStore.STATUS_OPEN, store.rows.getValue(2L).status)
        // a avaliacao da #2 depois da viagem dela fecha a #2
        t.onScreen(screen(active = true), base + 430_000L)
        t.onScreen(screen(rating = true), base + 900_000L)
        assertEquals(EarningsStore.STATUS_DONE, store.rows.getValue(2L).status)
    }

    @Test
    fun sameEndScreenTwiceDoesNotReleaseTheLockTwice() {
        val cfg = FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = true, dualConflictGuardEnabled = true)
        val c = DualRideCoordinator()
        c.onRideAccepted(screen(offer = true, fp = "A"), cfg, "R$ 6,00", 0L)
        c.onRideAccepted(screen(offer = true, fp = "B"), cfg, "R$ 6,00", 60_000L)
        c.onScreenObserved(screen(rating = true), cfg, 200_000L)   // #1 acabou -> #2 vira a atual
        assertNotNull(c.primaryRide)
        c.onScreenObserved(screen(rating = true), cfg, 201_000L)   // mesma tela de novo: nao solta a #2
        assertNotNull(c.primaryRide)
        c.onScreenObserved(screen(active = true), cfg, 210_000L)
        c.onScreenObserved(screen(rating = true), cfg, 700_000L)   // avaliacao da #2
        assertEquals(null, c.primaryRide)
    }
}
