package com.ruan.uberareafilter.ui

import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ruan.uberareafilter.account.AccountGate
import com.ruan.uberareafilter.account.AccountStore
import com.ruan.uberareafilter.account.SyncManager
import com.ruan.uberareafilter.account.UpdateManager
import kotlinx.coroutines.launch

/** Avisos da conta na tela principal: boas-vindas (dias gratis), cupom, menu da conta e atualizacao. */
object AccountDialogs {

    /** Menu do botao de pessoa no topo. */
    fun showAccountMenu(activity: AppCompatActivity, anchor: View) {
        val menu = PopupMenu(activity, anchor)
        menu.menu.add(0, 1, 0, "Minha conta")
        menu.menu.add(0, 2, 1, "Inserir cupom")
        menu.menu.add(0, 3, 2, "Planos e pagamento")
        menu.menu.add(0, 4, 3, "Salvar cópia na conta")
        if (AccountGate.state(activity)?.hasUpdate == true) menu.menu.add(0, 5, 4, "Atualizar o app")
        // So aparece para o dono (o servidor confere conta e celular de novo ao abrir)
        if (AccountGate.state(activity)?.isAdmin == true) menu.menu.add(0, 6, 5, "Painel do dono")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> AccountActivity.open(activity, locked = false)
                2 -> showCoupon(activity)
                3 -> AccountActivity.open(activity, locked = false, screen = AccountActivity.SCREEN_PLANS)
                4 -> activity.lifecycleScope.launch {
                    toast(activity, "Salvando…")
                    toast(activity, runCatching { SyncManager.syncNow(activity, force = true) }.getOrDefault("Sem conexão"))
                }
                5 -> AccountActivity.open(activity, locked = false, screen = AccountActivity.SCREEN_UPDATE)
                6 -> activity.startActivity(android.content.Intent(activity, AdminPanelActivity::class.java))
            }
            true
        }
        menu.show()
    }

    /** Caixa para digitar o cupom. */
    fun showCoupon(activity: AppCompatActivity, onApplied: () -> Unit = {}) {
        val ui = AccountUi(activity)
        val box = ui.column(20)
        box.addView(ui.text("Digite o cupom que você recebeu.", 14f, ui.muted))
        val input = ui.input("Cupom", AccountUi.Kind.CODE)
        box.addView(input)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Inserir cupom")
            .setView(box)
            .setPositiveButton("Aplicar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text.toString().trim()
                if (code.isEmpty()) return@setOnClickListener toast(activity, "Digite o cupom")
                activity.lifecycleScope.launch {
                    val r = AccountGate.redeemCoupon(activity, code)
                    if (r.ok) {
                        toast(activity, r.state?.message ?: "Cupom aplicado")
                        dialog.dismiss()
                        onApplied()
                    } else toast(activity, r.error ?: "Cupom inválido")
                }
            }
        }
        dialog.show()
    }

    /**
     * Logo depois de entrar: "Voce tem N dias gratis" + cupom + assinar (os dias que sobram continuam).
     * Mostra uma vez por login.
     */
    fun maybeShowWelcome(activity: AppCompatActivity): Boolean {
        val store = AccountStore(activity)
        if (!store.welcomePending) return false
        store.welcomePending = false
        val st = AccountGate.state(activity) ?: return false
        if (st.status != "trial") return false
        val days = AccountUi.daysLeft(st.until)
        val ui = AccountUi(activity)
        val box = ui.column(20)
        val name = st.firstName.ifBlank { "" }
        box.addView(ui.text(if (name.isNotBlank()) "Bem-vindo(a), $name!" else "Bem-vindo(a)!", 20f, bold = true))
        box.addView(ui.text("Você tem $days ${if (days == 1L) "dia grátis" else "dias grátis"} para usar tudo do app, até ${AccountUi.date(st.until)}.", 15f).apply {
            setPadding(0, ui.dp(6), 0, ui.dp(6))
        })
        box.addView(ui.card(ui.blue,
            ui.text("Vire assinante e ganhe os dias", 15f, ui.blue, bold = true),
            ui.text("Assinando durante o teste, os dias grátis que sobrarem são somados ao seu plano. Mensal: ${AccountUi.money(st.priceMonthly)}.", 13f)
        ))
        var dialog: AlertDialog? = null
        box.addView(ui.button("Ver planos", color = ui.blue) {
            dialog?.dismiss()
            AccountActivity.open(activity, locked = false, screen = AccountActivity.SCREEN_PLANS)
        })
        box.addView(ui.button("Tenho um cupom", outline = true) {
            dialog?.dismiss()
            showCoupon(activity)
        })
        box.addView(ui.button("Começar a usar") { dialog?.dismiss() })
        dialog = MaterialAlertDialogBuilder(activity).setView(android.widget.ScrollView(activity).apply { addView(box) }).create()
        dialog.show()
        return true
    }

    /** Aviso mandado pelo painel (para voce ou para todos): aparece uma vez. */
    fun maybeShowNotice(activity: AppCompatActivity) {
        val st = AccountGate.state(activity) ?: return
        if (st.noticeText.isBlank()) return
        val prefs = activity.getSharedPreferences("avisos", android.content.Context.MODE_PRIVATE)
        val key = "visto_" + st.noticeId.ifBlank { st.noticeText.hashCode().toString() }
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Aviso do Filtro Inteligente")
            .setMessage(st.noticeText)
            .setPositiveButton("Entendi", null)
            .show()
    }

    /** Atualizacao opcional: lembra no maximo a cada 12 h. */
    fun maybeRemindUpdate(activity: AppCompatActivity) {
        val st = AccountGate.state(activity) ?: return
        if (!UpdateManager.shouldRemind(activity, st)) return
        UpdateManager.markReminded(activity, st)
        val name = st.latestVersionName.ifBlank { st.latestVersionCode.toString() }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Nova versão $name")
            .setMessage(st.updateMessage.ifBlank { "Tem uma versão nova do Filtro Inteligente com melhorias." })
            .setPositiveButton("Atualizar") { _, _ -> AccountActivity.open(activity, locked = false, screen = AccountActivity.SCREEN_UPDATE) }
            .setNegativeButton("Depois", null)
            .show()
    }

    private fun toast(activity: AppCompatActivity, msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
