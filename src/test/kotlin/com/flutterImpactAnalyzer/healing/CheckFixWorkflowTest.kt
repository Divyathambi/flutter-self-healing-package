package com.flutterImpactAnalyzer.healing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

class CheckFixWorkflowTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Fixture(status: String = "requires_review") {
        val project = temporary.newFolder().toPath().toRealPath()
        val command = PreviewCommand(project, project, listOf("unused-test-executable"))
        val artifactRoot = temporary.root.toPath().toRealPath().resolve("runs")
        var previewCalls = 0
        var validationCalls = 0
        var artifact: PrivateValidationRun? = null
        val events = mutableListOf<String>()
        val json = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("project", project.toString())
            addProperty("baseline", "a".repeat(40))
            addProperty("filesModified", false)
            addProperty("testsExecuted", false)
            add("warnings", JsonArray())
            add("preview", JsonObject().apply {
                addProperty("status", status)
                addProperty("reason", "Test proposal")
                addProperty("editCount", if (status == "requires_review") 1 else 0)
                addProperty("patch", if (status == "requires_review") "--- old\n+++ new\n" else "")
                add("files", JsonArray().apply {
                    if (status == "requires_review") add(JsonObject().apply { addProperty("file", "test/widget_test.dart") })
                })
            })
        }.toString() + "\n"
        val output = ProcessResult(if (status == "blocked") 2 else 0, json, "preview diagnostics")

        fun execute(
            cancelled: () -> Boolean = { false },
            checkInputs: (Path) -> Unit = {},
            preview: () -> ProcessResult = { output },
            onPreview: (SelectedPreview) -> Unit = {},
            onRun: (PrivateValidationRun) -> Unit = {},
            validation: (SelectedPreview, PrivateValidationRun) -> ValidationReport = { _, run -> report(run) }
        ) = CheckFixWorkflow.execute(
            command, project,
            cancelled = cancelled,
            checkInputs = checkInputs,
            onPreview = { events.add("proposal"); onPreview(it) },
            onValidationRun = onRun,
            runPreview = { _, _ -> previewCalls++; events.add("preview"); preview() },
            createRun = { selection ->
                events.add("artifact")
                PrivateValidationRun.create(project, selection.report.rawJson, artifactRoot).also { artifact = it }
            },
            runValidation = { selection, run, _, _ ->
                validationCalls++
                events.add("validation")
                validation(selection, run)
            }
        )

        // The real validator/parser has its own evidence-integrity tests; these fixtures test sequencing only.
        fun report(run: PrivateValidationRun, status: String = "before_failed_after_passed") =
            ValidationReport(status, "Test validation result", "{}", run.directory)
    }

    @Test fun `reviewable preview is automatically validated exactly once with unchanged bytes`() {
        val fixture = Fixture()
        var displayed: SelectedPreview? = null
        val result = fixture.execute(onPreview = { displayed = it }, validation = { selection, run ->
            assertSame(displayed, selection)
            assertArrayEquals(fixture.json.toByteArray(), Files.readAllBytes(run.previewPath))
            run.verifyPreview()
            fixture.report(run)
        })
        assertEquals(listOf("preview", "proposal", "artifact", "validation"), fixture.events)
        assertEquals(1, fixture.previewCalls)
        assertEquals(1, fixture.validationCalls)
        assertSame(displayed, result.proposal!!.selection)
        assertSame(result.validation, result.proposal!!.validation)
        assertEquals("preview diagnostics", result.diagnostics)
        assertEquals("--- old\n+++ new\n", result.selection.report.patch)
    }

    @Test fun `blocked and no-change previews never create artifacts or run validation`() {
        for (status in listOf("blocked", "no_changes")) {
            val fixture = Fixture(status)
            val result = fixture.execute()
            assertEquals(status, result.selection.report.status)
            assertEquals(0, fixture.validationCalls)
            assertNull(fixture.artifact)
            assertNull(result.validation)
            assertNull(result.proposal)
        }
    }

    @Test fun `malformed preview stops before validation`() {
        val fixture = Fixture()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.execute(preview = { ProcessResult(0, "not JSON", "") })
        }
        assertEquals(0, fixture.validationCalls)
        assertNull(fixture.artifact)
    }

    @Test fun `cancel before starting runs neither stage`() {
        val fixture = Fixture()
        assertThrows(CancellationException::class.java) { fixture.execute(cancelled = { true }) }
        assertEquals(0, fixture.previewCalls)
        assertEquals(0, fixture.validationCalls)
    }

    @Test fun `unsaved inputs before preview prevent execution`() {
        val fixture = Fixture()
        assertThrows(IllegalStateException::class.java) {
            fixture.execute(checkInputs = { error("Unsaved edits") })
        }
        assertEquals(0, fixture.previewCalls)
        assertNull(fixture.artifact)
    }

    @Test fun `unsaved inputs appearing during preview stop automatic validation`() {
        val fixture = Fixture()
        var unsaved = false
        assertThrows(IllegalStateException::class.java) {
            fixture.execute(checkInputs = { check(!unsaved) }, preview = { unsaved = true; fixture.output })
        }
        assertEquals(1, fixture.previewCalls)
        assertEquals(0, fixture.validationCalls)
        assertNull(fixture.artifact)
    }

    @Test fun `cancel at preview handoff prevents artifact creation and validation`() {
        val fixture = Fixture()
        var stopped = false
        assertThrows(CancellationException::class.java) {
            fixture.execute(cancelled = { stopped }, onPreview = { stopped = true })
        }
        assertEquals(0, fixture.validationCalls)
        assertNull(fixture.artifact)
    }

    @Test fun `cancel after artifact creation preserves an incomplete journal without testing`() {
        val fixture = Fixture()
        var stopped = false
        assertThrows(CancellationException::class.java) {
            fixture.execute(cancelled = { stopped }, onRun = { stopped = true })
        }
        assertEquals(0, fixture.validationCalls)
        assertTrue(Files.readString(fixture.artifact!!.directory.resolve("check-outcome.txt")).contains("CHECK INCOMPLETE"))
    }

    @Test fun `cancel after successful validation cannot return an eligible proposal`() {
        val fixture = Fixture()
        var stopped = false
        assertThrows(CancellationException::class.java) {
            fixture.execute(cancelled = { stopped }, validation = { _, run -> stopped = true; fixture.report(run) })
        }
        assertEquals(1, fixture.validationCalls)
        assertTrue(Files.exists(fixture.artifact!!.directory.resolve("check-outcome.txt")))
    }

    @Test fun `failed and stale validation reports never produce an apply proposal`() {
        for (status in listOf("blocked", "repair_failed", "input_changed", "cleanup_failed", "failure_not_reproduced")) {
            val fixture = Fixture()
            val result = fixture.execute(validation = { _, run -> fixture.report(run, status) })
            assertEquals(status, result.validation!!.status)
            assertNull(result.proposal)
            assertEquals(1, fixture.validationCalls)
        }
    }

    @Test fun `validation exception retains preview and recovery journal`() {
        val fixture = Fixture()
        val error = assertThrows(IllegalStateException::class.java) {
            fixture.execute(validation = { _, _ -> error("CLI could not complete") })
        }
        assertEquals("CLI could not complete", error.message)
        assertEquals(fixture.json, Files.readString(fixture.artifact!!.previewPath))
        assertTrue(Files.readString(fixture.artifact!!.directory.resolve("check-outcome.txt")).contains("CLI could not complete"))
    }

    @Test fun `unsaved inputs after validation prevent an accepted result`() {
        val fixture = Fixture()
        var unsaved = false
        assertThrows(IllegalStateException::class.java) {
            fixture.execute(checkInputs = { check(!unsaved) }, validation = { _, run -> unsaved = true; fixture.report(run) })
        }
        assertTrue(Files.exists(fixture.artifact!!.directory.resolve("check-outcome.txt")))
    }
}

