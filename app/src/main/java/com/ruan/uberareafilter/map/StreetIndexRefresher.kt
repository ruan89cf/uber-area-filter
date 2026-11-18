package com.ruan.uberareafilter.map

import com.ruan.uberareafilter.engine.GeoMath
import com.ruan.uberareafilter.model.ZoneMode
import com.ruan.uberareafilter.storage.ConfigRepository
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mantem o indice de ruas internas/divisa em dia:
 * - logo apos salvar as zonas no mapa;
 * - ao abrir o app, se alguma zona ainda nao tem indice (ex.: zonas desenhadas na versao anterior)
 *   ou se a zona foi redesenhada.
 * Sem indice valido, o motor simplesmente nao aprova "so pelo nome" (fica mais conservador, nunca mais permissivo).
 */
object StreetIndexRefresher {

    @Volatile
    private var running = false

    @Volatile
    private var lastFailedAttemptAt = 0L

    data class Outcome(val zones: Int, val internalStreets: Int, val touchingStreets: Int, val complete: Boolean)

    fun isStale(repository: ConfigRepository): Boolean {
        val cfg = repository.load()
        val allowed = cfg.mapZones.filter { it.mode == ZoneMode.ALLOWED }
        if (allowed.isEmpty()) return false
        return allowed.any { z ->
            val idx = cfg.streetIndex[z.id]
            idx == null || !idx.complete || idx.geometryHash != GeoMath.indexKeyFor(z, cfg.mapZones)
        }
    }

    suspend fun refresh(repository: ConfigRepository, force: Boolean = false): Outcome? {
        if (running) return null
        if (!force) {
            if (!isStale(repository)) return null
            if (System.currentTimeMillis() - lastFailedAttemptAt < 10 * 60_000L) return null
        }
        running = true
        try {
            val cfg = repository.load()
            val allowed = cfg.mapZones.filter { it.mode == ZoneMode.ALLOWED }
            val blocked = cfg.mapZones.filter { it.mode == ZoneMode.BLOCKED }
            if (allowed.isEmpty()) return null

            val built = withTimeoutOrNull(30_000L) {
                OpenFreeMapStreetFetcher.buildStreetIndex(allowed, blocked)
            }.orEmpty()
            if (built.isEmpty()) {
                lastFailedAttemptAt = System.currentTimeMillis()
                return null
            }
            repository.saveStreetIndex(built)
            val complete = built.values.all { it.complete }
            if (!complete) lastFailedAttemptAt = System.currentTimeMillis()
            return Outcome(
                zones = built.size,
                internalStreets = built.values.sumOf { it.internalStreets.size },
                touchingStreets = built.values.sumOf { it.touchingStreets.size },
                complete = complete
            )
        } finally {
            running = false
        }
    }
}
