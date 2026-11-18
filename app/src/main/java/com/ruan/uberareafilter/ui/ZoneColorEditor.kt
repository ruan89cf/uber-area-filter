package com.ruan.uberareafilter.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.appcompat.app.AlertDialog
import com.ruan.uberareafilter.model.MapZone
import com.ruan.uberareafilter.overlay.CardPalette

/**
 * Editor da cor do card por zona: o motorista escolhe uma cor para cada zona permitida e,
 * quando o destino da corrida cai nela, o card aparece com essa cor (identifica sem ler).
 * Pergunta uma zona por vez e devolve as zonas atualizadas em [onDone].
 */
object ZoneColorEditor {

    fun edit(context: Context, zones: List<MapZone>, targetIds: List<Long>, onDone: (List<MapZone>) -> Unit) {
        val queue = targetIds.filter { id -> zones.any { it.id == id } }.toMutableList()
        val result = zones.toMutableList()

        fun next() {
            val id = queue.removeFirstOrNull()
            if (id == null) {
                onDone(result)
                return
            }
            val idx = result.indexOfFirst { it.id == id }
            val zone = result[idx]
            val palette = CardPalette.ZONE_COLORS
            val labels = palette.map { (name, hex) -> swatch(name, hex) } + "Não pintar (cor normal)"
            val current = zone.cardColorHex.ifBlank { CardPalette.DEFAULT_ZONE_COLOR }
            var chosen = palette.indexOfFirst { it.second.equals(current, ignoreCase = true) }
                .let { if (it < 0) 0 else it }

            AlertDialog.Builder(context)
                .setTitle("Cor do card: ${zone.name}")
                .setSingleChoiceItems(labels.toTypedArray(), chosen) { _, which -> chosen = which }
                .setPositiveButton("Salvar") { _, _ ->
                    val hex = palette.getOrNull(chosen)?.second.orEmpty()
                    result[idx] = zone.copy(cardColorHex = hex)
                    next()
                }
                .setNegativeButton("Pular") { _, _ -> next() }
                .setOnCancelListener { next() }
                .show()
        }
        next()
    }

    private fun swatch(name: String, hex: String): CharSequence {
        val color = CardPalette.parseHex(hex) ?: return name
        return SpannableStringBuilder("●  $name").apply {
            setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(1.4f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
