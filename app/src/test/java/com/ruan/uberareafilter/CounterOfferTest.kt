package com.ruan.uberareafilter

import android.graphics.Rect
import com.ruan.uberareafilter.driver.AppCaptureRouter
import com.ruan.uberareafilter.engine.CounterOfferFlow
import com.ruan.uberareafilter.engine.CounterOfferFlow.Action
import com.ruan.uberareafilter.engine.CounterOfferFlow.Stage
import com.ruan.uberareafilter.engine.ParsedRideOffer
import com.ruan.uberareafilter.engine.RideLedgerTracker
import com.ruan.uberareafilter.engine.RideOfferParser
import com.ruan.uberareafilter.model.FilterConfig
import com.ruan.uberareafilter.storage.EarningsStore
import com.ruan.uberareafilter.storage.LedgerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Negocia (99) com contraproposta automatica: o app toca no lapis, escreve o valor minimo das regras,
 * confere o que apareceu no campo e so entao confirma. Posicoes reais dos prints 59192 e 59332 (1220x2712).
 */
class CounterOfferTest {

    private val only99 = FilterConfig(nineNineModuleEnabled = true, uberModuleEnabled = false)

    private fun box(top: Int, bottom: Int, left: Int = 60, right: Int = 1160) = Rect().apply {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom
    }

    private fun parse(lines: List<Pair<String, Rect?>>): ParsedRideOffer? =
        AppCaptureRouter.parseOrderedLines(lines, null, 1220, 2712, only99, preferredAppSource = "99")

    // 07:59:01 (print 59192), posicoes do RAIO-X do celular
    private val negocia0759 = listOf(
        "Moto Negocia" to box(1014, 1071),
        "R\$21,13 1,3x" to box(1141, 1272),
        "R\$1,07/km" to box(1310, 1355),
        "0% de Taxa99 semanal" to box(1389, 1432),
        "t 4,87 584 corridas" to box(1519, 1562),
        "V CPF e Cartão verif." to box(1599, 1643),
        "O5 min (2,6 km)" to box(1732, 1782),
        "Rua Leopoldo Bulhões, 800 - Rua Leopoldo" to box(1797, 1849),
        "de Bulhões" to box(1861, 1900),
        "28 min (17 km)" to box(1952, 2001),
        "Avenida Nossa Sra. de Copacabana, 386," to box(2016, 2062),
        "Copacabana" to box(2076, 2124),
        "Aceitar por R\$21,13" to box(2221, 2266),
        "R\$22,19 R\$23,24" to box(2380, 2443, 110, 628),
        "R\$24,30" to box(2388, 2438, 720, 940)
    )

    @Test
    fun pencilIsFoundOnTheNegociaCard() {
        val p = parse(negocia0759)!!
        assertTrue(p.isRideOffer)
        assertTrue(p.isNegotiable)
        val pencil = p.counterOfferPencilTap!!
        // no print 59192 o lapis esta em (789, 1779) de 900x2000 = (1069, 2411) em 1220x2712
        assertEquals(1220 * RideOfferParser.PENCIL_X_FRACTION, pencil.first, 1f)
        assertEquals(2411.5f, pencil.second, 1f)

        // fileira de valores nao lida: o lapis fica 6,3% da altura abaixo do "Aceitar por"
        val noRow = parse(negocia0759.dropLast(2))!!
        assertEquals((2221 + 2266) / 2f + 2712 * 0.063f, noRow.counterOfferPencilTap!!.second, 1f)
    }

    // 11:26 (print 59332): lapis aberto, teclado na tela; o fundo escurecido (card) fica no topo
    private fun editor(field: String) = listOf(
        "Moto Negocia" to box(320, 375),
        "R\$23,88" to box(450, 560),
        "321 corridas" to box(824, 874),
        "PAUSA" to box(830, 880, 40, 300),
        "Ofertas menores têm mais chances!" to box(920, 970),
        "Sua oferta" to box(1066, 1122),
        "A oferta feita pelo passageiro é de R\$23,88" to box(1150, 1192),
        field to box(1330, 1390, 520, 700),
        "-0,5" to box(1335, 1385, 60, 240),
        "+0,5" to box(1335, 1385, 990, 1150),
        "Confirmar oferta" to box(1578, 1630, 390, 830),
        "Senhas" to box(1805, 1853, 470, 750),
        "1" to box(1960, 2015, 140, 180), "2" to box(1960, 2015, 440, 480), "3" to box(1960, 2015, 740, 780)
    )

    @Test
    fun counterOfferEditorScreenIsReadWithItsButtons() {
        val p = parse(editor("R\$23,9"))!!
        assertFalse(p.isRideOffer)
        assertTrue(p.isCounterOfferEditor)
        assertEquals("99", p.appSource)
        assertEquals(23.9, p.editorValue!!, 0.001)
        assertEquals(1070f, p.editorPlusTap!!.first, 0.5f)
        assertEquals(1360f, p.editorPlusTap!!.second, 0.5f)
        assertEquals(610f, p.editorConfirmTap!!.first, 0.5f)
        assertEquals(1604f, p.editorConfirmTap!!.second, 0.5f)
        // depois de escrever: cursor no fim, "O" no lugar do zero
        assertEquals(23.6, parse(editor("R\$23,6O|"))!!.editorValue!!, 0.001)
        assertEquals(26.8, parse(editor("26,80"))!!.editorValue!!, 0.001)
    }

    private fun job(min: Double = 23.52, target: Double = 23.60) =
        CounterOfferFlow.Job(app = "99", fingerprint = "fp", minPrice = min, target = target, startedAt = 0L)

    private val plus = 1070f to 1360f
    private val confirm = 610f to 1604f

    @Test
    fun happyPathWritesChecksAndConfirms() {
        // 1a leitura: a dica cinza (23,9) ja passa do minimo, mas NUNCA e confirmada: o app escreve o valor
        val (j1, a1) = CounterOfferFlow.onEditor(job(), 23.9, plus, confirm, 900L)
        assertEquals(Action.SetText("23,60"), a1)
        // logo em seguida a tela ainda nao mudou: espera
        val (j2, a2) = CounterOfferFlow.onEditor(j1, 23.9, plus, confirm, 1200L)
        assertEquals(Action.Wait, a2)
        // campo mostra o valor escrito: confirma
        val (j3, a3) = CounterOfferFlow.onEditor(j2, 23.60, plus, confirm, 1900L)
        assertEquals(Action.TapConfirm(23.60, confirm), a3)
        assertEquals(Stage.CONFIRMING, j3.stage)
        // a tela do lapis fechou: enviada
        val (j4, a4) = CounterOfferFlow.onOtherScreen(j3, 2900L)
        assertEquals(Action.Sent(23.60), a4)
        assertEquals(Stage.SENT, j4.stage)
        assertFalse(j4.active)
    }

    @Test
    fun fieldThatRejectsTextNeverConfirmsTheGreyHint() {
        val (j1, a1) = CounterOfferFlow.onEditor(job(), 23.9, plus, confirm, 900L)
        assertEquals(Action.SetText("23,60"), a1)
        val (j2, a2) = CounterOfferFlow.onEditor(j1, 23.9, plus, confirm, 1700L)
        assertEquals(Action.SetText("23.60"), a2)            // 2o formato
        val (j3, a3) = CounterOfferFlow.onEditor(j2, 23.9, plus, confirm, 2500L)
        assertTrue(a3 is Action.Abort)                        // dica cinza ja passa do minimo: nao arrisca
        assertEquals(Stage.ABORTED, j3.stage)
    }

    @Test
    fun plusButtonFallbackReachesTheMinimum() {
        // precisa de R$ 26,75 (pede 26,80); o campo nao aceita texto e mostra 23,9
        var j = job(min = 26.75, target = 26.80)
        var a: Action
        CounterOfferFlow.onEditor(j, 23.9, plus, confirm, 900L).also { j = it.first; a = it.second }
        assertTrue(a is Action.SetText)
        CounterOfferFlow.onEditor(j, 23.9, plus, confirm, 1700L).also { j = it.first; a = it.second }
        assertTrue(a is Action.SetText)
        CounterOfferFlow.onEditor(j, 23.9, plus, confirm, 2500L).also { j = it.first; a = it.second }
        assertEquals(Action.TapPlus(6, plus), a)              // 23,9 + 6 x 0,5 = 26,9
        CounterOfferFlow.onEditor(j, 26.9, plus, confirm, 3500L).also { j = it.first; a = it.second }
        assertEquals(Action.TapConfirm(26.9, confirm), a)
        assertEquals(Action.Sent(26.9), CounterOfferFlow.onOtherScreen(j, 4500L).second)
    }

    @Test
    fun valueBelowTheMinimumOrTooHighIsNeverConfirmed() {
        val j = job().copy(setTextTries = 1, lastStepAt = 0L, stage = Stage.FILLING)
        assertFalse(CounterOfferFlow.readyToConfirm(j, 23.40))   // abaixo do minimo
        assertFalse(CounterOfferFlow.readyToConfirm(j, 236.0))   // virgula perdida
        assertFalse(CounterOfferFlow.readyToConfirm(j, 23.68))   // nao e o valor escrito
        assertTrue(CounterOfferFlow.readyToConfirm(j, 23.60))
        assertFalse(CounterOfferFlow.readyToConfirm(j, null))
    }

    @Test
    fun confirmIsRetriedOnceThenGivesUp() {
        val (j1, _) = CounterOfferFlow.onEditor(job(), 23.9, plus, confirm, 900L)
        val (j2, a2) = CounterOfferFlow.onEditor(j1, 23.60, plus, confirm, 1700L)
        assertTrue(a2 is Action.TapConfirm)
        val (j3, a3) = CounterOfferFlow.onEditor(j2, 23.60, plus, confirm, 2000L)
        assertEquals(Action.Wait, a3)                         // da tempo da 99 fechar a tela
        val (j4, a4) = CounterOfferFlow.onEditor(j3, 23.60, plus, confirm, 3300L)
        assertTrue(a4 is Action.TapConfirm)                   // 2a tentativa
        val (_, a5) = CounterOfferFlow.onEditor(j4, 23.60, plus, confirm, 4900L)
        assertTrue(a5 is Action.Abort)
    }

    @Test
    fun timeoutsGiveUpAndLeaveItToTheDriver() {
        // o lapis nao abriu em 4 s
        val (stuck, a) = CounterOfferFlow.onOtherScreen(job(), 4_100L)
        assertTrue(a is Action.Abort)
        assertFalse(stuck.active)
        // nao passa de 15 s no total
        val late = job().copy(stage = Stage.FILLING, setTextTries = 1, lastStepAt = 14_000L)
        assertTrue(CounterOfferFlow.onEditor(late, 23.9, plus, confirm, 15_100L).second is Action.Abort)
        // ainda dentro do prazo: so espera
        assertEquals(null, CounterOfferFlow.onOtherScreen(job(), 2_000L).second)
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

    @Test
    fun passengerAcceptingTheCounterOfferRecordsTheAskedValue() {
        val store = FakeStore()
        val t = RideLedgerTracker(store) { }
        val base = 1_763_470_000_000L
        val offer = parse(negocia0759)!!
        t.onScreen(offer, base)
        t.onOfferEvaluated(offer, "OUTSIDE_AREA", 2.5, base)
        t.onCounterOffer("99", offer.fingerprint, 23.60, base + 30_000L)
        // aceite 140 s depois da oferta (110 s depois da contraproposta): conta a partir da contraproposta
        val accepted = AppCaptureRouter.parseOrderedLines(
            listOf("Moto Negocia", "Corrida aceita", "R\$23,60", "Carregando...").map { it to null },
            null, 1220, 2712, only99, preferredAppSource = "99"
        )!!
        assertTrue(accepted.isInActiveRide)
        t.onScreen(accepted, base + 140_000L)
        assertEquals(1, store.rows.size)
        val ride = store.rows.values.single()
        assertNotNull(ride)
        assertEquals(23.60, ride.amount, 0.001)
        assertEquals(EarningsStore.SOURCE_MANUAL, ride.source)
        assertTrue(ride.note.contains("Negocia"))
    }
}
