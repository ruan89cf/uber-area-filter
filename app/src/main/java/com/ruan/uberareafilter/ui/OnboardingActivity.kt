package com.ruan.uberareafilter.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * Apresentacao da primeira vez: cards que deslizam para o lado explicando o app em poucas palavras.
 * No fim: "Criar conta gratis" ou "Ja tenho conta". Aparece uma vez so (depois vai direto ao login).
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "apresentacao"
        fun seen(context: Context) = context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("vista", false)
        private fun markSeen(context: Context) = context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("vista", true).apply()
    }

    private data class Slide(val icon: String, val title: String, val body: String, val accent: Int)

    private val slides by lazy {
        listOf(
            Slide("🛵", "Só as corridas que valem a pena",
                "O Filtro Inteligente lê as ofertas da 99 e da Uber e decide em menos de 1 segundo: aceita as boas e recusa as ruins, sozinho.",
                Color.parseColor("#06C167")),
            Slide("🗺️", "Você escolhe onde rodar",
                "Desenhe no mapa suas zonas e os lugares que quer evitar. Corrida que tira você da sua área é recusada antes de você perder tempo.",
                Color.parseColor("#2563EB")),
            Slide("💰", "Suas regras de ganho",
                "Mínimo de R$/km e R$/h líquido, embarque longe, nota do passageiro. O card mostra na hora o que passou e o que faltou.",
                Color.parseColor("#F59E0B")),
            Slide("🚦", "Trânsito de agora",
                "Antes de aceitar sozinho, o app confere o trânsito real, para moto ou carro. Se a avenida travou, ele não aceita.",
                Color.parseColor("#D0101F")),
            Slide("🎁", "Comece grátis",
                "Crie sua conta e teste tudo de graça. Suas zonas, ganhos e histórico ficam salvos na conta, até se trocar de celular.",
                Color.parseColor("#06C167"))
        )
    }

    private lateinit var ui: AccountUi
    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var next: com.google.android.material.button.MaterialButton
    private lateinit var secondary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = AccountUi(this)

        pager = ViewPager2(this).apply {
            adapter = SlideAdapter()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = updateControls(position)
            })
        }
        val skip = ui.link("Pular") { finishTo(register = false) }.apply {
            paintFlags = paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            setTextColor(ui.muted)
            gravity = Gravity.END
            setPadding(ui.dp(20), ui.dp(18), ui.dp(24), ui.dp(4))
        }
        dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(8), 0, ui.dp(8))
        }
        next = ui.button("Próximo") {
            if (pager.currentItem < slides.lastIndex) pager.currentItem = pager.currentItem + 1 else finishTo(register = true)
        }
        secondary = ui.link("Já tenho conta") { finishTo(register = false) }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(24))
            addView(dots)
            addView(next)
            addView(secondary)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.pageBg)
            addView(skip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(pager)
            addView(bottom)
        })
        updateControls(0)
    }

    private fun updateControls(position: Int) {
        dots.removeAllViews()
        slides.forEachIndexed { i, s ->
            dots.addView(View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = ui.dp(4).toFloat()
                    setColor(if (i == position) s.accent else ui.line)
                }
                layoutParams = LinearLayout.LayoutParams(if (i == position) ui.dp(26) else ui.dp(8), ui.dp(8)).apply {
                    marginStart = ui.dp(4); marginEnd = ui.dp(4)
                }
            })
        }
        val last = position == slides.lastIndex
        next.text = if (last) "Criar conta grátis" else "Próximo"
        secondary.visibility = if (last) View.VISIBLE else View.INVISIBLE
    }

    private fun finishTo(register: Boolean) {
        markSeen(this)
        startActivity(
            Intent(this, AccountActivity::class.java)
                .putExtra("locked", true)
                .putExtra("screen", if (register) AccountActivity.SCREEN_REGISTER else null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pager.currentItem > 0) pager.currentItem = pager.currentItem - 1 else finishAffinity()
    }

    private inner class SlideAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = slides.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val page = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(ui.dp(32), ui.dp(12), ui.dp(32), ui.dp(12))
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            return object : RecyclerView.ViewHolder(page) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val s = slides[position]
            val page = holder.itemView as LinearLayout
            page.removeAllViews()
            // "Ilustracao": circulo com a cor do card, anel claro e o simbolo grande no meio
            val art = FrameLayout(page.context).apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(190), ui.dp(190)).apply { bottomMargin = ui.dp(36) }
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(38, Color.red(s.accent), Color.green(s.accent), Color.blue(s.accent)))
                    }
                }, FrameLayout.LayoutParams(ui.dp(190), ui.dp(190)))
                addView(View(context).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(s.accent, Color.argb(255, (Color.red(s.accent) * 0.7).toInt(), (Color.green(s.accent) * 0.7).toInt(), (Color.blue(s.accent) * 0.7).toInt()))
                    ).apply { shape = GradientDrawable.OVAL }
                    elevation = ui.dp(10).toFloat()
                }, FrameLayout.LayoutParams(ui.dp(132), ui.dp(132), Gravity.CENTER))
                addView(TextView(context).apply {
                    text = s.icon
                    textSize = 58f
                    gravity = Gravity.CENTER
                    elevation = ui.dp(11).toFloat()
                }, FrameLayout.LayoutParams(ui.dp(132), ui.dp(132), Gravity.CENTER))
            }
            page.addView(art)
            page.addView(ui.title(s.title, center = true))
            page.addView(ui.text(s.body, 16f, ui.muted, center = true).apply { setPadding(0, ui.dp(10), 0, 0) })
        }
    }
}
