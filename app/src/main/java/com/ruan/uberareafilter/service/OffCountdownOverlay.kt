package com.ruan.uberareafilter.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.ruan.uberareafilter.model.RunState
import com.ruan.uberareafilter.setup.SetupChecks
import com.ruan.uberareafilter.storage.ConfigRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Botao "OFF" que continua na tela depois que a leitura de tela (Acessibilidade) foi desligada.
 *
 * Como a Acessibilidade ja esta desligada (apps de banco param de reclamar), ele usa a permissao
 * "Sobrepor a outros apps" (TYPE_APPLICATION_OVERLAY). Mostra uma contagem regressiva configuravel
 * (padrao 10 min). Toque = religa (ON). Segurar = esconde agora. Quando o tempo acaba ele some e o
 * app e fechado para economizar bateria.
 */
object OffCountdownOverlay {

    private const val TAP_GUARD_MS = 1200L

    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var endsAt = 0L
    private var shownAt = 0L

    val isShowing: Boolean get() = view != null

    private val tick = object : Runnable {
        override fun run() {
            val v = view ?: return
            val left = endsAt - System.currentTimeMillis()
            if (left <= 0L) {
                val ctx = v.context.applicationContext
                log(ctx, "[OFF] Contagem do botão OFF terminou (${(System.currentTimeMillis() - shownAt) / 60000} min sem religar): botão removido e app encerrado para economizar bateria")
                hide(ctx, "tempo esgotado")
                closeAppIfInBackground(ctx)
                return
            }
            val totalSec = (left / 1000L).toInt()
            v.text = String.format(Locale.US, "OFF  %d:%02d", totalSec / 60, totalSec % 60)
            handler.postDelayed(this, 1000L)
        }
    }

    fun show(context: Context, minutes: Int, startX: Int?, startY: Int?) {
        val ctx = context.applicationContext
        handler.post {
            if (minutes <= 0) {
                log(ctx, "[OFF] Botão OFF configurado para sumir na hora (0 min)")
                return@post
            }
            if (!Settings.canDrawOverlays(ctx)) {
                log(ctx, "[OFF] Sem permissão 'Sobrepor a outros apps': botão OFF não pode ficar na tela")
                return@post
            }
            removeView(ctx)
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val tv = TextView(ctx).apply {
                gravity = Gravity.CENTER
                minWidth = dp(64)
                minHeight = dp(44)
                setTextColor(Color.parseColor("#FFFFFF"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.04f
                background = GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat()
                    setColor(Color.parseColor("#F2D0101F"))
                    setStroke(dp(2), Color.parseColor("#B8FFFFFF"))
                }
                setPadding(dp(18), dp(10), dp(18), dp(10))
                elevation = dp(8).toFloat()
                contentDescription = "Filtro desligado. Toque para religar. Segure para esconder."
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = startX ?: dp(10)
                y = startY ?: dp(240)
            }
            attachTouch(tv, lp, wm, dp(8))
            try {
                wm.addView(tv, lp)
            } catch (e: Exception) {
                log(ctx, "[OFF] Falha ao mostrar botão OFF: ${e.javaClass.simpleName}: ${e.message}")
                return@post
            }
            view = tv
            params = lp
            shownAt = System.currentTimeMillis()
            endsAt = shownAt + minutes * 60_000L
            handler.removeCallbacks(tick)
            tick.run()
            log(ctx, "[OFF] Botão OFF na tela por $minutes min (toque religa, segurar esconde)")
        }
    }

    fun hide(context: Context, reason: String) {
        val ctx = context.applicationContext
        handler.post {
            if (view != null) log(ctx, "[OFF] Botão OFF removido ($reason)")
            removeView(ctx)
        }
    }

    private fun removeView(ctx: Context) {
        handler.removeCallbacks(tick)
        view?.let { v ->
            runCatching { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
        }
        view = null
        params = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(tv: TextView, lp: WindowManager.LayoutParams, wm: WindowManager, slop: Int) {
        var ix = 0
        var iy = 0
        var tx = 0f
        var ty = 0f
        var moved = false
        var longPressed = false
        val longPress = Runnable {
            if (!moved) {
                longPressed = true
                val ctx = tv.context.applicationContext
                runCatching { Toast.makeText(ctx, "Botão OFF escondido. Religue pelo bloco \"Filtro\" ou pelo app.", Toast.LENGTH_SHORT).show() }
                hide(ctx, "segurado pelo usuário")
            }
        }
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = lp.x; iy = lp.y; tx = e.rawX; ty = e.rawY
                    moved = false; longPressed = false
                    handler.postDelayed(longPress, 700L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        handler.removeCallbacks(longPress)
                        lp.x = ix + dx
                        lp.y = iy + dy
                        runCatching { wm.updateViewLayout(tv, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (!moved && !longPressed && e.action == MotionEvent.ACTION_UP) {
                        val ctx = tv.context.applicationContext
                        val sinceShown = System.currentTimeMillis() - shownAt
                        if (sinceShown < TAP_GUARD_MS) {
                            // O botao OFF nasce no mesmo lugar do botao flutuante: um 2o toque rapido
                            // (de quem tocou ON -> PAUSA -> OFF depressa) religava sem querer.
                            log(ctx, "[OFF] Toque ignorado: botão OFF apareceu há ${sinceShown} ms (proteção contra toque duplo)")
                        } else {
                            turnBackOn(ctx)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun turnBackOn(ctx: Context) {
        val repo = ConfigRepository(ctx)
        repo.setRunStateQuick(RunState.ON)
        val ok = runCatching { SetupChecks.enableOwnAccessibilityService(ctx) }.getOrDefault(false)
        log(ctx, "[OFF] Religado pelo botão OFF (acessibilidade ligada sozinha=$ok)")
        if (ok) {
            runCatching { Toast.makeText(ctx, "Filtro religado", Toast.LENGTH_SHORT).show() }
        } else {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Toast.makeText(ctx, "Ative \"Filtro de Área\" na Acessibilidade", Toast.LENGTH_LONG).show()
            }
        }
        FilterTileService.requestRefresh(ctx)
        removeView(ctx)
    }

    /** "Fecha o app": so encerra o processo se nenhuma tela do app estiver aberta. */
    private fun closeAppIfInBackground(ctx: Context) {
        handler.postDelayed({
            if (UberAccessibilityService.running) return@postDelayed
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            if (info.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }, 800L)
    }

    private fun log(ctx: Context, msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        ConfigRepository.appendLog("[$time] $msg", ctx)
    }
}
