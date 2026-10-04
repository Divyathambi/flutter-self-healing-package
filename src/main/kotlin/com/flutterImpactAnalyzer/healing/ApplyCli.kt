package com.flutterImpactAnalyzer.healing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

internal data class ValidatedProposal(
    val selection: SelectedPreview,
    val validationRun: PrivateValidationRun,
    val validation: ValidationReport
) {
    val eligible: Boolean get() = selection.canValidate && validation.successful

    fun verify() {
        require(eligible) { "Apply requires a successful validation of a reviewable proposal." }
        // Recheck the persisted evidence against the exact preview and displayed result, not a latest report.
        ValidationReport.parse(ProcessResult(0, validation.rawJson, ""), selection, validationRun)
        require(validationRun.previewBytes.contentEquals(selection.report.rawJson.toByteArray(Charsets.UTF_8))) {
            "The validation no longer matches the selected preview."
        }
    }
}

internal class ApplyRequest private constructor(
    val proposal: ValidatedProposal,
    val storage: PrivateValidationRun,
    val root: Path,
    private val validationBytes: ByteArray,
    private val existingRuns: Set<Path>
) {
    val validationPath: Path = storage.directory.resolve("validation.json")
    val validationSha256: String = PrivateValidationRun.sha256(validationBytes)
    val lockPath: Path = root.resolve("apply-${PrivateValidationRun.sha256(proposal.selection.command.project.toString().toByteArray(Charsets.UTF_8))}.lock")

    fun verify() {
        proposal.verify()
        storage.verifyPreview()
        require(storage.previewBytes.contentEquals(proposal.validationRun.previewBytes)) { "Apply preview mismatch." }
        require(readBounded(validationPath, storage.directory, 16 * 1024 * 1024).contentEquals(validationBytes)) {
            "Apply validation input changed; regenerate and revalidate."
        }
    }

    fun newArtifact(path: Path): Boolean = path.parent == root && path.fileName.toString().startsWith("apply-") && path !in existingRuns

    companion object {
        fun create(proposal: ValidatedProposal, approved: Boolean, root: Path = PrivateValidationRun.defaultRoot()): ApplyRequest {
            require(approved) { "Explicit approval of this exact proposal is required." }
            proposal.verify()
            val storage = PrivateValidationRun.createApply(proposal.selection.command.project, proposal.selection.report.rawJson, root)
            val validationBytes = proposal.validation.rawJson.toByteArray(Charsets.UTF_8)
            storage.write("validation.json", proposal.validation.rawJson)
            val existing = Files.list(root).use { files -> files.filter { it.fileName.toString().startsWith("apply-") }.toList().toSet() }
            val request = ApplyRequest(proposal, storage, root, validationBytes, existing)
            storage.write("approval.json", JsonObject().apply {
                addProperty("approved", true)
                addProperty("approvedAt", Instant.now().toString())
                addProperty("project", proposal.selection.command.project.toString())
                addProperty("previewSha256", storage.previewSha256)
                addProperty("validationSha256", request.validationSha256)
                addProperty("artifactRoot", root.toString())
                addProperty("lockPath", request.lockPath.toString())
            }.toString())
            request.verify()
            return request
        }
    }
}

internal fun readBounded(path: Path, parent: Path, limit: Int): ByteArray {
    require(path.parent == parent && path.toRealPath() == path && Files.isRegularFile(path)) { "Unexpected or symlinked report path: $path" }
    require(Files.size(path) <= limit) { "Report exceeds size limit." }
    return Files.readAllBytes(path)
}

internal object ApplyCommand {
    val overallTimeout: Duration = Duration.ofMinutes(10)
    fun prepare(request: ApplyRequest): List<String> {
        request.verify()
        val selected = request.proposal.selection
        val flutter = selected.sdk.resolve("bin/flutter")
        require(Files.isRegularFile(flutter) && Files.isExecutable(flutter)) { "Flutter executable is unavailable." }
        return selected.command.arguments.take(3) + listOf(
            "apply", "--project", selected.command.project.toString(),
            "--report", request.storage.previewPath.toString(), "--validation", request.validationPath.toString(),
            "--flutter", flutter.toString(), "--timeout", "180", "--artifacts-dir", request.root.toString(), "--approve", "--json"
        )
    }
}

internal data class ApplyReport(val status: String, val details: String, val rawJson: String, val directory: Path) {
    val accepted: Boolean get() = status in setOf("applied_and_tests_passed", "already_applied")

    companion object {
        private val statuses = setOf("applied_and_tests_passed", "already_applied", "approval_required", "blocked", "application_failed", "rolled_back", "rollback_conflict")

        fun parse(output: ProcessResult, request: ApplyRequest): ApplyReport {
            require(output.exitCode in setOf(0, 2)) { "Apply command exited ${output.exitCode}; outcome is not established.\n${output.stderr.take(8000)}" }
            try {
                request.verify()
                val data = JsonParser.parseString(output.stdout).asJsonObject
                fun JsonObject.text(name: String): String {
                    val value = get(name)
                    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Missing string: $name" }
                    return value.asString
                }
                fun JsonObject.flag(name: String): Boolean {
                    val value = get(name)
                    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "Missing boolean: $name" }
                    return value.asBoolean
                }
                fun JsonObject.count(name: String): Int {
                    val value = get(name)
                    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
                    return value.asBigDecimal.intValueExact().also { require(it >= 0) }
                }
                require(data.get("schemaVersion")?.toString() == "1") { "Unsupported apply report schema." }
                val status = data.text("status")
                require(status in statuses) { "Apply is incomplete or has an unknown status: $status" }
                require(data.text("finishedAt").isNotBlank()) { "Incomplete apply journal." }
                val accepted = status in setOf("applied_and_tests_passed", "already_applied")
                require(output.exitCode == if (accepted) 0 else 2) { "Apply exit/status contradiction." }
                val selection = request.proposal.selection
                require(Path.of(data.text("project")) == selection.command.project) { "Apply belongs to another project." }
                require(data.flag("approved") && !data.flag("gitCommitted")) { "Apply approval/commit flags are inconsistent." }
                val artifacts = Path.of(data.text("artifacts"))
                require(request.newArtifact(artifacts) && artifacts.toRealPath() == artifacts) { "Apply did not produce a new report in the shared artifact root." }
                val persisted = JsonParser.parseString(String(readBounded(artifacts.resolve("result.json"), artifacts, 16 * 1024 * 1024), Charsets.UTF_8))
                require(persisted == data) { "Returned apply result differs from the persisted report." }
                for ((name, expected) in listOf("preview.json" to request.storage.previewBytes, "validation.json" to request.proposal.validation.rawJson.toByteArray(Charsets.UTF_8))) {
                    val path = artifacts.resolve(name)
                    if (accepted || Files.exists(path)) require(readBounded(path, artifacts, 16 * 1024 * 1024).contentEquals(expected)) { "Apply copied a different input report." }
                }
                if (accepted || data.has("previewSha256")) require(data.text("previewSha256") == request.storage.previewSha256) { "Wrong preview digest." }
                if (accepted || data.has("validationSha256")) require(data.text("validationSha256") == request.validationSha256) { "Wrong validation digest." }
                if (accepted || data.has("baseline")) require(data.text("baseline") == selection.report.baseline) { "Wrong baseline." }
                val conflicts = data.getAsJsonArray("rollbackConflicts").map { it.asString }
                val modified = data.flag("filesModified")
                val testsExecuted = data.flag("testsExecuted")
                if (accepted) require(conflicts.isEmpty()) { "Accepted apply cannot contain rollback conflicts." }
                if (status == "already_applied") require(!modified && !testsExecuted) { "Idempotent apply cannot claim new writes or tests." }
                if (status == "rolled_back") require(!modified && conflicts.isEmpty()) { "Rollback report is contradictory." }
                if (status == "rollback_conflict") require(modified && conflicts.isNotEmpty()) { "Conflict report is missing preserved conflicts." }
                if (status in setOf("blocked", "approval_required")) require(!modified && !testsExecuted && conflicts.isEmpty()) { "Blocked apply claims side effects." }
                val tests = data.getAsJsonObject("tests")
                val command = data.getAsJsonObject("testCommand")
                if (status == "applied_and_tests_passed") {
                    require(modified && testsExecuted && tests != null && command != null) { "Missing post-apply test evidence." }
                    val identities = JsonParser.parseString(request.proposal.validation.rawJson).asJsonObject.getAsJsonArray("validatedTestIdentities")
                    require(tests.flag("complete") && tests.flag("runnerSuccess") && tests.count("passed") == identities.size() &&
                        tests.count("failed") == 0 && tests.count("skipped") == 0 && tests.count("incomplete") == 0) { "Incomplete or failing post-apply tests." }
                    require(command.count("exitCode") == 0 && !command.flag("timedOut") &&
                        command.get("startError")?.isJsonNull == true && command.text("executable") == selection.sdk.resolve("bin/flutter").toString()) { "Invalid post-apply command." }
                    val args = command.getAsJsonArray("arguments").map { it.asString }
                    require(args.firstOrNull() == "test" && "--no-pub" in args && "pub" !in args && "upgrade" !in args) { "Expected direct tests with --no-pub." }
                    val files = data.getAsJsonArray("files").map { it.asJsonObject.text("file") }
                    require(files.toSet() == selection.report.files.toSet() && files.size == files.toSet().size) { "Unexpected applied file set." }
                }
                val details = buildString {
                    append("Apply: $status\n${data.text("reason")}\n\n")
                    data.get("failureReason")?.let { append("Failure reason: ${it.asString}\n\n") }
                    append("Project: ${selection.command.project}\nFinder edits in approved proposal: ${selection.report.editCount}\n")
                    append("Files modified at completion: $modified\nTests executed: $testsExecuted\n")
                    if (tests != null) append("Tests: ${tests.get("passed")} passed, ${tests.get("failed")} failed, ${tests.get("skipped")} skipped, ${tests.get("incomplete")} incomplete; complete=${tests.get("complete")}\n")
                    if (status == "already_applied") append("No new edits or test run; the exact repair was already present.\n")
                    append("\nReport, logs, journal and backups: $artifacts\nRequest: ${request.storage.directory}\n")
                    command?.get("log")?.let { append("Test log: ${it.asString}\n") }
                    data.getAsJsonArray("files")?.forEach { append("Backup: ${it.asJsonObject.get("backup")?.asString}\n") }
                    append("\nRollback conflicts: ${if (conflicts.isEmpty()) "none reported" else conflicts.joinToString()}\n")
                    if (status == "rollback_conflict") append("Unexpected content was preserved. Review backups manually; do not overwrite independent edits.\n")
                    data.get("lockCleanupRequired")?.let { append("LOCK CLEANUP REQUIRED: ${it.asString}. Inspect the journal and running processes before removal.\n") }
                    append("\nNo dependency setup, Git staging, commits, or pushes were requested.\n")
                    append("Selected tests only, not full-suite verification. Editors do not honor the tool's cooperative lock.\n")
                }
                return ApplyReport(status, details, output.stdout, artifacts)
            } catch (e: Exception) {
                throw IllegalArgumentException("Apply outcome could not be verified: ${e.message}", e)
            }
        }
    }
}

internal object ApplyWorkflow {
    fun execute(request: ApplyRequest, progress: (String) -> Unit = {}): ApplyReport {
        try {
            val args = ApplyCommand.prepare(request)
            request.storage.write("execution.txt", "Starting guarded apply. Do not interrupt. Shared project lock: ${request.lockPath}\n")
            val output = PreviewProcessRunner.run(
                args, request.proposal.selection.command.workingDirectory, ApplyCommand.overallTimeout,
                outputLimit = 16 * 1024 * 1024, operationName = "Apply",
                onPoll = { progress("Guarded apply: checking inputs, installing edits or running --no-pub tests. Do not interrupt.") }
                // No UI cancellation once a mutation-capable CLI has launched: let its rollback/finally finish.
            )
            request.storage.write("stdout.json", output.stdout)
            request.storage.write("stderr.log", output.stderr)
            val report = ApplyReport.parse(output, request)
            request.storage.write("outcome.txt", "Completed: ${report.status}\nReport: ${report.directory}\n")
            return report
        } catch (e: Exception) {
            request.storage.write("outcome.txt", "OUTCOME NOT VERIFIED: ${e.message}\n" +
                "Do not assume files are unchanged or rolled back. Inspect journals/backups under ${request.root}.\n" +
                "Project lock: ${request.lockPath}\nDo not delete locks or overwrite unexpected edits automatically.\n")
            throw e
        }
    }
}

