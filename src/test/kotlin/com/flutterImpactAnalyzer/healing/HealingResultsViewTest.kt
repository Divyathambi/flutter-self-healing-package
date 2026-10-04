package com.flutterImpactAnalyzer.healing

import org.junit.Assert.*
import org.junit.Test
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class HealingResultsViewTest {
    @Test fun `default view has only results and finder edits`() = onEdt {
        val view = HealingResultsView()
        assertEquals(2, view.tabs.tabCount)
        assertEquals("Results", view.tabs.getTitleAt(0))
        assertEquals("Finder edits", view.tabs.getTitleAt(1))
        assertEquals(listOf(view.tabs), view.components.toList())
    }

    @Test fun `all stages keep only results and finder edits`() = onEdt {
        val view = HealingResultsView()
        HealingResultsView.Stage.values().forEach { stage ->
            view.showStage(stage)
            assertEquals(2, view.tabs.tabCount)
            assertEquals("${stage.title} results", view.tabs.getTitleAt(0))
            assertEquals("Finder edits", view.tabs.getTitleAt(1))
        }
    }

    @Test fun `stage changes reveal their result instead of leaving an old diff visible`() = onEdt {
        val view = HealingResultsView()
        view.previewSummary.text = "Proposal only"
        view.validationSummary.text = "Validation blocked"
        view.applySummary.text = "Unexpected content preserved; inspect backups"
        val summaries = listOf(view.previewSummary, view.validationSummary, view.applySummary)
        HealingResultsView.Stage.values().forEachIndexed { index, stage ->
            view.tabs.selectedIndex = 1
            view.showStage(stage)
            assertEquals(0, view.tabs.selectedIndex)
            assertEquals("${stage.title} results", view.tabs.getTitleAt(0))
            val cards = view.tabs.selectedComponent as JPanel
            val visible = cards.components.single { it.isVisible } as JScrollPane
            assertSame(summaries[index], visible.viewport.view)
        }
    }

    @Test fun `switching views preserves recovery warnings`() = onEdt {
        val view = HealingResultsView()
        val warning = "Rollback conflict — unexpected content preserved. Review backups manually."
        view.applySummary.text = warning
        view.showStage(HealingResultsView.Stage.PREVIEW)
        view.tabs.selectedIndex = 1
        view.showStage(HealingResultsView.Stage.APPLY)
        assertEquals(0, view.tabs.selectedIndex)
        val cards = view.tabs.selectedComponent as JPanel
        val visible = cards.components.single { it.isVisible } as JScrollPane
        assertSame(view.applySummary, visible.viewport.view)
        assertEquals(warning, view.applySummary.text)
    }

    @Test fun `prose wraps while exact diffs remain read only`() = onEdt {
        val view = HealingResultsView()
        val patch = "--- a/test.dart\n+++ b/test.dart\n-  find.byKey(Key('old'));\n+  find.byKey(Key('new'));\n"
        view.diff.text = patch
        listOf(view.previewSummary, view.validationSummary, view.applySummary).forEach {
            assertTrue(it.lineWrap)
            assertFalse(it.isEditable)
        }
        assertFalse(view.diff.lineWrap)
        assertFalse(view.diff.isEditable)
        view.showStage(HealingResultsView.Stage.VALIDATION)
        assertEquals(patch, view.diff.text)
        assertSame(view.diff, (view.tabs.getComponentAt(1) as JScrollPane).viewport.view as JTextArea)
    }

    private fun onEdt(test: () -> Unit) = SwingUtilities.invokeAndWait(test)
}

