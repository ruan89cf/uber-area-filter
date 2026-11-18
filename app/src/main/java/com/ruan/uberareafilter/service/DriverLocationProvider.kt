package com.ruan.uberareafilter.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import com.ruan.uberareafilter.engine.DriverFix

/**
 * Le a posicao atual do motorista SEM ligar o GPS por conta propria:
 * aproveita a ultima posicao que o Android ja calculou (Uber/99/Waze mantem o GPS ativo o tempo todo).
 *
 * Precisa da permissao de Localizacao "Permitir o tempo todo" (o servico roda em segundo plano).
 * Sem permissao, ou com posicao velha/imprecisa, devolve null e o filtro segue sem essa evidencia.
 */
object DriverLocationProvider {

    fun hasPermission(context: Context): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine && background
    }

    fun current(context: Context): DriverFix? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        var best: Location? = null
        for (p in providers) {
            val loc = try {
                @Suppress("MissingPermission")
                lm.getLastKnownLocation(p)
            } catch (_: Exception) {
                null
            } ?: continue
            if (best == null || loc.elapsedRealtimeNanos > best.elapsedRealtimeNanos) best = loc
        }
        val loc = best ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
        val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 999.0
        return DriverFix(loc.latitude, loc.longitude, accuracy, ageMs.coerceAtLeast(0L))
    }
}
