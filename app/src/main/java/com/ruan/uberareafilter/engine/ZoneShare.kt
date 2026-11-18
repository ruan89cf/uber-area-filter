package com.ruan.uberareafilter.engine

import com.ruan.uberareafilter.model.MapZone
import com.ruan.uberareafilter.model.ZoneMode
import com.ruan.uberareafilter.model.ZoneShape
import java.util.Locale
import kotlin.math.abs

/**
 * Trocar zonas com outro motorista (WhatsApp): o arquivo leva as zonas desenhadas no mapa
 * (liberadas e proibidas, nome, cor do card e as ruas de cada uma). Quem recebe escolhe:
 *  - SUBSTITUIR as zonas dele pelas recebidas, ou
 *  - JUNTAR: as dele continuam (a Zona 1 dele segue sendo a principal) e as recebidas entram no fim.
 */
object ZoneShare {

    /** Marca do arquivo ("tipo") para reconhecer que e um arquivo de zonas deste app. */
    const val FILE_KIND = "filtro-inteligente-zonas"
    const val FILE_VERSION = 1

    data class MergeResult(val zones: List<MapZone>, val added: Int, val skippedDuplicates: Int)

    /** Substitui tudo: as zonas recebidas, na ordem do arquivo (a primeira liberada vira a principal). */
    fun replace(incoming: List<MapZone>): MergeResult {
        val zones = incoming.filter { isValid(it) }.mapIndexed { i, z -> z.copy(id = (i + 1).toLong()) }
        return MergeResult(zones, zones.size, 0)
    }

    /** Junta: as suas zonas primeiro; as recebidas no fim, com numero novo e sem repetir a mesma zona. */
    fun merge(existing: List<MapZone>, incoming: List<MapZone>): MergeResult {
        var nextId = (existing.maxOfOrNull { it.id } ?: 0L) + 1L
        val usedNames = existing.map { it.name.trim().lowercase(Locale.ROOT) }.toMutableSet()
        val out = existing.toMutableList()
        var added = 0
        var duplicates = 0
        for (z in incoming) {
            if (!isValid(z)) continue
            if (out.any { sameGeometry(it, z) }) {
                duplicates++
                continue
            }
            var name = z.name.trim().ifBlank { "Zona $nextId" }
            if (name.lowercase(Locale.ROOT) in usedNames) name = "$name (recebida)"
            usedNames += name.lowercase(Locale.ROOT)
            out += z.copy(id = nextId++, name = name)
            added++
        }
        return MergeResult(out, added, duplicates)
    }

    /** Mesma zona (mesmo tipo, forma e pontos, com folga de ~1 m). */
    fun sameGeometry(a: MapZone, b: MapZone): Boolean {
        if (a.mode != b.mode || a.shape != b.shape) return false
        return if (a.shape == ZoneShape.CIRCLE) {
            abs(a.centerLat - b.centerLat) < 1e-5 && abs(a.centerLng - b.centerLng) < 1e-5 &&
                abs(a.radiusMeters - b.radiusMeters) < 1.0
        } else {
            a.points.size == b.points.size && a.points.zip(b.points).all { (p, q) ->
                abs(p.first - q.first) < 1e-5 && abs(p.second - q.second) < 1e-5
            }
        }
    }

    /** Zona aproveitavel: poligono com 3+ pontos validos ou circulo com centro e raio validos. */
    fun isValid(z: MapZone): Boolean = when (z.shape) {
        ZoneShape.POLYGON -> z.points.size >= 3 && z.points.all { (la, ln) -> validPoint(la, ln) }
        ZoneShape.CIRCLE -> z.radiusMeters in 50.0..100_000.0 && validPoint(z.centerLat, z.centerLng)
    }

    private fun validPoint(lat: Double, lng: Double): Boolean =
        lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0) && !lat.isNaN() && !lng.isNaN()

    /** "• Zona 1 (liberada, 12 pontos)" — uma linha por zona, para a pessoa conferir antes de importar. */
    fun describe(zones: List<MapZone>): String = zones.joinToString("\n") { z ->
        val kind = if (z.mode == ZoneMode.ALLOWED) "liberada" else "proibida"
        val shape = if (z.shape == ZoneShape.CIRCLE) {
            val r = z.radiusMeters
            if (r >= 1000.0) String.format(Locale("pt", "BR"), "círculo de %.1f km", r / 1000.0)
            else String.format(Locale("pt", "BR"), "círculo de %.0f m", r)
        } else {
            "${z.points.size} pontos"
        }
        "• ${z.name.ifBlank { "Zona" }} ($kind, $shape)"
    }
}
