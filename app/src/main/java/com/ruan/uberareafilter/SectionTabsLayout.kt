package com.ruan.uberareafilter

import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.bottomnavigation.BottomNavigationView

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
 */
class SectionTabsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private var bottomNav: BottomNavigationView? = null
    private var content: ViewGroup? = null
    private var scroller: ScrollView? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        content = findViewById<ViewGroup>(R.id.rootContentLayout)
        scroller = findViewById<ScrollView>(R.id.rootScrollView)

        bottomNav?.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            scroller?.scrollTo(0, 0)
            true
        }
        syncWithSelectedTab()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Ao recriar a tela (troca de tema, rotação) a barra restaura a aba sozinha,
        // sem avisar o listener. Reaplica aqui para o conteúdo acompanhar.
        syncWithSelectedTab()
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
        applyTo(this, tabName)
        content?.let { applyTo(it, tabName) }
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

    private companion object {
        const val TAB_PREFIX = "tab_"
    }
}
