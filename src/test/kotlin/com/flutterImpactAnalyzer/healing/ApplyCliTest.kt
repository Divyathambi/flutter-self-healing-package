package com.flutterImpactAnalyzer.healing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ApplyCliTest {
    @get:Rule val temporary = TemporaryFolder()
    private val base: Path get() = temporary.root.toPath().toRealPath()
    private val baseline = "a".repeat(40)

    private fun counts(passed: Int, failed: Int) = JsonObject().apply {
        addProperty("complete", true); addProperty("runnerSuccess", failed == 0)
        addProperty("passed", passed); addProperty("failed", failed)
        addProperty("skipped", 0); addProperty("incomplete", 0)
    }

    private fun proposal(): ValidatedProposal {
        val project = Files.createDirectories(base.resolve("project"))
        val sdk = Files.createDirectories(base.resolve("sdk/bin")).parent
        Files.writeString(sdk.resolve("bin/flutter"), "fixture")
        sdk.resolve("bin/flutter").toFile().setExecutable(true)
        val rawPreview = JsonObject().apply {
            addProperty("schemaVersion", 1); addProperty("project", project.toString()); addProperty("baseline", baseline)
            addProperty("filesModified", false); addProperty("testsExecuted", false); add("warnings", JsonArray())
            add("preview", JsonObject().apply {
                addProperty("status", "requires_review"); addProperty("reason", "Review required")
                addProperty("patch", "--- before\n+++ after\n"); addProperty("editCount", 1)
                add("files", JsonArray().apply { add(JsonObject().apply { addProperty("file", "test/widget_test.dart") }) })
            })
        }.toString() + "\n"
        val preview = PreviewReport.parse(ProcessResult(0, rawPreview, ""), project)
        val selection = SelectedPreview(PreviewCommand(project, base, listOf("/nonexistent/dart", "--packages=/packages.json", "/healer.dart", "preview")), preview, sdk)
        val run = PrivateValidationRun.create(project, rawPreview, base.resolve("artifacts"))
        val artifacts = Files.createTempDirectory(run.directory, "validate-")
        Files.write(artifacts.resolve("preview.json"), run.previewBytes)
        val validation = JsonObject().apply {
            addProperty("schemaVersion", 1); addProperty("bindingVersion", 1)
            addProperty("status", "before_failed_after_passed"); addProperty("reason", "Evidence only")
            addProperty("project", project.toString()); addProperty("baseline", baseline)
            addProperty("artifacts", artifacts.toString()); addProperty("previewSha256", run.previewSha256)
            addProperty("inputPreviewReport", run.previewPath.toString()); addProperty("previewReport", artifacts.resolve("preview.json").toString())
            addProperty("originalFilesUnchanged", true); addProperty("workspaceRemoved", true)
            addProperty("testsExecuted", true); addProperty("baselineCoverageMatches", true)
            addProperty("scope", "Selected test only"); add("stages", JsonArray())
            add("validatedTestIdentities", JsonArray().apply { add("test/widget_test.dart: example") })
            add("baselineCheck", JsonObject().apply { addProperty("status", "passed"); add("tests", counts(1, 0)) })
            add("failureEvidence", JsonObject().apply {
                addProperty("assessment", "linked_failures_passed_after"); add("before", counts(0, 1)); add("after", counts(1, 0))
            })
        }
        Files.writeString(artifacts.resolve("result.json"), validation.toString())
        val report = ValidationReport.parse(ProcessResult(0, validation.toString(), ""), selection, run)
        return ValidatedProposal(selection, run, report)
    }

    private fun request(proposal: ValidatedProposal = proposal()) = ApplyRequest.create(proposal, true, base.resolve("artifacts"))

    @Test fun `explicit approval and successful matching validation are required`() {
        val proposal = proposal()
        assertThrows(IllegalArgumentException::class.java) { ApplyRequest.create(proposal, false, base.resolve("artifacts")) }
        val failed = proposal.copy(validation = proposal.validation.copy(status = "repair_failed"))
        assertFalse(failed.eligible)
        assertThrows(IllegalArgumentException::class.java) { request(failed) }
        val wrongPreview = proposal.copy(selection = proposal.selection.copy(report = proposal.selection.report.copy(rawJson = "{}")))
        assertThrows(IllegalArgumentException::class.java) { request(wrongPreview) }
    }

    @Test fun `copies exact evidence and uses shared artifact root and lock across requests`() {
        val proposal = proposal(); val a = request(proposal); val b = request(proposal)
        assertNotEquals(a.storage.directory, b.storage.directory)
        assertEquals(a.root, b.root); assertEquals(a.lockPath, b.lockPath)
        assertEquals(proposal.selection.report.rawJson, Files.readString(a.storage.previewPath))
        assertEquals(proposal.validation.rawJson, Files.readString(a.validationPath))
        val args = ApplyCommand.prepare(a)
        assertEquals("apply", args[3]); assertTrue("--approve" in args)
        assertEquals(a.root.toString(), args[args.indexOf("--artifacts-dir") + 1])
        assertNotEquals(a.storage.directory.toString(), args[args.indexOf("--artifacts-dir") + 1])
        assertFalse(args.any { it in setOf("pub", "upgrade", "-c", "commit", "push") })
    }

    @Test fun `changed source evidence on disk or copied validation is rejected`() {
        val proposal = proposal(); val request = request(proposal)
        Files.writeString(request.validationPath, "{}")
        assertThrows(IllegalArgumentException::class.java) { ApplyCommand.prepare(request) }
        Files.writeString(proposal.validation.directory.resolve("result.json"), "{}")
        assertThrows(IllegalArgumentException::class.java) { request(proposal) }
    }

    private fun result(request: ApplyRequest, status: String): JsonObject {
        val artifacts = Files.createTempDirectory(request.root, "apply-")
        Files.write(artifacts.resolve("preview.json"), request.storage.previewBytes)
        Files.copy(request.validationPath, artifacts.resolve("validation.json"))
        return JsonObject().apply {
            addProperty("schemaVersion", 1); addProperty("status", status); addProperty("reason", "Recorded CLI outcome")
            addProperty("project", request.proposal.selection.command.project.toString()); addProperty("approved", true)
            addProperty("artifacts", artifacts.toString()); addProperty("finishedAt", "2026-09-16T00:00:00Z")
            addProperty("gitCommitted", false); addProperty("filesModified", status in setOf("applied_and_tests_passed", "rollback_conflict"))
            addProperty("testsExecuted", status in setOf("applied_and_tests_passed", "rolled_back", "rollback_conflict"))
            addProperty("previewSha256", request.storage.previewSha256); addProperty("validationSha256", request.validationSha256)
            addProperty("baseline", baseline)
            add("rollbackConflicts", JsonArray().apply { if (status == "rollback_conflict") add("test/widget_test.dart") })
            add("files", JsonArray().apply { add(JsonObject().apply {
                addProperty("file", "test/widget_test.dart"); addProperty("backup", artifacts.resolve("backups/test/widget_test.dart").toString())
            }) })
            if (status in setOf("rolled_back", "rollback_conflict")) addProperty("failureReason", "Post-apply verification failed")
            if (status == "applied_and_tests_passed") {
                add("tests", counts(1, 0))
                add("testCommand", JsonObject().apply {
                    addProperty("executable", request.proposal.selection.sdk.resolve("bin/flutter").toString())
                    addProperty("exitCode", 0); addProperty("timedOut", false); add("startError", com.google.gson.JsonNull.INSTANCE)
                    addProperty("log", artifacts.resolve("after.log").toString())
                    add("arguments", JsonArray().apply { add("test"); add("--no-pub"); add("test/widget_test.dart") })
                })
            }
        }
    }

    private fun parse(data: JsonObject, request: ApplyRequest, exit: Int = if (data.get("status").asString in setOf("applied_and_tests_passed", "already_applied")) 0 else 2): ApplyReport {
        Files.writeString(Path.of(data.get("artifacts").asString).resolve("result.json"), data.toString())
        return ApplyReport.parse(ProcessResult(exit, data.toString(), ""), request)
    }

    @Test fun `applied and already applied have distinct test and write claims`() {
        val request = request()
        val applied = parse(result(request, "applied_and_tests_passed"), request)
        assertTrue(applied.accepted); assertTrue(applied.details.contains("1 passed"))
        val already = parse(result(request, "already_applied"), request)
        assertTrue(already.accepted); assertTrue(already.details.contains("No new edits or test run"))
    }

    @Test fun `rollback and conflict statuses preserve failure and backup details`() {
        val request = request()
        val rolled = parse(result(request, "rolled_back"), request)
        assertFalse(rolled.accepted); assertTrue(rolled.details.contains("Post-apply verification failed"))
        val conflict = parse(result(request, "rollback_conflict"), request)
        assertFalse(conflict.accepted); assertTrue(conflict.details.contains("test/widget_test.dart"))
        assertTrue(conflict.details.contains("Unexpected content was preserved"))
    }

    @Test fun `early blocked reports need no evidence hashes or copied inputs`() {
        val request = request(); val data = result(request, "blocked")
        listOf("previewSha256", "validationSha256", "baseline", "files").forEach { data.remove(it) }
        val artifacts = Path.of(data.get("artifacts").asString)
        Files.delete(artifacts.resolve("preview.json")); Files.delete(artifacts.resolve("validation.json"))
        assertFalse(parse(data, request).accepted)
    }

    @Test fun `incomplete journals and exit contradictions are rejected`() {
        val request = request()
        for (state in listOf("prepared", "applying", "testing", "unknown")) {
            assertThrows(IllegalArgumentException::class.java) { parse(result(request, state), request) }
        }
        val missingEnd = result(request, "applied_and_tests_passed").apply { remove("finishedAt") }
        assertThrows(IllegalArgumentException::class.java) { parse(missingEnd, request) }
        assertThrows(IllegalArgumentException::class.java) { parse(result(request, "applied_and_tests_passed"), request, 2) }
        assertThrows(IllegalArgumentException::class.java) { parse(result(request, "blocked"), request, 0) }
    }

    @Test fun `success rejects incomplete tests conflicting writes or wrong binding`() {
        val request = request()
        val failed = result(request, "applied_and_tests_passed")
        failed.getAsJsonObject("tests").addProperty("failed", 1)
        assertThrows(IllegalArgumentException::class.java) { parse(failed, request) }
        val wrong = result(request, "applied_and_tests_passed").apply { addProperty("validationSha256", "b".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { parse(wrong, request) }
        val already = result(request, "already_applied").apply { addProperty("filesModified", true) }
        assertThrows(IllegalArgumentException::class.java) { parse(already, request) }
    }

    @Test fun `reports from another request or changed persisted reports are rejected`() {
        val request = request(); val data = result(request, "already_applied")
        Files.writeString(Path.of(data.get("artifacts").asString).resolve("result.json"), "{}")
        assertThrows(IllegalArgumentException::class.java) { ApplyReport.parse(ProcessResult(0, data.toString(), ""), request) }
        val another = request(request.proposal) // The previous result predates this request.
        assertThrows(IllegalArgumentException::class.java) { parse(data, another) }
        data.addProperty("project", base.toString())
        assertThrows(IllegalArgumentException::class.java) { parse(data, request) }
    }

    @Test fun `startup failure records unknown outcome without deleting backups or locks`() {
        val request = request()
        Files.writeString(request.lockPath, "independent lock")
        assertThrows(Exception::class.java) { ApplyWorkflow.execute(request) }
        assertEquals("independent lock", Files.readString(request.lockPath))
        assertTrue(Files.readString(request.storage.directory.resolve("outcome.txt")).contains("Do not assume files are unchanged or rolled back"))
    }

    @Test fun `optional disposable real apply and idempotence smoke test`() {
        val target = System.getenv("HEALER_APPLY_SMOKE_PROJECT")
        val healer = System.getenv("HEALER_SMOKE_ROOT")
        val sdk = System.getenv("HEALER_SMOKE_FLUTTER")
        assumeTrue("Explicit disposable-checkout mutation test", target != null && healer != null && sdk != null)
        val command = PreviewCommand.prepare(target!!, healer!!, sdk!!)
        require(command.project.parent.fileName.toString().startsWith("plugin-apply-smoke.")) { "Never apply to a developer checkout in this test." }
        require(Files.isRegularFile(command.project.resolve(".dart_tool/package_config.json"))) { "Prepare disposable dependencies before validation." }
        val preview = PreviewReport.parse(PreviewProcessRunner.run(command.arguments, command.workingDirectory), command.project)
        assertEquals("requires_review", preview.status)
        val selection = SelectedPreview(command, preview, Path.of(sdk).toRealPath())
        val validationRun = PrivateValidationRun.create(command.project, preview.rawJson)
        val validation = ValidationWorkflow.execute(selection, validationRun)
        assertTrue(validation.details, validation.successful)
        val proposal = ValidatedProposal(selection, validationRun, validation)
        val raw = JsonParser.parseString(preview.rawJson).asJsonObject.getAsJsonObject("preview")
        val source = command.project.resolve(raw.get("sourceFile").asString).normalize()
        require(source.startsWith(command.project))
        val originalSource = Files.readAllBytes(source)
        try {
            Files.writeString(source, String(originalSource, Charsets.UTF_8) + "\n// disposable stale apply check\n")
            val stale = ApplyWorkflow.execute(ApplyRequest.create(proposal, true))
            assertEquals("blocked", stale.status)
            assertTrue(stale.details.contains("Production source changed after preview"))
            println("Stale apply report: ${stale.directory}")
        } finally { Files.write(source, originalSource) }
        val before = preview.files.associateWith { Files.readAllBytes(command.project.resolve(it)) }
        val applied = ApplyWorkflow.execute(ApplyRequest.create(proposal, true))
        assertEquals(applied.details, "applied_and_tests_passed", applied.status)
        println("Successful apply report: ${applied.directory}")
        val already = ApplyWorkflow.execute(ApplyRequest.create(proposal, true))
        assertEquals("already_applied", already.status)
        println("Idempotent apply report: ${already.directory}")
        // Return only this disposable fixture to its unrepaired state for a subsequent interactive UI check.
        for (item in raw.getAsJsonArray("files")) {
            val file = item.asJsonObject
            val name = file.get("file").asString
            val path = command.project.resolve(name).normalize()
            require(path.startsWith(command.project))
            assertEquals(file.get("sha256After").asString, PrivateValidationRun.sha256(Files.readAllBytes(path)))
            Files.write(path, before.getValue(name))
        }
    }
}
