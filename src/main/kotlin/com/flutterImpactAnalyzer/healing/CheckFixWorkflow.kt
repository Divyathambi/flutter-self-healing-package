package com.flutterImpactAnalyzer.healing

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal data class CheckFixResult(
    val selection: SelectedPreview,
    val validationRun: PrivateValidationRun?,
    val validation: ValidationReport?,
    val diagnostics: String
) {
    val proposal: ValidatedProposal?
        get() = if (selection.canValidate && validation?.successful == true && validationRun != null)
            ValidatedProposal(selection, validationRun, validation) else null
}

/** One read-only/isolated operation. Applying edits is deliberately not part of this workflow. */
internal object CheckFixWorkflow {
    fun execute(
        command: PreviewCommand,
        sdk: Path,
        progress: (String) -> Unit = {},
        cancelled: () -> Boolean = { false },
        checkInputs: (Path) -> Unit = {},
        onPreview: (SelectedPreview) -> Unit = {},
        onValidationRun: (PrivateValidationRun) -> Unit = {},
        runPreview: (PreviewCommand, () -> Boolean) -> ProcessResult = { input, stop ->
            PreviewProcessRunner.run(input.arguments, input.workingDirectory, cancelled = stop)
        },
        createRun: (SelectedPreview) -> PrivateValidationRun = {
            PrivateValidationRun.create(it.command.project, it.report.rawJson)
        },
        runValidation: (SelectedPreview, PrivateValidationRun, (String) -> Unit, () -> Boolean) -> ValidationReport =
            ValidationWorkflow::execute
    ): CheckFixResult {
        fun checkpoint() {
            if (cancelled()) throw CancellationException("Fix check cancelled.")
            checkInputs(command.project)
            if (cancelled()) throw CancellationException("Fix check cancelled.")
        }
        var artifact: PrivateValidationRun? = null
        try {
            checkpoint()
            progress("Finding a supported fix…")
            val output = runPreview(command, cancelled)
            checkpoint()
            val selection = SelectedPreview(command, PreviewReport.parse(output, command.project), sdk)
            onPreview(selection)
            checkpoint()
            if (!selection.canValidate) return CheckFixResult(selection, null, null, output.stderr)

            progress("Checking the fix with selected tests…")
            checkpoint()
            val run = createRun(selection)
            artifact = run
            onValidationRun(run)
            checkpoint()
            val validation = runValidation(selection, run, progress, cancelled)
            checkpoint()
            return CheckFixResult(selection, run, validation, output.stderr)
        } catch (error: Exception) {
            // The validator journals its own failures. Cover cancellation/input drift around that call too.
            artifact?.let { run ->
                runCatching {
                    if (!Files.exists(run.directory.resolve("check-outcome.txt"))) {
                        run.write("check-outcome.txt", "CHECK INCOMPLETE: ${error.message}\n" +
                            "No result selected for apply. Inspect retained reports and any disposable copies.\n" +
                            "No repair was approved or applied by this check.\n")
                    }
                }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }
}
