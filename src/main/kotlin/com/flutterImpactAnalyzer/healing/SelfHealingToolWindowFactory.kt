package com.flutterImpactAnalyzer.healing

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SelfHealingToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SelfHealingPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Self-Healing", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

private class SelfHealingPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val settings = project.getService(SelfHealingSettings::class.java)
    private val target = pathField("Target Git-root project")
    private val healer = pathField("Healer directory (contains bin/flutter_test_healer.dart)")
    private val sdk = pathField("Flutter SDK directory")
    private val setupToggle = JButton("Edit setup")
    private val setup = JPanel(GridLayout(0, 1, 0, 4))
    private val save = JButton("Use these paths")
    private val check = JButton("1. Find & Check Fix")
    private val apply = JButton("2. Review & Apply")
    private val cancel = JButton("Cancel")
    private val status = messageArea("Ready. No tests run or files changed.")
    private val nextStep = messageArea("")
    private val targetDisplay = messageArea("")
    private val results = HealingResultsView()
    private val summary = results.previewSummary
    private val diff = results.diff
    private val validationSummary = results.validationSummary
    private val applySummary = results.applySummary
    private var selectedPreview: SelectedPreview? = null
    private var selectedValidation: ValidatedProposal? = null
    private val disposed = AtomicBoolean()
    private val revision = AtomicLong()
    private var cancellation = AtomicBoolean()
    private var busy = false
    private var applying = false

    init {
        val paths = settings.state
        target.text = paths.projectPath.ifBlank { project.basePath.orEmpty() }
        healer.text = paths.healerPath
        sdk.text = paths.flutterSdkPath
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        val config = JPanel(BorderLayout(0, 8))
        val configuration = JPanel(BorderLayout(0, 4))
        val targetRow = JPanel(BorderLayout(8, 0))
        targetRow.add(targetDisplay, BorderLayout.CENTER)
        targetRow.add(setupToggle, BorderLayout.EAST)
        configuration.add(targetRow, BorderLayout.NORTH)
        setup.border = BorderFactory.createTitledBorder("Local setup")
        setup.add(row("Target project", target))
        setup.add(row("Healer directory", healer))
        setup.add(row("Flutter SDK", sdk))
        setup.add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(save) })
        configuration.add(setup, BorderLayout.CENTER)
        config.add(configuration, BorderLayout.NORTH)
        val workflow = JPanel(BorderLayout(0, 4))
        val buttons = JPanel(GridLayout(1, 2, 8, 0))
        buttons.add(check)
        buttons.add(apply)
        workflow.add(buttons, BorderLayout.NORTH)
        workflow.add(nextStep, BorderLayout.CENTER)
        val progress = JPanel(BorderLayout(8, 0))
        progress.add(status, BorderLayout.CENTER)
        progress.add(cancel, BorderLayout.EAST)
        workflow.add(progress, BorderLayout.SOUTH)
        config.add(workflow, BorderLayout.CENTER)
        add(config, BorderLayout.NORTH)
        add(results, BorderLayout.CENTER)
        summary.text = "Repair test finders after an isolated widget-key rename.\n\n" +
            "1. Find & Check Fix — find a supported repair and test it in disposable copies.\n" +
            "2. Review & Apply — review the finder edits and approve the repair.\n\n" +
            "Save your source and test files before starting."
        cancel.isEnabled = false
        cancel.isVisible = false
        check.isEnabled = false
        apply.isEnabled = false
        setupToggle.addActionListener { showSetup(!setup.isVisible) }
        save.addActionListener {
            persistPaths()
            showSetup(false)
            status.text = "Paths saved locally for this IDE project."
            refreshActions()
        }
        check.addActionListener { startCheck() }
        apply.addActionListener { startApply() }
        cancel.addActionListener {
            if (applying) return@addActionListener
            cancellation.set(true)
            cancel.isEnabled = false
            status.text = "Cancelling operation…"
        }
        val invalidator = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = invalidatePreview()
            override fun removeUpdate(e: DocumentEvent) = invalidatePreview()
            override fun changedUpdate(e: DocumentEvent) = invalidatePreview()
        }
        listOf(target, healer, sdk).forEach { it.textField.document.addDocumentListener(invalidator) }
        showSetup(listOf(target, healer, sdk).any { it.text.isBlank() })
        refreshActions()
    }

    private fun pathField(title: String) = TextFieldWithBrowseButton().also {
        it.addBrowseFolderListener(title, null, project, FileChooserDescriptorFactory.createSingleFolderDescriptor())
        Disposer.register(this, it)
    }

    private fun row(label: String, field: TextFieldWithBrowseButton) = JPanel(BorderLayout(8, 0)).apply {
        add(JLabel(label).apply { labelFor = field.textField }, BorderLayout.WEST)
        add(field, BorderLayout.CENTER)
    }

    private fun messageArea(message: String) = JBTextArea(message).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
    }

    private fun showSetup(visible: Boolean) {
        setup.isVisible = visible
        setupToggle.text = if (visible) "Hide setup" else "Edit setup"
        revalidate()
        repaint()
    }

    private fun invalidatePreview() {
        revision.incrementAndGet()
        if (busy && !applying) cancellation.set(true)
        selectedPreview = null
        selectedValidation = null
        check.isEnabled = false
        apply.isEnabled = false
        applySummary.text = "Configuration changed; no previous approval or validation is selected."
        validationSummary.text = "Configuration changed; any previous validation is no longer selected."
        summary.text = "Configuration changed. Use Find & Check Fix again."
        diff.text = ""
        status.text = "Previous check cleared."
        results.showStage(HealingResultsView.Stage.PREVIEW)
        refreshActions()
    }

    private fun persistPaths(): SelfHealingSettings.Paths {
        val paths = SelfHealingSettings.Paths(target.text.trim(), healer.text.trim(), sdk.text.trim())
        settings.loadState(paths)
        return paths
    }

    private fun targetHasUnsavedInputs(root: Path): Boolean = ReadAction.compute<Boolean, RuntimeException> {
        val documents = FileDocumentManager.getInstance()
        documents.unsavedDocuments.any {
            val file = documents.getFile(it)
            file != null && Path.of(file.path).toAbsolutePath().normalize().startsWith(root)
        }
    }

    private fun startCheck() {
        if (busy || disposed.get() || project.isDisposed || listOf(target, healer, sdk).any { it.text.isBlank() }) return
        val paths = persistPaths()
        val root = try { Path.of(paths.projectPath).toRealPath() } catch (_: Exception) { null }
        fun blockUnsaved(): Boolean {
            if (root == null || !targetHasUnsavedInputs(root)) return false
            invalidatePreview()
            status.text = "Save your target files before checking a fix."
            summary.text = "Unsaved edits detected. Save or revert them, then use Find & Check Fix again. Nothing was saved automatically."
            return true
        }
        if (blockUnsaved()) return
        val requestRevision = revision.incrementAndGet()
        cancellation = AtomicBoolean()
        val requestedCancel = cancellation
        selectedPreview = null
        selectedValidation = null
        diff.text = ""
        applySummary.text = "No repair approved or applied."
        summary.text = "Looking for a supported finder repair…"
        validationSummary.text = "Tests have not started."
        results.showStage(HealingResultsView.Stage.PREVIEW)
        showSetup(false)
        setBusy(true)
        status.text = "Finding a fix…"
        object : Task.Backgroundable(project, "Find & Check Fix", true) {
            private var runArtifact: PrivateValidationRun? = null
            private var result: CheckFixResult? = null
            private val finished = AtomicBoolean()

            private fun current() = !disposed.get() && !project.isDisposed && revision.get() == requestRevision
            private fun publish(update: () -> Unit) {
                ApplicationManager.getApplication().invokeLater {
                    if (current() && !requestedCancel.get() && !finished.get()) update()
                }
            }

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    indicator.checkCanceled()
                    val command = PreviewCommand.prepare(paths.projectPath, paths.healerPath, paths.flutterSdkPath)
                    result = CheckFixWorkflow.execute(
                        command, Path.of(paths.flutterSdkPath).toRealPath(),
                        progress = { indicator.text = it },
                        cancelled = { indicator.isCanceled || requestedCancel.get() || !current() },
                        checkInputs = { targetRoot ->
                            check(!targetHasUnsavedInputs(targetRoot)) {
                                "Unsaved target edits detected. Save or revert them, then use Find & Check Fix again."
                            }
                        },
                        onPreview = { selection -> publish {
                            diff.text = selection.report.patch.ifBlank { "No finder edits proposed." }
                            diff.caretPosition = 0
                            if (selection.canValidate) {
                                status.text = "Fix found — checking selected tests…"
                                validationSummary.text = "Checking ${selection.report.editCount} finder edits in disposable copies."
                                results.showStage(HealingResultsView.Stage.VALIDATION)
                            }
                        } },
                        onValidationRun = { artifact ->
                            runArtifact = artifact
                            indicator.text2 = "Private reports: ${artifact.directory}"
                        }
                    )
                } catch (_: CancellationException) {
                    throw ProcessCanceledException()
                }
            }

            override fun onSuccess() {
                finished.set(true)
                if (!current()) return
                if (requestedCancel.get()) { onCancel(); return }
                val checked = result ?: return
                if (targetHasUnsavedInputs(checked.selection.command.project)) {
                    incomplete("Target files have unsaved changes. Save or revert them, then check again.")
                    return
                }
                selectedPreview = checked.selection
                selectedValidation = checked.proposal
                diff.text = checked.selection.report.patch.ifBlank { "No finder edits proposed." }
                val validation = checked.validation
                if (validation != null) {
                    status.text = if (checked.proposal != null) "Ready to review — the selected repair passed validation."
                        else "Fix not verified — review the result below."
                    validationSummary.text = ValidationSummary.render(validation, checked.selection.report)
                    results.showStage(HealingResultsView.Stage.VALIDATION)
                } else {
                    status.text = "No supported fix is ready."
                    summary.text = buildString {
                        append(checked.selection.report.reason)
                        checked.selection.report.warnings.forEach { append("\n$it") }
                        append("\n\nNo tests ran and no repair was applied. This does not mean tests pass.")
                        if (checked.diagnostics.isNotBlank()) append("\n\n${checked.diagnostics}")
                    }
                    results.showStage(HealingResultsView.Stage.PREVIEW)
                }
                listOf(summary, validationSummary, diff).forEach { it.caretPosition = 0 }
            }

            private fun incomplete(message: String) {
                finished.set(true)
                if (!current()) return
                selectedPreview = null
                selectedValidation = null
                status.text = "Check incomplete — no fix is ready to apply."
                summary.text = "$message\n\nNo repair was approved or applied. Use Find & Check Fix to retry." +
                    (runArtifact?.let { "\n\nPrivate request: ${it.directory}\n" +
                        "Inspect retained logs and any leftover disposable copies. Process cleanup is best effort." } ?: "")
                diff.text = ""
                results.showStage(HealingResultsView.Stage.PREVIEW)
            }

            override fun onThrowable(error: Throwable) = incomplete(error.message ?: error.javaClass.simpleName)
            override fun onCancel() = incomplete("Check cancelled. Partial results are not accepted.")
            override fun onFinished() {
                finished.set(true)
                if (!disposed.get() && !project.isDisposed) setBusy(false)
            }
        }.queue()
    }

    private fun startApply() {
        val proposal = selectedValidation ?: return
        if (busy || disposed.get() || !proposal.eligible || proposal.selection !== selectedPreview) return
        fun hasUnsavedInputs(): Boolean {
            val documents = FileDocumentManager.getInstance()
            return documents.unsavedDocuments.any {
                val file = documents.getFile(it)
                file != null && Path.of(file.path).toAbsolutePath().normalize().startsWith(proposal.selection.command.project)
            }
        }
        fun blockUnsaved() {
            selectedValidation = null
            selectedPreview = null
            status.text = "Apply blocked: save target files, then use Find & Check Fix again."
            applySummary.text = "No apply command started. Unsaved edits must not be overwritten."
            results.showStage(HealingResultsView.Stage.APPLY)
            refreshActions()
        }
        if (hasUnsavedInputs()) { blockUnsaved(); return }
        if (!ApplyApprovalDialog(project, proposal).showAndGet()) return
        // Modal review can take time: recheck local buffers and selection before launching.
        if (selectedValidation !== proposal || disposed.get() || project.isDisposed) return
        if (hasUnsavedInputs()) { blockUnsaved(); return }
        selectedValidation = null // Approval is consumed once, including blocked/failed attempts.
        selectedPreview = null
        applying = true
        setBusy(true)
        status.text = "Guarded apply running — do not edit files or close the IDE."
        applySummary.text = "Saving approval and bound input reports. Cancellation is disabled to allow CLI rollback to finish."
        results.showStage(HealingResultsView.Stage.APPLY)
        object : Task.Backgroundable(project, "Applying validated finder repair — do not interrupt", false) {
            private var request: ApplyRequest? = null
            private var report: ApplyReport? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val input = ApplyRequest.create(proposal, approved = true)
                request = input
                indicator.text2 = "Private request: ${input.storage.directory}"
                report = ApplyWorkflow.execute(input) {
                    if (!disposed.get() && !project.isDisposed) indicator.text = it
                }
            }

            override fun onSuccess() {
                if (disposed.get() || project.isDisposed) return
                val result = report ?: return
                status.text = when (result.status) {
                    "applied_and_tests_passed" -> "Finder repair applied; selected tests passed. No commit created."
                    "already_applied" -> "Exact repair already present — no new writes or test run."
                    "rolled_back" -> "Apply not accepted; tool-owned edits rolled back. Review failure reason."
                    "rollback_conflict" -> "Rollback conflict — unexpected content preserved. Review backups manually."
                    else -> "Apply: ${result.status}. Review the report before continuing."
                }
                applySummary.text = result.details
                applySummary.caretPosition = 0
            }

            override fun onThrowable(error: Throwable) {
                if (disposed.get() || project.isDisposed) return
                status.text = "Apply outcome not verified — manual review required."
                applySummary.text = "${error.message ?: error.javaClass.simpleName}\n\n" +
                    "Private request: ${request?.storage?.directory ?: "not created; command not started"}\n" +
                    "Shared artifact root: ${request?.root ?: PrivateValidationRun.defaultRoot()}\n" +
                    "Project lock: ${request?.lockPath ?: "not acquired by this request"}\n\n" +
                    "If execution started, do not assume unchanged files or completed rollback. Inspect journals, logs, and backups.\n" +
                    "Never delete a lock or overwrite independent edits automatically. Use Find & Check Fix again before retrying."
            }

            override fun onCancel() = onThrowable(IllegalStateException("Apply interrupted; its outcome is unknown."))

            override fun onFinished() {
                if (!disposed.get() && !project.isDisposed) {
                    // Refresh only proposed files, including after rollback or an uncertain outcome. Never auto-save buffers.
                    val root = proposal.selection.command.project
                    val files = proposal.selection.report.files.mapNotNull {
                        val path = root.resolve(it).normalize()
                        if (path.startsWith(root)) LocalFileSystem.getInstance().findFileByPath(path.toString()) else null
                    }
                    VfsUtil.markDirtyAndRefresh(true, false, false, *files.toTypedArray())
                    applying = false
                    setBusy(false)
                }
            }
        }.queue()
    }

    private fun refreshActions() {
        val configured = listOf(target, healer, sdk).all { it.text.isNotBlank() }
        check.isEnabled = !busy && configured
        apply.isEnabled = !busy && selectedValidation?.eligible == true && selectedValidation?.selection === selectedPreview
        targetDisplay.text = "Target: ${target.text.trim().ifBlank { "Not configured" }}"
        targetDisplay.toolTipText = "Healer: ${healer.text}\nFlutter SDK: ${sdk.text}"
        check.toolTipText = "Find a supported repair and check it with selected tests in disposable copies. No repair is applied."
        apply.toolTipText = "Requires successful matching validation. Opens the exact diff for explicit approval; does not apply immediately."
        nextStep.text = when {
            applying -> "Applying — keep the IDE open and do not edit target files. Cancellation is disabled for safe cleanup."
            busy -> "Working… Follow progress in the IDE status bar. You can cancel before apply."
            !configured -> "Start here: enter all three directories in Edit setup."
            apply.isEnabled -> "Next: Review & Apply. Check the exact edits and approve only if they are correct."
            selectedPreview != null -> "No fix is ready to apply. Read the result below before checking again."
            else -> "Save your target files, then Find & Check Fix."
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        listOf(target, healer, sdk).forEach { it.isEnabled = !value }
        save.isEnabled = !value
        setupToggle.isEnabled = !value
        refreshActions()
        cancel.isEnabled = value && !applying
        cancel.isVisible = value && !applying
    }

    override fun dispose() {
        disposed.set(true)
        cancellation.set(true)
    }
}

