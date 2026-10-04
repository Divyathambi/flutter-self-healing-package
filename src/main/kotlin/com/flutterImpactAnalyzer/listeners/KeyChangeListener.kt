package com.flutterImpactAnalyzer.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class KeyChangeListener : ProjectActivity {

    private val previousFileContent = ConcurrentHashMap<String, String>()

    // Fires analysis only after the user pauses typing (resets on every keystroke).
    private val debounceDelayMs = 1200

    // Persist across edits so repeated popups for the same change are suppressed.
    private val processedChanges = ConcurrentHashMap.newKeySet<String>()
    private val processedTreeChanges = ConcurrentHashMap<String, String>()

    // True while this plugin is rewriting files itself, so its own edits are not
    // re-analyzed as if the user had typed them (which would pop the dialog again).
    private val applyingEdits = AtomicBoolean(false)

    override suspend fun execute(project: Project) {

        // Debounced auto-detection: no manual save needed, and nothing fires while
        // the user is actively typing. Alarm + listener are disposed with the project.
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {

            override fun documentChanged(event: DocumentEvent) {

                if (applyingEdits.get()) return

                val document = event.document
                val file = FileDocumentManager.getInstance().getFile(document) ?: return
                if (file.extension != "dart") return

                val filePath = file.path

                // First time we see this file: the document already contains this edit,
                // so recover the text as it was *before* it. Recording the current text
                // (as before) lost the first edit entirely, e.g. a key renamed with a
                // single paste/replace was never detected.
                previousFileContent.computeIfAbsent(filePath) { textBefore(event) }

                // Restart the countdown; analysis runs ~debounce ms after typing stops.
                // We pass the Document (not a text snapshot) so the background pass reads
                // the *latest* text and never analyzes a stale, mid-typing version.
                alarm.cancelAllRequests()
                alarm.addRequest({
                    analyze(project, filePath, document)
                }, debounceDelayMs)
            }

        }, project)
    }

    /** Document text as it was before [event] was applied. */
    private fun textBefore(event: DocumentEvent): String {
        val current = event.document.text
        val start = event.offset.coerceIn(0, current.length)
        val end = (event.offset + event.newLength).coerceIn(start, current.length)
        return StringBuilder(current).replace(start, end, event.oldFragment.toString()).toString()
    }

    // Result of the (background) analysis, presented later on the UI thread.
    private sealed interface Detection {
        data class KeyRename(
            val oldKey: String,
            val newKey: String,
            val impactedFiles: List<VirtualFile>
        ) : Detection

        data class TreeChange(
            val key: String,
            val depthChange: Int,
            val impactedTestFiles: List<VirtualFile>
        ) : Detection
    }

    // Analysis output plus the exact text it was computed from, so the baseline
    // advances to precisely what we analyzed (not a newer, unanalyzed version).
    private class Result(val analyzedText: String, val detections: List<Detection>)

    private fun analyze(
        project: Project,
        filePath: String,
        document: Document
    ) {
        // Heavy PSI work (parsing + project-wide scans) runs on a background thread
        // inside a non-blocking read action so the UI never freezes. The current
        // document text is read *inside* the read action, so if the user was still
        // typing, retries pick up the latest text and detect all changes at once.
        ReadAction.nonBlocking(Callable {
            val oldText = previousFileContent[filePath] ?: ""
            val newText = document.text
            if (oldText == newText) {
                Result(newText, emptyList())
            } else {
                Result(newText, computeDetections(project, filePath, oldText, newText))
            }
        })
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
                previousFileContent[filePath] = result.analyzedText
                result.detections.forEach { present(project, filePath, it) }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** Runs on a background thread inside a read action. No UI here. */
    private fun computeDetections(
        project: Project,
        filePath: String,
        oldText: String,
        newText: String
    ): List<Detection> {

        // Definition renames (static const foo = Key('lit')) are anchored on the
        // initializer literal, so unrelated keys are never mis-paired and several
        // simultaneous renames are each detected.
        val defRenames = DartKeyAnalyzer.detectDefinitionRenames(
            DartKeyAnalyzer.definitionNames(project, oldText),
            DartKeyAnalyzer.definitionNames(project, newText)
        ).filter { isMeaningfulRename(it.first, it.second) }

        if (defRenames.isNotEmpty()) {
            return defRenames.map { (oldKey, newKey) ->
                println("Key changed: $oldKey → $newKey")
                Detection.KeyRename(
                    oldKey,
                    newKey,
                    DartKeyAnalyzer.findImpactedFiles(project, oldKey, filePath)
                )
            }
        }

        // Usage renames (Keys.foo) have no literal anchor, so only an unambiguous
        // single change is treated as a rename.
        val usageRename = DartKeyAnalyzer.detectRename(
            DartKeyAnalyzer.usageKeys(project, oldText),
            DartKeyAnalyzer.usageKeys(project, newText)
        )
        if (usageRename != null && isMeaningfulRename(usageRename.first, usageRename.second)) {
            val (oldKey, newKey) = usageRename
            println("Key changed: $oldKey → $newKey")
            return listOf(
                Detection.KeyRename(
                    oldKey,
                    newKey,
                    DartKeyAnalyzer.findImpactedFiles(project, oldKey, filePath)
                )
            )
        }

        // Widget tree change: depth of the widget owning the first key changed.
        val key = DartKeyAnalyzer.usageKeys(project, newText).firstOrNull()
            ?: return emptyList()

        val depthChange = DartKeyAnalyzer.widgetDepth(project, newText, key) -
            DartKeyAnalyzer.widgetDepth(project, oldText, key)

        if (depthChange == 0) return emptyList()

        println("Widget hierarchy changed")

        val impactedTestFiles = DartKeyAnalyzer.findImpactedFiles(project, key, filePath)
            .filter { KeyTextRewriter.isTestFile(project.basePath, it.path) }

        return listOf(Detection.TreeChange(key, depthChange, impactedTestFiles))
    }

    /** Ignore incomplete typing and the completion popup's dummy identifier. */
    private fun isMeaningfulRename(oldKey: String, newKey: String): Boolean {
        if (oldKey.length < 3 || newKey.length < 3) return false
        if (oldKey.contains(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED) ||
            newKey.contains(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED)
        ) return false
        return true
    }

    /** Runs on the UI thread: dedup, dialogs and (on confirm) write actions. */
    private fun present(project: Project, filePath: String, detection: Detection) {
        when (detection) {
            is Detection.KeyRename -> {
                val changeId = "$filePath::${detection.oldKey} -> ${detection.newKey}"
                if (!processedChanges.add(changeId)) return
                if (processedChanges.size > 50) {
                    processedChanges.clear()
                    processedChanges.add(changeId)
                }

                val message = buildString {
                    append("Key Change Detected\n\n")
                    append("Old key: ${detection.oldKey}\n")
                    append("New key: ${detection.newKey}\n\n")

                    append("Impacted Files:\n")
                    if (detection.impactedFiles.isEmpty()) {
                        append("None\n")
                    } else {
                        detection.impactedFiles.forEach { append("• ${it.name}\n") }
                    }

                    append("\nApply changes?")
                }

                val result = Messages.showYesNoDialog(
                    project,
                    message,
                    "Flutter Key Change  Detector",
                    "Yes",
                    "No",
                    null
                )

                if (result == Messages.YES) {
                    replaceKeys(project, detection.impactedFiles, detection.oldKey, detection.newKey, 0)
                }
            }

            is Detection.TreeChange -> {
                val treeChangeId = "$filePath::${detection.key}::${detection.depthChange}"
                if (processedTreeChanges[filePath] == treeChangeId) return
                processedTreeChanges[filePath] = treeChangeId

                val message = buildString {
                    append("Widget Hierarchy Change Detected\n\n")

                    if (detection.depthChange > 0) {
                        append("Widget wrapped by ${detection.depthChange} level(s) \n\n")
                    } else {
                        append("Widget unwrapped by ${-detection.depthChange} level(s) \n\n")
                    }

                    append("Impacted Test Files: \n")
                    if (detection.impactedTestFiles.isEmpty()) {
                        append("None\n")
                    } else {
                        detection.impactedTestFiles.forEach { append("• ${it.name}\n") }
                    }

                    append("\nUpdate test matchers?")
                }

                val result = Messages.showYesNoDialog(
                    project,
                    message,
                    "Flutter Widget Tree Change Detector",
                    "Yes",
                    "No",
                    null
                )

                if (result == Messages.YES) {
                    applyWidgetCountUpdate(
                        project,
                        detection.impactedTestFiles,
                        detection.key,
                        detection.depthChange
                    )
                }
            }
        }
    }


    // Apply for only widget count updation
    private fun applyWidgetCountUpdate(
        project: Project,
        files: Collection<VirtualFile>,
        key: String,
        depthChange: Int
    ) {
        rewriteFiles(project, files) { file, content ->
            if (!KeyTextRewriter.isTestFile(project.basePath, file.path)) content
            else KeyTextRewriter.updateWidgetCount(content, key, depthChange)
        }
    }

    // Replace Keys
    private fun replaceKeys(
        project: Project,
        files: Collection<VirtualFile>,
        oldKey: String,
        newKey: String,
        depthChange: Int
    ) {
        rewriteFiles(project, files) { file, content ->
            var updated = KeyTextRewriter.renameKey(content, oldKey, newKey)

            // Apply widget count if test file
            if (KeyTextRewriter.isTestFile(project.basePath, file.path)) {
                updated = KeyTextRewriter.updateWidgetCount(updated, newKey, depthChange)
            }
            updated
        }
    }

    /**
     * Applies [transform] to each file through its Document (not by re-reading the file
     * from disk). That way unsaved editor changes are preserved instead of being
     * overwritten, the edit joins the undo history, and files whose content did not
     * change are not touched at all.
     */
    private fun rewriteFiles(
        project: Project,
        files: Collection<VirtualFile>,
        transform: (VirtualFile, String) -> String
    ) {
        val documents = FileDocumentManager.getInstance()
        applyingEdits.set(true)
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                files.forEach { file ->
                    val document = documents.getDocument(file) ?: return@forEach
                    val before = document.text
                    val after = transform(file, before)
                    if (after == before) return@forEach

                    document.setText(after)
                    documents.saveDocument(document)
                    // Our own edit becomes the new baseline for this file.
                    previousFileContent[file.path] = after
                }
            }
        } finally {
            applyingEdits.set(false)
        }
    }
}

/**
 * What is our motive?
 * 1) Pop up should detect the changes in the key - This is done
 * 2) Pop up should detect the widget tree changes - This is also done
 *
 * What is the problem facing?
 * 1) The update widget count is only working if the current working file is a test file
 *
 * What it should do?
 * 1) It should ideally find the test files, make the changes there.
 *
 * What can I do to achieve that?
 *
 * 1) Find all the files using PSI manager. List<com.intellij.openapi.vfs.VirtualFile>
 * 2) Check whether there are any test files. Check whether any of the file in the list, the path of it, contains the name "test"
 * 3) IF there are any test files with the key and IF there is a widget tree change -> Then show the pop up with the list of test files impacted.
 */