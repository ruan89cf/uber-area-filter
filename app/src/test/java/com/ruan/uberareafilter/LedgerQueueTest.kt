package com.ruan.uberareafilter

import com.ruan.uberareafilter.driver.NineNineCaptureModule
import com.ruan.uberareafilter.engine.ParsedRideOffer
import com.ruan.uberareafilter.engine.RideLedgerTracker
import com.ruan.uberareafilter.engine.TextNormalizer
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.storage.EarningsStore
import com.ruan.uberareafilter.storage.LedgerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Situacoes reais do log de 17/11 (99 com corrida encadeada, atalho de voz, religar pelo OFF). */
class LedgerQueueTest {

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
        fun status(id: Long) = rows.getValue(id).status
    }

    private fun screen(
        offer: Boolean = false, rating: Boolean = false, active: Boolean = false,
        cancelled: Boolean = false, idle: Boolean = false,
        price: Double? = 6.0, hourly: Double? = 40.0, fp: String = "", estimated: Double? = null
    ) = ParsedRideOffer(
        isRideOffer = offer, isContinuePrompt = false, isRatingScreen = rating, ratingPassengerName = null,
        isInActiveRide = active, hasRiskAreaBadge = false, allTexts = emptyList(),
        pickupAddress = "Rua A", dropoffAddress = "Rua B $fp", ridePrice = price,
        pickupDistanceKm = 1.0, dropoffDistanceKm = 2.0, totalDistanceKm = 3.0, pricePerKm = null,
        hourlyRate = hourly, passengerRating = 4.9, isNewPassenger = false, addressCandidates = emptyList(),
        rejectNode = null, rejectTapPoint = null, secondaryRejectTapPoint = null, rejectTapTargets = emptyList(),
        hasNaoAfetaTaPill = false, acceptNode = null, acceptTapPoint = null, continueNode = null,
        continueTapPoint = null, fingerprint = fp, appSource = "99",
        isCancelledScreen = cancelled, isIdleHomeScreen = idle, estimatedPrice = estimated
    )

    private val log = mutableListOf<String>()
    private fun tracker(store: LedgerStore) = RideLedgerTracker(store) { log += it }

    private fun accept(t: RideLedgerTracker, fp: String, now: Long, price: Double = 6.0) {
        val o = screen(offer = true, fp = fp, price = price)
        t.onScreen(o, now)
        t.onOfferEvaluated(o, "INSIDE_AREA", 0.3, now)
        t.onAccepted(o, now)
    }

    // 20:58:59 corrida #8 aceita durante a #7: a avaliacao das 21:02 era da #7, nao da #8
    @Test
    fun rideAcceptedDuringTripWaitsInQueue() {
        val store = FakeStore()
        val t = tracker(store)
        accept(t, "A", 0L)
        t.onScreen(screen(active = true), 10_000L)
        accept(t, "B", 240_000L)
        assertEquals(2, t.openCount("99"))
        t.onScreen(screen(active = true), 241_000L)
        t.onScreen(screen(rating = true), 430_000L)
        assertEquals(EarningsStore.STATUS_DONE, store.status(1))
        assertEquals(EarningsStore.STATUS_OPEN, store.status(2))
        t.onScreen(screen(active = true), 440_000L)
        t.onScreen(screen(rating = true), 900_000L)
        assertEquals(EarningsStore.STATUS_DONE, store.status(2))
        assertFalse(t.hasOpenRide("99"))
    }

    @Test
    fun cancelWithQueuedRideCancelsTheQueuedOne() {
        val store = FakeStore()
        val t = tracker(store)
        accept(t, "A", 0L)
        t.onScreen(screen(active = true), 10_000L)
        accept(t, "B", 200_000L)
        t.onScreen(screen(cancelled = true), 260_000L)
        assertEquals(EarningsStore.STATUS_OPEN, store.status(1))
        assertEquals(EarningsStore.STATUS_CANCELLED, store.status(2))
    }

    // 22:26 o motorista passou por OFF e religou: a corrida #20 ficou esquecida na memoria
    @Test
    fun openRidesComeBackAfterServiceRestart() {
        val store = FakeStore()
        val first = tracker(store)
        accept(first, "A", 0L)
        first.onScreen(screen(active = true), 5_000L)
        val second = tracker(store)
        second.restoreOpenRides(200_000L)
        assertTrue(second.hasOpenRide("99"))
        second.onScreen(screen(rating = true), 400_000L)
        assertEquals(EarningsStore.STATUS_DONE, store.status(1))
    }

    @Test
    fun acceptThatNeverBecameATripIsCancelledOnIdleScreen() {
        val store = FakeStore()
        val t = tracker(store)
        accept(t, "A", 0L)
        t.onScreen(screen(idle = true), 30_000L)
        assertEquals(EarningsStore.STATUS_OPEN, store.status(1))
        t.onScreen(screen(idle = true), 95_000L)
        assertEquals(EarningsStore.STATUS_CANCELLED, store.status(1))
    }

    // 20:35:33 corrida #6 gravada como "R$ ?" (preco ilegivel, R$47,51/hora legivel)
    @Test
    fun unreadablePriceUsesHourlyEstimate() {
        val store = FakeStore()
        val t = tracker(store)
        val o = screen(offer = true, fp = "X", price = null, hourly = 47.5, estimated = 19.2)
        t.onOfferEvaluated(o, "INSIDE_AREA", 2.0, 0L)
        t.onAccepted(o, 0L)
        val row = store.rows.getValue(1)
        assertEquals(19.2, row.amount, 0.001)
        assertTrue(row.note.contains("estimado"))
    }

    @Test
    fun voiceShortcutMergedWithStreetIsNotACancellation() {
        listOf("r ok, corrida cancelada", "glorin ok, corrida cancelada", "ok, corrida cancelada onsuc").forEach {
            assertFalse(it, TextNormalizer.withoutVoiceCancelShortcut(it).contains("corrida cancelada"))
        }
        assertTrue(TextNormalizer.withoutVoiceCancelShortcut("passageiro cancelou a corrida.").contains("passageiro cancelou"))
        assertTrue(TextNormalizer.withoutVoiceCancelShortcut("corrida cancelada").contains("corrida cancelada"))
    }

    @Test
    fun ninetyNineMapWithMergedShortcutStaysInRide() {
        val lines = listOf(
            "20:03", "Rua Albino Imparato, 658", "R OK, corrida cancelada", "1 min 48 m",
            "Chegada prevista: 20:05", "Finalizar corrida"
        ).map { it to null }
        val parsed = NineNineCaptureModule().parseOrderedLines(lines, null, 1220, 2712, FilterConfig())
        if (parsed != null) assertFalse(parsed.isCancelledScreen)
    }
}
