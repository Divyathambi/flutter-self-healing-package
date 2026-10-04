package com.flutterImpactAnalyzer.healing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CancellationException

internal data class SelectedPreview(val command: PreviewCommand, val report: PreviewReport, val sdk: Path) {
    val canValidate: Boolean get() = report.status == "requires_review"
}

internal object ValidationCommand {
    const val STAGE_TIMEOUT_SECONDS = 180
    val overallTimeout: Duration = Duration.ofMinutes(25)

    fun prepare(selection: SelectedPreview, run: PrivateValidationRun): List<String> {
        require(selection.canValidate) { "Only a reviewable preview can be validated." }
        // Reparse the exact stored payload instead of trusting a display model alone.
        val checked = PreviewReport.parse(ProcessResult(0, selection.report.rawJson, ""), selection.command.project)
        require(checked.status == "requires_review") { "No reviewable proposal." }
        run.verifyPreview()
        require(run.previewBytes.contentEquals(selection.report.rawJson.toByteArray(Charsets.UTF_8))) { "Preview binding mismatch." }
        val flutter = selection.sdk.resolve("bin/flutter")
        require(Files.isRegularFile(flutter) && Files.isExecutable(flutter)) { "Selected SDK has no executable bin/flutter." }
        return selection.command.arguments.take(3) + listOf(
            "validate", "--project", selection.command.project.toString(),
            "--report", run.previewPath.toString(), "--flutter", flutter.toString(),
            "--timeout", STAGE_TIMEOUT_SECONDS.toString(), "--artifacts-dir", run.directory.toString(), "--json"
        )
    }
}

internal data class ValidationReport(val status: String, val details: String, val rawJson: String, val directory: Path) {
    val successful: Boolean get() = status in successStatuses

    companion object {
        private val successStatuses = setOf("before_failed_after_passed", "before_compile_failed_after_passed")
        private val statuses = setOf(
            "before_failed_after_passed", "before_compile_failed_after_passed", "baseline_unavailable", "baseline_setup_failed", "baseline_failed",
            "baseline_inconclusive", "requires_investigation", "failure_not_reproduced", "repair_failed",
            "setup_failed", "execution_failed", "blocked", "input_changed", "cleanup_failed"
        )

        fun parse(output: ProcessResult, selection: SelectedPreview, run: PrivateValidationRun): ValidationReport {
            require(output.exitCode in setOf(0, 2)) { "Validation command failed (exit ${output.exitCode}).\n${output.stderr.take(8000)}" }
            try {
                run.verifyPreview()
                require(run.previewBytes.contentEquals(selection.report.rawJson.toByteArray(Charsets.UTF_8))) { "Preview binding mismatch." }
                PreviewReport.parse(ProcessResult(0, selection.report.rawJson, ""), selection.command.project)
                val previewRoot = JsonParser.parseString(selection.report.rawJson).asJsonObject
                val root = JsonParser.parseString(output.stdout).asJsonObject
                fun JsonObject.text(key: String): String {
                    val value = get(key)
                    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Missing string: $key" }
                    return value.asString
                }
                fun JsonObject.flag(key: String): Boolean? {
                    val value = get(key)
                    if (value == null || value.isJsonNull) return null
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "Invalid boolean: $key" }
                    return value.asBoolean
                }
                require(root.get("schemaVersion")?.toString() == "1" && root.get("bindingVersion")?.toString() == "1") {
                    "Unsupported validation schema/binding."
                }
                val status = root.text("status")
                require(status in statuses) { "Unknown validation status: $status" }
                val success = status in successStatuses
                val compileMode = status == "before_compile_failed_after_passed"
                require(output.exitCode == if (success) 0 else 2) { "Exit code contradicts validation status." }
                require(Path.of(root.text("project")) == selection.command.project) { "Validation belongs to another project." }
                val artifacts = Path.of(root.text("artifacts"))
                require(artifacts.parent == run.directory && artifacts.fileName.toString().startsWith("validate-") && artifacts.toRealPath() == artifacts) {
                    "Validation reports must be in this request's private directory."
                }
                fun readLocal(file: Path, limit: Long): ByteArray {
                    require(file.parent == artifacts && file.toRealPath() == file && Files.isRegularFile(file)) { "Invalid report file." }
                    require(Files.size(file) <= limit) { "Report exceeds size limit." }
                    return Files.readAllBytes(file)
                }
                val persisted = JsonParser.parseString(String(readLocal(artifacts.resolve("result.json"), 16L * 1024 * 1024), Charsets.UTF_8))
                require(persisted == root) { "CLI output differs from the saved validation report." }
                require(root.text("previewSha256") == run.previewSha256) { "Validation is not bound to the displayed preview." }
                require(Path.of(root.text("inputPreviewReport")) == run.previewPath) { "Wrong input preview." }
                require(Path.of(root.text("previewReport")) == artifacts.resolve("preview.json")) { "Wrong saved preview." }
                require(readLocal(artifacts.resolve("preview.json"), 4L * 1024 * 1024).contentEquals(run.previewBytes)) { "Validated preview bytes changed." }
                if (root.has("baseline")) require(root.text("baseline") == selection.report.baseline) { "Baseline changed." }
                val baseline = root.getAsJsonObject("baselineCheck")
                val evidence = root.getAsJsonObject("failureEvidence")
                if (success) {
                    require(selection.canValidate && compileMode == SharedKeyReportContract.isSymbol(previewRoot)) {
                        "Validation mode does not match the selected repair."
                    }
                    require(root.text("baseline") == selection.report.baseline && root.flag("originalFilesUnchanged") == true &&
                        root.flag("workspaceRemoved") == true && root.flag("testsExecuted") == true && root.flag("baselineCoverageMatches") == true) {
                        "Successful validation is missing integrity, cleanup, or coverage checks."
                    }
                    require(baseline?.text("status") == "passed" && evidence?.text("assessment") ==
                        if (compileMode) "linked_compile_errors_passed_after" else "linked_failures_passed_after") {
                        "Successful validation is missing baseline/failure attribution."
                    }
                    if (compileMode) {
                        SharedKeyReportContract.validateCompile(root, previewRoot, selection.sdk.resolve("bin/flutter").toString())
                    } else {
                        if (previewRoot.get("schemaVersion").asInt == 3) {
                            LiteralBatchReportContract.validateEvidence(previewRoot, evidence!!)
                        }
                        val counts = listOf(baseline!!.getAsJsonObject("tests"), evidence!!.getAsJsonObject("before"), evidence.getAsJsonObject("after"))
                        counts.forEach { require(it.flag("complete") == true && it.get("skipped").asInt == 0 && it.get("incomplete").asInt == 0) }
                        require(counts[0].get("passed").asInt > 0 && counts[0].get("failed").asInt == 0 &&
                            counts[1].get("failed").asInt > 0 && counts[2].get("passed").asInt > 0 && counts[2].get("failed").asInt == 0)
                    }
                    val identities = root.getAsJsonArray("validatedTestIdentities").map {
                        require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Invalid test identity." }
                        it.asString
                    }
                    require(identities.isNotEmpty() && identities.toSet().size == identities.size) { "Invalid validated test identities." }
                }
                fun counts(value: JsonObject?): String = if (value == null) "not available / not run" else
                    "${value.get("passed")} passed, ${value.get("failed")} failed, ${value.get("skipped")} skipped, " +
                        "${value.get("incomplete")} incomplete; complete=${value.get("complete") }"
                val details = buildString {
                    append("Validation: $status\n${root.text("reason")}\n\n")
                    append("Baseline: ${root.get("baseline")?.asString ?: selection.report.baseline}\n")
                    append("Baseline check: ${baseline?.get("status")?.asString ?: "not run"}\n")
                    append("Baseline tests: ${counts(baseline?.getAsJsonObject("tests"))}\n")
                    append("Before: ${counts(evidence?.getAsJsonObject("before"))}\n")
                    append("After: ${counts(evidence?.getAsJsonObject("after"))}\n\n")
                    append("Dependency provenance: ${baseline?.get("dependencyProvenance")?.asString ?: "unavailable"}\n")
                    baseline?.get("environmentNote")?.let { append("${it.asString}\n") }
                    append("\nFailure evidence: ${evidence?.get("assessment")?.asString ?: "unavailable"}\n")
                    evidence?.getAsJsonArray("failures")?.forEach { append("$it\n") }
                    append("\nOriginal inputs unchanged: ${root.flag("originalFilesUnchanged") ?: "not established"}\n")
                    append("Disposable workspace removed: ${root.flag("workspaceRemoved") ?: "not established"}\n")
                    append("\nReport and logs: $artifacts\nSaved preview: ${run.previewPath}\n\nStages:\n")
                    root.getAsJsonArray("stages").forEach { item ->
                        val stage = item.asJsonObject
                        append("${stage.get("stage")}: exit=${stage.get("exitCode")}, timeout=${stage.get("timedOut")}\n")
                        append("  Log: ${stage.get("log")?.asString}\n")
                    }
                    append("\nScope: ${root.text("scope")}\n")
                    append("\nEvidence only: no repair approved/applied, no commit or push. Regenerate after input changes.\n")
                    if (!success) append("This result is not successful repair validation.\n")
                }
                return ValidationReport(status, details, output.stdout, artifacts)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid validation report: ${e.message}", e)
            }
        }
    }
}

internal object ValidationWorkflow {
    fun execute(
        selection: SelectedPreview,
        run: PrivateValidationRun,
        progress: (String) -> Unit = {},
        cancelled: () -> Boolean = { false }
    ): ValidationReport {
        try {
            if (cancelled()) throw CancellationException("Validation cancelled.")
            val args = ValidationCommand.prepare(selection, run)
            val output = PreviewProcessRunner.run(
                args, selection.command.workingDirectory, ValidationCommand.overallTimeout,
                outputLimit = 16 * 1024 * 1024, operationName = "Validation",
                onPoll = { progress(currentStage(run)) }, cancelled = cancelled
            )
            run.write("stdout.json", output.stdout)
            run.write("stderr.log", output.stderr)
            if (cancelled()) throw CancellationException("Validation cancelled.")
            val result = ValidationReport.parse(output, selection, run)
            if (cancelled()) throw CancellationException("Validation cancelled.")
            run.write("outcome.txt", "Completed: ${result.status}\nNo repair approved or applied.\n")
            return result
        } catch (e: Exception) {
            val outcome = if (e is CancellationException) "CANCELLED" else "FAILED OR INCOMPLETE"
            run.write("outcome.txt", "$outcome: ${e.message}\nNo validation accepted.\n" +
                "Process-tree termination is best effort. Inspect this directory for leftover workspace/snapshot copies and logs.\n" +
                "Do not infer success from any partial report. No automatic deletion was attempted.\n")
            throw e
        }
    }

    private fun currentStage(run: PrivateValidationRun): String {
        val child = Files.list(run.directory).use { files ->
            files.filter { it.fileName.toString().startsWith("validate-") && Files.isDirectory(it) }.findFirst().orElse(null)
        } ?: return "Validation preflight / preparing private workspace"
        val stages = listOf("sdk", "dependencies_baseline", "baseline", "dependencies", "before", "dependencies_after", "after")
        return "Latest stage: ${stages.lastOrNull { Files.exists(child.resolve("$it.log")) } ?: "snapshot preparation"}"
    }
}

