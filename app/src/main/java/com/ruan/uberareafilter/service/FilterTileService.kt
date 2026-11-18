package com.ruan.uberareafilter.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.ruan.uberareafilter.model.RunState
import com.ruan.uberareafilter.setup.SetupChecks
import com.ruan.uberareafilter.storage.ConfigRepository

/**
 * Bloco "Filtro" nas Configuracoes rapidas (puxar a barra de notificacoes).
 * Serve para RELIGAR depois do OFF: no OFF a Acessibilidade e desligada de verdade
 * (por causa dos apps de banco) e o botao flutuante some junto.
 *  - Desligado -> toque liga (sozinho se o pareamento ja foi feito; senao abre a Acessibilidade)
 *  - Ligado/Pausado -> toque desliga a leitura de tela
 */
class FilterTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val repo = ConfigRepository(applicationContext)
        if (UberAccessibilityService.running) {
            // O servico ve isEnabled=false em menos de 1 s e se desliga sozinho
            repo.setRunStateQuick(RunState.OFF)
            Toast.makeText(this, "Desligando a leitura de tela...", Toast.LENGTH_SHORT).show()
        } else {
            repo.setRunStateQuick(RunState.ON)
            if (SetupChecks.enableOwnAccessibilityService(applicationContext)) {
                Toast.makeText(this, "Filtro ligado.", Toast.LENGTH_SHORT).show()
            } else {
                openAccessibilitySettings()
            }
        }
        refreshTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val running = UberAccessibilityService.running
        val state = if (running) ConfigRepository(applicationContext).load().runState else RunState.OFF
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Filtro"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                RunState.ON -> "Ligado"
                RunState.PAUSED -> "Pausado (só lê)"
                RunState.OFF -> "Desligado"
            }
        }
        tile.updateTile()
    }

    companion object {
        fun requestRefresh(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, FilterTileService::class.java))
            }
        }
    }
}
