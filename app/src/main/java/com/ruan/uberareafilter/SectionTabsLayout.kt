package com.ruan.uberareafilter

import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.ruan.uberareafilter.ui.UiPolish

/**
 * Raiz do painel principal (activity_main.xml). Liga a barra inferior às abas sem precisar de
 * código na Activity.
 *
 * Funciona por tags: todo filho direto deste layout ou de rootContentLayout que tenha
 * android:tag começando com "tab_" só aparece quando a aba de mesmo nome está selecionada.
 * O nome é o id do item em menu/main_tabs.xml (ex.: "tab_filtros"). Uma view pode pertencer a
 * várias abas separando os nomes por vírgula. Views sem tag ficam sempre visíveis.
 *
 * As views das outras abas continuam na tela (apenas GONE), então o MainActivity lê e grava
 * todos os campos normalmente, não importa qual aba esteja aberta.
 *
 * Ergonomia (menos rolagem repetitiva):
 *  - cada aba LEMBRA onde você parou; voltar a ela devolve a mesma posição;
 *  - tocar de novo na aba que já está aberta volta ao topo;
 *  - abas com 3 ou mais seções ganham uma fileira de atalhos no topo ("Ganho por hora",
 *    "Distância"...). Os nomes vêm do título de cada card (o TextView marcado com
 *    android:accessibilityHeading="true"), então novos cards entram sozinhos nos atalhos.
 */
class SectionTabsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private var bottomNav: BottomNavigationView? = null
    private var content: ViewGroup? = null
    private var scroller: ScrollView? = null
    private var jumpBar: HorizontalScrollView? = null
    private var jumpRow: LinearLayout? = null
    private var currentTab: String? = null
    private val scrollByTab = HashMap<String, Int>()
    /** A primeira exibicao nao anima (o app esta abrindo); as trocas de aba sim. */
    private var animateTabChanges = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        content = findViewById<ViewGroup>(R.id.rootContentLayout)
        scroller = findViewById<ScrollView>(R.id.rootScrollView)
        installJumpBar()

        bottomNav?.setOnItemSelectedListener { item ->
            rememberScroll()
            animateTabChanges = true
            showTab(item.itemId)
            // Restaura depois do novo layout da aba (dois "post" = depois da medida do conteúdo novo)
            val target = currentTab?.let { scrollByTab[it] } ?: 0
            scroller?.scrollTo(0, 0)
            scroller?.post { scroller?.post { scroller?.scrollTo(0, target) } }
            true
        }
        bottomNav?.setOnItemReselectedListener {
            scroller?.smoothScrollTo(0, 0)
        }
        syncWithSelectedTab()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Ao recriar a tela (troca de tema, rotação) a barra restaura a aba sozinha,
        // sem avisar o listener. Reaplica aqui para o conteúdo acompanhar.
        syncWithSelectedTab()
    }

    private fun rememberScroll() {
        val tab = currentTab ?: return
        scrollByTab[tab] = scroller?.scrollY ?: 0
    }

    private fun syncWithSelectedTab() {
        bottomNav?.let { showTab(it.selectedItemId) }
    }

    private fun showTab(itemId: Int) {
        val tabName = try {
            resources.getResourceEntryName(itemId)
        } catch (e: Resources.NotFoundException) {
            return
        }
        currentTab = tabName
        applyTo(this, tabName)
        content?.let { applyTo(it, tabName) }
        rebuildJumpChips(tabName)
        if (animateTabChanges) {
            animateTabChanges = false
            content?.let { group ->
                val shown = (0 until group.childCount).map { group.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
                UiPolish.animateEntrance(shown)
            }
        }
    }

    private fun applyTo(group: ViewGroup, tabName: String) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            val tag = child.tag as? String ?: continue
            if (!tag.startsWith(TAB_PREFIX)) continue
            val belongs = tag.split(',').any { it.trim() == tabName }
            child.visibility = if (belongs) View.VISIBLE else View.GONE
        }
    }

    // ------------------------------------------------------------------
    // Atalhos de seção
    // ------------------------------------------------------------------

    private fun installJumpBar() {
        val sc = scroller ?: return
        val index = indexOfChild(sc)
        if (index < 0) return
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(4))
        }
        val bar = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
            addView(row)
        }
        addView(bar, index, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        jumpBar = bar
        jumpRow = row
    }

    private fun rebuildJumpChips(tabName: String) {
        val row = jumpRow ?: return
        val bar = jumpBar ?: return
        val group = content ?: return
        row.removeAllViews()

        val sections = mutableListOf<Pair<String, View>>()
        for (i in 0 until group.childCount) {
            val card = group.getChildAt(i)
            val tag = card.tag as? String ?: continue
            if (!tag.split(',').any { it.trim() == tabName }) continue
            val title = findHeading(card)?.text?.toString()?.takeIf { it.isNotBlank() } ?: continue
            sections.add(title to card)
        }

        if (sections.size < 3) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        sections.forEachIndexed { idx, (title, card) ->
            val chip = Chip(context).apply {
                text = title
                isCheckable = false
                isClickable = true
                setEnsureMinTouchTargetSize(true)
                setOnClickListener {
                    scroller?.smoothScrollTo(0, (card.top - dp(8)).coerceAtLeast(0))
                }
            }
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                if (idx > 0) marginStart = dp(8)
            }
            row.addView(chip, lp)
        }
        bar.scrollTo(0, 0)
    }

    private fun findHeading(view: View): TextView? {
        if (view is TextView && view.isAccessibilityHeading) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findHeading(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAB_PREFIX = "tab_"
    }
}
