package com.flutterImpactAnalyzer.healing

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel

/** Approval is scoped to this immutable selection, not persisted as a global opt-in. */
internal class ApplyApprovalDialog(project: Project, private val proposal: ValidatedProposal) : DialogWrapper(project) {
    init {
        title = "Review Finder Repair"
        setOKButtonText("Approve & Apply")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val selected = proposal.selection
        val explanation = JBTextArea(
            "${selected.report.editCount} finder edits in ${selected.report.files.size} test file(s)\n" +
                "Target: ${selected.command.project}"
        ).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false }
        val diff = JBTextArea(selected.report.patch).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            caretPosition = 0
        }
        return JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(820, 620)
            add(explanation, BorderLayout.NORTH)
            add(JBScrollPane(diff).apply {
                border = BorderFactory.createTitledBorder("Exact finder edits — read only")
            }, BorderLayout.CENTER)
        }
    }
}
