package com.ruan.uberareafilter.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gerencia conexao, permissao e execucao de toques via Shizuku UserService.
 * Permite disparar `input tap` em nivel de sistema na Uber sem bloqueio de Acessibilidade.
 */
object ShizukuTapBridge {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_CODE_SHIZUKU = 9901

    private val initialized = AtomicBoolean(false)
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var remoteService: IShizukuTapService? = null
    @Volatile
    private var pendingBindLatch: CountDownLatch? = null
    @Volatile
    private var statusChangedCallback: (() -> Unit)? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        appContext?.let { ensureBound(it) }
        statusChangedCallback?.invoke()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remoteService = null
        statusChangedCallback?.invoke()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { reqCode, grantResult ->
        if (reqCode == REQUEST_CODE_SHIZUKU && grantResult == PackageManager.PERMISSION_GRANTED) {
            appContext?.let { ensureBound(it) }
        }
        statusChangedCallback?.invoke()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remoteService = if (service != null && service.pingBinder()) {
                IShizukuTapService.Stub.asInterface(service)
            } else {
                null
            }
            pendingBindLatch?.countDown()
            statusChangedCallback?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remoteService = null
            statusChangedCallback?.invoke()
        }
    }

    fun init(context: Context, onStatusChanged: (() -> Unit)? = null) {
        appContext = context.applicationContext
        if (onStatusChanged != null) {
            statusChangedCallback = onStatusChanged
        }
        if (initialized.compareAndSet(false, true)) {
            runCatching {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Shizuku.addRequestPermissionResultListener(permissionResultListener)
            }
        }
        ensureBound(context.applicationContext)
    }

    fun isInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean {
        if (!isBinderAlive()) return false
        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun isServiceConnected(): Boolean = remoteService?.asBinder()?.isBinderAlive == true

    fun requestPermission() {
        if (!isBinderAlive()) return
        runCatching {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
            }
        }
    }

    fun ensureBound(context: Context) {
        if (!hasPermission()) return
        if (isServiceConnected()) return
        val latch = CountDownLatch(1)
        pendingBindLatch = latch
        runCatching {
            Shizuku.bindUserService(buildUserServiceArgs(context.applicationContext), serviceConnection)
        }
    }

    /**
     * Executa `input tap X Y` pelo Shizuku se estiver ativo e autorizado.
     * Deve ser chamado fora da thread principal (ex: Dispatchers.IO).
     */
    fun tryTap(context: Context, x: Float, y: Float): Boolean {
        if (!hasPermission()) return false
        var svc = remoteService
        if (svc == null || !svc.asBinder().isBinderAlive) {
            ensureBound(context)
            runCatching { pendingBindLatch?.await(500L, TimeUnit.MILLISECONDS) }
            svc = remoteService
        }
        if (svc == null || !svc.asBinder().isBinderAlive) return false
        return try {
            svc.tap(x.toInt(), y.toInt())
        } catch (_: Throwable) {
            remoteService = null
            false
        }
    }

    fun statusLabel(context: Context): String = when {
        !isInstalled(context) -> "Não instalado"
        !isBinderAlive() -> "Inativo (abra o app Shizuku e toque em Iniciar)"
        !hasPermission() -> "Sem permissão (toque no botão p/ autorizar)"
        isServiceConnected() -> "ATIVO ✅ (toque direto na Uber liberado)"
        else -> "Autorizado (conectando serviço...)"
    }

    fun release(context: Context) {
        runCatching {
            Shizuku.unbindUserService(buildUserServiceArgs(context.applicationContext), serviceConnection, true)
        }
        remoteService = null
    }

    private fun buildUserServiceArgs(context: Context): Shizuku.UserServiceArgs {
        return Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuTapUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("tap_service")
            .debuggable(false)
            .version(3)
    }
}
