package com.ruan.uberareafilter.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Contraproposta automatica da Negocia (99), um passo por leitura de tela e sempre conferindo:
 *  1) o servico toca no lapis do card (comeca o Job);
 *  2) na tela "Sua oferta" escreve o valor direto no campo (acessibilidade, sem usar o teclado)
 *     e confere na leitura seguinte o que apareceu; se o campo nao aceitar, usa o botao "+0,5";
 *  3) so toca em "Confirmar oferta" quando o campo mostra um valor que passa nas regras
 *     (>= minimo e no maximo R$ 0,50 acima do valor pedido);
 *  4) qualquer coisa estranha = desiste e deixa a tela com voce. Nunca confirma valor errado.
 */
object CounterOfferFlow {

    enum class Stage { OPENING_EDITOR, FILLING, CONFIRMING, SENT, ABORTED }

    data class Job(
        val app: String,
        val fingerprint: String,
        /** Menor valor que passa nas regras (o que importa). */
        val minPrice: Double,
        /** Valor que o app escreve (minimo arredondado para cima em R$ 0,10). */
        val target: Double,
        val startedAt: Long,
        val stage: Stage = Stage.OPENING_EDITOR,
        val setTextTries: Int = 0,
        val plusTaps: Int = 0,
        val confirmTries: Int = 0,
        val lastStepAt: Long = startedAt,
        /** Primeiro valor lido no campo (a dica cinza com o valor do passageiro). */
        val initialValue: Double? = null,
        val confirmedValue: Double? = null
    ) {
        val active: Boolean get() = stage == Stage.OPENING_EDITOR || stage == Stage.FILLING || stage == Stage.CONFIRMING
    }

    sealed class Action {
        data class SetText(val text: String) : Action()
        data class TapPlus(val times: Int, val at: Pair<Float, Float>) : Action()
        data class TapConfirm(val value: Double, val at: Pair<Float, Float>) : Action()
        object Wait : Action()
        data class Sent(val value: Double) : Action()
        data class Abort(val reason: String) : Action()
    }

    /** A Negocia tem poucos segundos: cada passo tem 4 s e o total 15 s. */
    const val STEP_TIMEOUT_MS = 4_000L
    const val TOTAL_TIMEOUT_MS = 15_000L
    /** Com o botao "+0,5" o valor pode passar do pedido em ate R$ 0,50. */
    const val MAX_OVERSHOOT = 0.50
    /** Espera a tela mostrar o resultado do passo anterior antes de conferir. */
    const val SETTLE_MS = 700L
    private const val MAX_PLUS_TAPS = 40

    private val br = Locale("pt", "BR")

    /** 1a tentativa "23,60"; 2a "23.60" (se o campo nao aceitar virgula). */
    fun textFor(value: Double, attempt: Int): String =
        if (attempt == 0) String.format(br, "%.2f", value) else String.format(Locale.US, "%.2f", value)

    /**
     * Pode confirmar? So depois que o APP mexeu no campo (a dica cinza nunca e confirmada):
     *  - escreveu o valor: o campo tem que mostrar exatamente o valor escrito;
     *  - usou o "+0,5": o valor mudou, passa no minimo e nao passou demais.
     */
    fun readyToConfirm(job: Job, value: Double?): Boolean {
        if (value == null) return false
        if (value + 0.001 < job.minPrice) return false
        if (value > job.target + MAX_OVERSHOOT + 0.001) return false
        return when {
            job.plusTaps > 0 -> job.initialValue == null || abs(value - job.initialValue) > 0.001
            job.setTextTries > 0 -> abs(value - job.target) < 0.011
            else -> false
        }
    }

    /** A tela do lapis ("Sua oferta") esta aberta e foi o app que abriu. */
    fun onEditor(
        job: Job,
        value: Double?,
        plusTap: Pair<Float, Float>?,
        confirmTap: Pair<Float, Float>?,
        now: Long
    ): Pair<Job, Action> {
        if (!job.active) return job to Action.Wait
        if (now - job.startedAt > TOTAL_TIMEOUT_MS) {
            return job.copy(stage = Stage.ABORTED) to Action.Abort("passou de ${TOTAL_TIMEOUT_MS / 1000} s")
        }
        var j = if (job.initialValue == null && value != null && job.setTextTries == 0 && job.plusTaps == 0) {
            job.copy(initialValue = value)
        } else {
            job
        }
        if (j.stage == Stage.OPENING_EDITOR) j = j.copy(stage = Stage.FILLING, lastStepAt = now - SETTLE_MS)

        // Ja tocou em Confirmar e a tela continua aberta: tenta 1 vez mais e depois desiste
        if (j.stage == Stage.CONFIRMING) {
            if (now - j.lastStepAt < 1_500L) return j to Action.Wait
            if (j.confirmTries >= 2 || confirmTap == null) {
                return j.copy(stage = Stage.ABORTED) to Action.Abort("a 99 não fechou a tela depois de confirmar")
            }
            return j.copy(confirmTries = j.confirmTries + 1, lastStepAt = now) to
                Action.TapConfirm(j.confirmedValue ?: j.target, confirmTap)
        }

        // Da tempo da tela mostrar o passo anterior
        if (now - j.lastStepAt < SETTLE_MS) return j to Action.Wait

        if (readyToConfirm(j, value)) {
            if (confirmTap == null) {
                return j.copy(stage = Stage.ABORTED) to Action.Abort("não achei o botão Confirmar oferta")
            }
            return j.copy(stage = Stage.CONFIRMING, confirmTries = 1, lastStepAt = now, confirmedValue = value) to
                Action.TapConfirm(value!!, confirmTap)
        }
        // Escreve o valor no campo
        if (j.setTextTries < 2) {
            return j.copy(setTextTries = j.setTextTries + 1, lastStepAt = now) to Action.SetText(textFor(j.target, j.setTextTries))
        }
        // O campo nao aceitou: "+0,5" a partir do valor que esta la
        if (value != null && plusTap != null && value + 0.001 < j.minPrice && j.plusTaps < MAX_PLUS_TAPS) {
            val times = ceil((j.minPrice - value) / 0.5 - 1e-9).toInt().coerceIn(1, MAX_PLUS_TAPS - j.plusTaps)
            return j.copy(plusTaps = j.plusTaps + times, lastStepAt = now) to Action.TapPlus(times, plusTap)
        }
        val why = when {
            value == null -> "não consegui ler o valor do campo"
            value > j.target + MAX_OVERSHOOT -> "o campo ficou com um valor acima do pedido"
            plusTap == null -> "o campo não aceitou o valor e não achei o botão +0,5"
            else -> "o campo não aceitou o valor"
        }
        return j.copy(stage = Stage.ABORTED) to Action.Abort(why)
    }

    /** Qualquer outra tela (card da Negocia, espera do passageiro, outra oferta...). */
    fun onOtherScreen(job: Job, now: Long): Pair<Job, Action?> = when (job.stage) {
        // Confirmou e a tela do lapis fechou: enviada
        Stage.CONFIRMING -> job.copy(stage = Stage.SENT) to Action.Sent(job.confirmedValue ?: job.target)
        Stage.OPENING_EDITOR ->
            if (now - job.lastStepAt > STEP_TIMEOUT_MS) {
                job.copy(stage = Stage.ABORTED) to Action.Abort("a tela do lápis não abriu")
            } else {
                job to null
            }
        Stage.FILLING ->
            if (now - job.lastStepAt > STEP_TIMEOUT_MS) {
                job.copy(stage = Stage.ABORTED) to Action.Abort("a tela do lápis fechou antes de confirmar")
            } else {
                job to null
            }
        else -> job to null
    }
}
