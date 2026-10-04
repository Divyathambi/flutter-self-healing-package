package com.flutterImpactAnalyzer.healing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class SharedKeyAdapterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val base: Path get() = temporary.root.toPath().toRealPath()
    private val commit = "a".repeat(40)
    private val testFile = "test/button_test.dart"
    private fun array(vararg items: JsonObject) = JsonArray().apply { items.forEach { add(it) } }
    private fun ids() = JsonArray().apply { add("$testFile: finds submit") }

    private fun preview(symbol: Boolean = true, status: String = "requires_review"): JsonObject {
        val project = Files.createDirectories(base.resolve("project"))
        val before = if (symbol) "submitButton" else "'submit_button'"
        val replacement = if (symbol) "confirmButton" else "'confirm_button'"
        val reference = JsonObject().apply {
            addProperty("file", testFile); addProperty("offset", 40); addProperty("length", before.length)
            addProperty("line", 7); addProperty("column", 25)
            addProperty("editKind", if (symbol) "shared_member" else "key_literal")
        }
        return JsonObject().apply {
            addProperty("schemaVersion", 2); addProperty("project", project.toString()); addProperty("baseline", commit)
            addProperty("filesModified", false); addProperty("testsExecuted", false); add("warnings", JsonArray())
            add("candidateRenames", array(JsonObject().apply {
                addProperty("kind", if (symbol) "shared_key_symbol" else "shared_key_value")
                addProperty("file", "lib/keys.dart"); addProperty("owner", "AppKeys")
                addProperty("oldName", "submitButton"); addProperty("newName", if (symbol) "confirmButton" else "submitButton")
                addProperty("oldKey", if (symbol) "submitButton" else "submit_button")
                addProperty("newKey", if (symbol) "confirmButton" else "confirm_button")
                add("references", if (status == "no_changes") JsonArray() else array(reference))
            }))
            add("preview", JsonObject().apply {
                addProperty("status", status); addProperty("reason", "Finder-only proposal")
                addProperty("sourceFile", "lib/keys.dart"); addProperty("sourceSha256", "b".repeat(64))
                addProperty("editCount", if (status == "requires_review") 1 else 0)
                addProperty("patch", if (status == "requires_review") "--- a/$testFile\n+++ b/$testFile\n-$before\n+$replacement\n" else "")
                add("files", if (status != "requires_review") JsonArray() else array(JsonObject().apply {
                    addProperty("file", testFile); addProperty("sha256Before", "c".repeat(64)); addProperty("sha256After", "d".repeat(64))
                    add("edits", array(JsonObject().apply {
                        addProperty("offset", 40); addProperty("length", before.length); addProperty("line", 7)
                        addProperty("before", before); addProperty("replacement", replacement)
                    }))
                }))
            })
        }
    }

    private fun selection(json: JsonObject = preview()): SelectedPreview {
        val project = Path.of(json.get("project").asString)
        val sdk = Files.createDirectories(base.resolve("sdk/bin")).parent
        Files.writeString(sdk.resolve("bin/flutter"), "fixture")
        sdk.resolve("bin/flutter").toFile().setExecutable(true)
        val exit = if (json.getAsJsonObject("preview").get("status").asString == "blocked") 2 else 0
        return SelectedPreview(PreviewCommand(project, base, listOf("/fake/dart", "--packages=/fake/config", "/fake/healer.dart", "preview")),
            PreviewReport.parse(ProcessResult(exit, json.toString(), ""), project), sdk)
    }

    private fun run(selected: SelectedPreview) = PrivateValidationRun.create(selected.command.project, selected.report.rawJson, base.resolve("runs"))

    private fun report(selected: SelectedPreview, run: PrivateValidationRun, compile: Boolean = true): JsonObject {
        val artifacts = Files.createTempDirectory(run.directory, "validate-")
        Files.write(artifacts.resolve("preview.json"), run.previewBytes)
        fun counts(passed: Int, failed: Int) = JsonObject().apply {
            addProperty("passed", passed); addProperty("failed", failed); addProperty("skipped", 0); addProperty("incomplete", 0)
            addProperty("complete", true); addProperty("runnerSuccess", failed == 0)
        }
        return JsonObject().apply {
            addProperty("schemaVersion", 1); addProperty("bindingVersion", 1)
            addProperty("status", if (compile) "before_compile_failed_after_passed" else "before_failed_after_passed")
            addProperty("reason", "Verified in disposable copies"); addProperty("scope", "Selected tests")
            addProperty("project", selected.command.project.toString()); addProperty("baseline", commit)
            addProperty("artifacts", artifacts.toString()); addProperty("previewSha256", run.previewSha256)
            addProperty("inputPreviewReport", run.previewPath.toString()); addProperty("previewReport", artifacts.resolve("preview.json").toString())
            addProperty("originalFilesUnchanged", true); addProperty("workspaceRemoved", true); addProperty("testsExecuted", true)
            addProperty("baselineCoverageMatches", true); addProperty("baselineCoverageComparison", if (compile) "baseline_after" else "baseline_before")
            add("validatedTestIdentities", ids())
            add("baselineCheck", JsonObject().apply { addProperty("status", "passed"); add("tests", counts(1, 0)) })
            add("stages", JsonArray().apply {
                for (name in listOf("baseline", "before", "after")) add(JsonObject().apply {
                    addProperty("stage", name); addProperty("executable", selected.sdk.resolve("bin/flutter").toString())
                    addProperty("exitCode", if (name == "before") 1 else 0); addProperty("timedOut", false); addProperty("logTruncated", false)
                })
            })
            add("failureEvidence", JsonObject().apply {
                addProperty("assessment", if (compile) "linked_compile_errors_passed_after" else "linked_failures_passed_after")
                addProperty("mode", "shared_symbol_compile_failure"); addProperty("inventoryComparison", "baseline_after")
                addProperty("baselineCoverageMatches", true); add("issues", JsonArray()); add("failures", JsonArray())
                add("baselineTestIdentities", ids()); add("afterTestIdentities", ids())
                add("before", counts(0, 1)); add("after", counts(1, 0))
                add("compileErrors", array(JsonObject().apply {
                    addProperty("file", testFile); addProperty("offset", 40); addProperty("length", 12); addProperty("line", 7); addProperty("column", 25)
                    addProperty("oldName", "submitButton"); addProperty("newName", "confirmButton")
                    addProperty("category", "missing_static_member"); addProperty("linkKind", "compiler_error_at_approved_member_identifier")
                }))
            })
        }
    }

    private fun parse(data: JsonObject, selected: SelectedPreview, run: PrivateValidationRun, exit: Int = 0): ValidationReport {
        Files.writeString(Path.of(data.get("artifacts").asString).resolve("result.json"), data.toString())
        return ValidationReport.parse(ProcessResult(exit, data.toString(), ""), selected, run)
    }

    @Test fun `shared value preview and runtime validation preserve exact bytes`() {
        val json = preview(symbol = false); val selected = selection(json); val run = run(selected)
        assertEquals(json.toString(), selected.report.rawJson)
        assertTrue(ValidationCommand.prepare(selected, run).contains("validate"))
        assertTrue(parse(report(selected, run, compile = false), selected, run).successful)
        assertArrayEquals(json.toString().toByteArray(), run.previewBytes)
    }

    @Test fun `compile success enables guarded apply only after explicit approval`() {
        val selected = selection(); val run = run(selected)
        val validation = parse(report(selected, run), selected, run)
        val proposal = ValidatedProposal(selected, run, validation)
        assertTrue(proposal.eligible); proposal.verify()
        assertThrows(IllegalArgumentException::class.java) { ApplyRequest.create(proposal, false, base.resolve("apply-runs")) }
        val request = ApplyRequest.create(proposal, true, base.resolve("apply-runs"))
        val args = ApplyCommand.prepare(request)
        assertTrue(args.contains("apply")); assertTrue(args.contains("--approve")); assertFalse(args.contains("pub"))
        assertArrayEquals(run.previewBytes, request.storage.previewBytes)
        val summary = ValidationSummary.render(validation, selected.report)
        assertTrue(summary.contains("could not compile")); assertTrue(summary.contains("$testFile:7"))
        assertTrue(summary.contains("submitButton → confirmButton")); assertFalse(summary.contains("No failed test names"))
    }

    @Test fun `shared no changes and blocked reports never enable validation`() {
        for (status in listOf("no_changes", "blocked")) {
            val selected = selection(preview(status = status))
            assertFalse(selected.canValidate)
            assertThrows(IllegalArgumentException::class.java) { ValidationCommand.prepare(selected, run(selected)) }
        }
    }

    @Test fun `invalid shared kinds coverage and replacement are rejected`() {
        val mutations: List<(JsonObject) -> Unit> = listOf(
            { it.addProperty("schemaVersion", 3) },
            { it.getAsJsonArray("candidateRenames")[0].asJsonObject.addProperty("kind", "helper_rewrite") },
            { it.getAsJsonArray("candidateRenames")[0].asJsonObject.add("references", JsonArray()) },
            { it.getAsJsonObject("preview").addProperty("editCount", 2) },
            { it.getAsJsonObject("preview").getAsJsonArray("files")[0].asJsonObject.getAsJsonArray("edits")[0].asJsonObject.addProperty("replacement", "arbitraryCode()") },
            { it.getAsJsonObject("preview").getAsJsonArray("files")[0].asJsonObject.addProperty("file", "test/../lib/keys.dart") },
            { it.addProperty("filesModified", true) }
        )
        mutations.forEachIndexed { index, mutate ->
            val data = preview(); mutate(data)
            assertThrows("mutation $index", IllegalArgumentException::class.java) { selection(data) }
        }
    }

    @Test fun `compile and runtime validation modes cannot be exchanged`() {
        for (symbol in listOf(true, false)) {
            val selected = selection(preview(symbol)); val run = run(selected)
            assertThrows(IllegalArgumentException::class.java) { parse(report(selected, run, compile = !symbol), selected, run) }
        }
    }

    @Test fun `incomplete unbound or contradictory compile reports are rejected`() {
        val selected = selection(); val run = run(selected)
        val mutations: List<(JsonObject) -> Unit> = listOf(
            { it.addProperty("workspaceRemoved", false) },
            { it.addProperty("originalFilesUnchanged", false) },
            { it.addProperty("previewSha256", "f".repeat(64)) },
            { it.addProperty("baselineCoverageComparison", "baseline_before") },
            { it.getAsJsonObject("failureEvidence").getAsJsonArray("issues").add("truncated") },
            { it.getAsJsonObject("failureEvidence").add("compileErrors", JsonArray()) },
            { it.getAsJsonObject("failureEvidence").getAsJsonArray("compileErrors")[0].asJsonObject.addProperty("column", 26) },
            { it.getAsJsonObject("failureEvidence").getAsJsonArray("compileErrors")[0].asJsonObject.addProperty("newName", "other") },
            { it.getAsJsonObject("failureEvidence").getAsJsonArray("afterTestIdentities").add("extra test") },
            { it.getAsJsonObject("failureEvidence").getAsJsonObject("after").addProperty("skipped", 1) },
            { it.getAsJsonObject("failureEvidence").getAsJsonObject("after").addProperty("passed", "1") },
            { it.getAsJsonObject("failureEvidence").getAsJsonObject("before").addProperty("complete", false) },
            { it.getAsJsonObject("baselineCheck").getAsJsonObject("tests").addProperty("runnerSuccess", false) },
            { it.getAsJsonArray("stages")[1].asJsonObject.addProperty("exitCode", 0) },
            { it.getAsJsonArray("stages")[1].asJsonObject.addProperty("logTruncated", true) },
            { it.getAsJsonArray("stages")[2].asJsonObject.addProperty("executable", "/other/flutter") },
            { it.getAsJsonArray("stages").add(it.getAsJsonArray("stages")[2].deepCopy()) }
        )
        mutations.forEachIndexed { index, mutate ->
            val data = report(selected, run); mutate(data)
            assertThrows("mutation $index", IllegalArgumentException::class.java) { parse(data, selected, run) }
        }
        assertThrows(IllegalArgumentException::class.java) { parse(report(selected, run), selected, run, 2) }
    }

    @Test fun `compile apply eligibility rechecks persisted validation`() {
        val selected = selection(); val run = run(selected)
        val data = report(selected, run); val validation = parse(data, selected, run)
        Files.writeString(validation.directory.resolve("result.json"), "{}")
        assertThrows(IllegalArgumentException::class.java) { ValidatedProposal(selected, run, validation).verify() }
    }
}
