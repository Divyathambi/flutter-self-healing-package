package com.flutterImpactAnalyzer.healing

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea

/** Presentation only: changing views never changes proposal eligibility or approval. */
internal class HealingResultsView : JPanel(BorderLayout(0, 4)) {
    enum class Stage(val title: String) { PREVIEW("Check"), VALIDATION("Check"), APPLY("Apply") }

    val previewSummary = textArea()
    val validationSummary = textArea()
    val applySummary = textArea()
    val diff = textArea(code = true)
    val tabs = JTabbedPane()
    private val cards = CardLayout()
    private val overview = JPanel(cards)

    init {
        listOf(previewSummary, validationSummary, applySummary).zip(Stage.values()).forEach { (area, stage) ->
            overview.add(JScrollPane(area), stage.name)
        }
        tabs.addTab("Results", overview)
        tabs.addTab("Finder edits", JScrollPane(diff))
        add(tabs, BorderLayout.CENTER)
    }

    fun showStage(stage: Stage) {
        cards.show(overview, stage.name)
        tabs.setTitleAt(0, "${stage.title} results")
        tabs.selectedComponent = overview
    }

    private fun textArea(code: Boolean = false) = JTextArea().apply {
        isEditable = false
        lineWrap = !code
        wrapStyleWord = !code
        if (code) font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        margin = java.awt.Insets(8, 8, 8, 8)
    }
}


