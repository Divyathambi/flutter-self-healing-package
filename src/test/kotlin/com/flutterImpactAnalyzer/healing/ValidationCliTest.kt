package com.flutterImpactAnalyzer.healing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException

class ValidationCliTest {
    @get:Rule val temporary = TemporaryFolder()
    private val base: Path get() = temporary.root.toPath().toRealPath()
    private val commit = "a".repeat(40)

    private fun selection(status: String = "requires_review"): SelectedPreview {
        val project = Files.createDirectories(base.resolve("project"))
        val sdk = Files.createDirectories(base.resolve("flutter/bin")).parent
        val flutter = sdk.resolve("bin/flutter")
        Files.writeString(flutter, "fixture")
        flutter.toFile().setExecutable(true)
        val json = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("project", project.toString())
            addProperty("baseline", commit)
            addProperty("filesModified", false)
            addProperty("testsExecuted", false)
            add("warnings", JsonArray())
            add("preview", JsonObject().apply {
                addProperty("status", status)
                addProperty("reason", "Finder-only proposal")
                addProperty("editCount", if (status == "requires_review") 1 else 0)
                addProperty("patch", if (status == "requires_review") "--- old\n+++ new\n" else "")
                add("files", JsonArray().apply {
                    if (status == "requires_review") add(JsonObject().apply { addProperty("file", "test/widget_test.dart") })
                })
            })
        }
        val report = PreviewReport.parse(ProcessResult(if (status == "blocked") 2 else 0, "$json\n", ""), project)
        return SelectedPreview(PreviewCommand(project, base, listOf("/fake/dart", "--packages=/fake/packages.json", "/fake/healer.dart", "preview")), report, sdk)
    }

    private fun run(selection: SelectedPreview) = PrivateValidationRun.create(selection.command.project, selection.report.rawJson, base.resolve("artifacts"))

    @Test fun `only reviewable previews are eligible`() {
        assertTrue(selection().canValidate)
        for (status in listOf("blocked", "no_changes")) {
            val selected = selection(status)
            assertFalse(selected.canValidate)
            assertThrows(IllegalArgumentException::class.java) { ValidationCommand.prepare(selected, run(selected)) }
        }
    }

    @Test fun `stores exact preview bytes privately outside the project`() {
        val selected = selection()
        val run = run(selected)
        assertArrayEquals(selected.report.rawJson.toByteArray(), Files.readAllBytes(run.previewPath))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(run.directory))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(run.previewPath))
        assertFalse(run.directory.startsWith(selected.command.project))
        assertNotEquals(run.directory, run(selected).directory)
    }

    @Test fun `inside project and symlinked artifact roots are rejected`() {
        val selected = selection()
        assertThrows(IllegalArgumentException::class.java) {
            PrivateValidationRun.create(selected.command.project, selected.report.rawJson, selected.command.project.resolve("reports"))
        }
        val real = Files.createDirectory(base.resolve("real"))
        val link = Files.createSymbolicLink(base.resolve("link"), real)
        assertThrows(IllegalArgumentException::class.java) { PrivateValidationRun.create(selected.command.project, selected.report.rawJson, link.resolve("reports")) }
        assertFalse(Files.exists(real.resolve("reports")))
    }

    @Test fun `public existing artifact root is not silently reused or chmodded`() {
        val selected = selection()
        val root = Files.createDirectory(base.resolve("public"))
        val permissions = PosixFilePermissions.fromString("rwxr-xr-x")
        Files.setPosixFilePermissions(root, permissions)
        assertThrows(IllegalArgumentException::class.java) { PrivateValidationRun.create(selected.command.project, selected.report.rawJson, root) }
        assertEquals(permissions, Files.getPosixFilePermissions(root))
    }

    @Test fun `validation command binds exact paths with no apply or shell`() {
        val selected = selection()
        val run = run(selected)
        val args = ValidationCommand.prepare(selected, run)
        assertEquals(selected.command.arguments.take(3), args.take(3))
        assertEquals("validate", args[3])
        assertEquals(run.previewPath.toString(), args[args.indexOf("--report") + 1])
        assertEquals(run.directory.toString(), args[args.indexOf("--artifacts-dir") + 1])
        assertEquals(selected.sdk.resolve("bin/flutter").toString(), args[args.indexOf("--flutter") + 1])
        assertEquals("180", args[args.indexOf("--timeout") + 1])
        assertFalse(args.any { it in setOf("apply", "--approve", "pub", "-c") })
        assertEquals(25L, ValidationCommand.overallTimeout.toMinutes())
    }

    @Test fun `saved preview mutation refuses execution`() {
        val selected = selection()
        val run = run(selected)
        Files.writeString(run.previewPath, "{}")
        assertThrows(IllegalArgumentException::class.java) { ValidationCommand.prepare(selected, run) }
    }

    private fun report(selected: SelectedPreview, run: PrivateValidationRun, status: String = "before_failed_after_passed"): JsonObject {
        val artifacts = Files.createTempDirectory(run.directory, "validate-")
        Files.write(artifacts.resolve("preview.json"), run.previewBytes)
        fun counts(passed: Int, failed: Int) = JsonObject().apply {
            addProperty("passed", passed); addProperty("failed", failed); addProperty("skipped", 0)
            addProperty("incomplete", 0); addProperty("complete", true)
        }
        return JsonObject().apply {
            addProperty("schemaVersion", 1); addProperty("bindingVersion", 1)
            addProperty("status", status); addProperty("reason", "Evidence, not automatic approval")
            addProperty("project", selected.command.project.toString()); addProperty("baseline", commit)
            addProperty("artifacts", artifacts.toString()); addProperty("previewSha256", run.previewSha256)
            addProperty("inputPreviewReport", run.previewPath.toString())
            addProperty("previewReport", artifacts.resolve("preview.json").toString())
            addProperty("scope", "Selected tests only")
            addProperty("originalFilesUnchanged", true); addProperty("workspaceRemoved", true)
            addProperty("testsExecuted", true); addProperty("baselineCoverageMatches", true)
            add("stages", JsonArray())
            add("validatedTestIdentities", JsonArray().apply { add("test/widget_test.dart: example") })
            add("baselineCheck", JsonObject().apply {
                addProperty("status", "passed"); add("tests", counts(1, 0))
                addProperty("dependencyProvenance", "current_locks_reused_for_identical_manifests")
                addProperty("environmentNote", "Not a recovered historical dependency environment.")
            })
            add("failureEvidence", JsonObject().apply {
                addProperty("assessment", "linked_failures_passed_after")
                add("before", counts(0, 1)); add("after", counts(1, 0))
            })
        }
    }

    private fun parse(data: JsonObject, selected: SelectedPreview, run: PrivateValidationRun, exit: Int = 0): ValidationReport {
        Files.writeString(Path.of(data.get("artifacts").asString).resolve("result.json"), data.toString())
        return ValidationReport.parse(ProcessResult(exit, data.toString(), ""), selected, run)
    }

    @Test fun `successful validation displays evidence provenance and artifact path`() {
        val selected = selection(); val run = run(selected)
        val result = parse(report(selected, run), selected, run)
        assertTrue(result.successful)
        assertTrue(result.details.contains("Before: 0 passed, 1 failed"))
        assertTrue(result.details.contains("After: 1 passed, 0 failed"))
        assertTrue(result.details.contains("current_locks_reused_for_identical_manifests"))
        assertTrue(result.details.contains("no repair approved/applied"))
    }

    @Test fun `early blocked validation needs no test summary and is not success`() {
        val selected = selection(); val run = run(selected)
        val data = report(selected, run, "blocked")
        listOf("baseline", "baselineCheck", "failureEvidence", "validatedTestIdentities", "baselineCoverageMatches").forEach { data.remove(it) }
        data.add("originalFilesUnchanged", com.google.gson.JsonNull.INSTANCE)
        data.addProperty("testsExecuted", false)
        data.addProperty("reason", "Production source changed after preview; regenerate the report.")
        val result = parse(data, selected, run, 2)
        assertFalse(result.successful)
        assertTrue(result.details.contains("not available / not run"))
        assertTrue(result.details.contains("not established"))
    }

    @Test fun `non successful statuses never become success`() {
        val selected = selection()
        for (status in listOf("repair_failed", "input_changed", "baseline_failed", "requires_investigation", "failure_not_reproduced", "cleanup_failed", "execution_failed")) {
            val run = run(selected)
            assertFalse(parse(report(selected, run, status), selected, run, 2).successful)
        }
    }

    @Test fun `exit contradictions and missing integrity evidence are rejected`() {
        val selected = selection(); val run = run(selected)
        assertThrows(IllegalArgumentException::class.java) { parse(report(selected, run), selected, run, 2) }
        assertThrows(IllegalArgumentException::class.java) { parse(report(selected, run, "blocked"), selected, run, 0) }
        for (field in listOf("originalFilesUnchanged", "workspaceRemoved", "baselineCoverageMatches", "testsExecuted")) {
            val data = report(selected, run).apply { addProperty(field, false) }
            assertThrows(IllegalArgumentException::class.java) { parse(data, selected, run) }
        }
    }

    @Test fun `wrong preview digest and project are rejected`() {
        val selected = selection(); val run = run(selected)
        val digest = report(selected, run).apply { addProperty("previewSha256", "b".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { parse(digest, selected, run) }
        val project = report(selected, run).apply { addProperty("project", base.toString()) }
        assertThrows(IllegalArgumentException::class.java) { parse(project, selected, run) }
    }

    @Test fun `mismatched saved report and out of request artifact paths are rejected`() {
        val selected = selection(); val run = run(selected)
        val data = report(selected, run)
        Files.writeString(Path.of(data.get("artifacts").asString).resolve("result.json"), "{}")
        assertThrows(IllegalArgumentException::class.java) { ValidationReport.parse(ProcessResult(0, data.toString(), ""), selected, run) }
        data.addProperty("artifacts", base.toString())
        assertThrows(IllegalArgumentException::class.java) { ValidationReport.parse(ProcessResult(0, data.toString(), ""), selected, run) }
    }

    @Test fun `malformed report and missing executable result are not success`() {
        val selected = selection(); val run = run(selected)
        assertThrows(IllegalArgumentException::class.java) { ValidationReport.parse(ProcessResult(0, "{}", ""), selected, run) }
        assertThrows(IllegalArgumentException::class.java) { ValidationReport.parse(ProcessResult(65, "", "Git unavailable"), selected, run) }
    }

    @Test fun `cancelled validation preserves exact preview and records incomplete outcome`() {
        val selected = selection(); val run = run(selected)
        assertThrows(CancellationException::class.java) { ValidationWorkflow.execute(selected, run, cancelled = { true }) }
        run.verifyPreview()
        assertTrue(Files.readString(run.directory.resolve("outcome.txt")).contains("CANCELLED"))
        assertTrue(Files.readString(run.directory.resolve("outcome.txt")).contains("No validation accepted"))
    }

    @Test fun `batch validation binds every rename before guarded apply eligibility`() {
        val initial = selection()
        val preview = literalBatchFixture(initial.command.project)
        val selected = initial.copy(report = PreviewReport.parse(ProcessResult(0, preview.toString(), ""), initial.command.project))
        val run = run(selected)
        val data = report(selected, run)
        data.getAsJsonObject("baselineCheck").getAsJsonObject("tests").addProperty("passed", 2)
        val evidence = data.getAsJsonObject("failureEvidence")
        evidence.getAsJsonObject("before").addProperty("failed", 2)
        evidence.getAsJsonObject("after").addProperty("passed", 2)
        evidence.addProperty("batchCoverageComplete", true)
        evidence.add("failures", literalBatchFailures())
        data.add("validatedTestIdentities", JsonArray().apply { add("test/widget_test.dart: alpha"); add("test/widget_test.dart: beta") })
        val validation = parse(data, selected, run)
        val proposal = ValidatedProposal(selected, run, validation)
        assertTrue(proposal.eligible)
        proposal.verify()
        evidence.getAsJsonArray("failures").remove(1)
        assertThrows(IllegalArgumentException::class.java) { parse(data, selected, run) }
        assertThrows(IllegalArgumentException::class.java) { proposal.verify() }
    }

    @Test fun `optional disposable real validation smoke test`() {
        val target = System.getenv("HEALER_VALIDATION_SMOKE_PROJECT")
        val healer = System.getenv("HEALER_SMOKE_ROOT")
        val sdk = System.getenv("HEALER_SMOKE_FLUTTER")
        assumeTrue("Opt-in disposable checkout test only", target != null && healer != null && sdk != null)
        val command = PreviewCommand.prepare(target!!, healer!!, sdk!!)
        require(command.project.parent.fileName.toString().startsWith("plugin-validation-smoke.")) {
            "The validation smoke test requires its own disposable clone, not a developer checkout."
        }
        val preview = PreviewReport.parse(PreviewProcessRunner.run(command.arguments, command.workingDirectory), command.project)
        assertEquals("requires_review", preview.status)
        val selected = SelectedPreview(command, preview, Path.of(sdk).toRealPath())
        val run = PrivateValidationRun.create(command.project, preview.rawJson)
        val validated = ValidationWorkflow.execute(selected, run)
        assertTrue(validated.details, validated.successful)
        assertEquals(selected.report.rawJson, Files.readString(run.previewPath))
        println("Positive validation report: ${validated.directory}")
        val sourceName = com.google.gson.JsonParser.parseString(preview.rawJson).asJsonObject
            .getAsJsonObject("preview").get("sourceFile").asString
        val source = command.project.resolve(sourceName).normalize()
        require(source.startsWith(command.project))
        val original = Files.readAllBytes(source)
        try {
            Files.writeString(source, String(original, Charsets.UTF_8) + "\n// disposable stale-input check\n")
            val staleRun = PrivateValidationRun.create(command.project, preview.rawJson)
            val stale = ValidationWorkflow.execute(selected, staleRun)
            assertEquals("blocked", stale.status)
            assertTrue(stale.details.contains("Production source changed after preview"))
            println("Stale validation report: ${stale.directory}")
        } finally {
            Files.write(source, original)
        }
    }
}

