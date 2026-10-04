package com.flutterImpactAnalyzer.healing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class ValidationSummaryTest {
    private val directory = Path.of("/private/reports/validate-example")
    private val preview = PreviewReport(
        "requires_review", "Proposed only", "a".repeat(40), emptyList(), listOf("test/back_button_test.dart"),
        3, "--- old\n+++ new\n-oldKey\n+newKey\n", "unchanged preview bytes"
    )

    private fun failure(name: String, line: Int) = JsonObject().apply {
        addProperty("testName", name)
        addProperty("testFile", "test/back_button_test.dart")
        addProperty("beforeOutcome", "failed")
        addProperty("afterOutcome", "passed")
        addProperty("assessment", "linked_failure_passed_after")
        add("failureLocation", JsonObject().apply {
            addProperty("file", "test/back_button_test.dart")
            addProperty("line", line)
        })
        add("proposedEdit", JsonObject().apply {
            addProperty("oldKey", "oldKey")
            addProperty("newKey", "newKey")
        })
    }

    private fun data() = JsonObject().apply {
        addProperty("reason", "Verbose successful evidence and provenance explanation")
        addProperty("baseline", "a".repeat(40))
        addProperty("originalFilesUnchanged", true)
        addProperty("workspaceRemoved", true)
        addProperty("testsExecuted", true)
        add("failureEvidence", JsonObject().apply {
            addProperty("assessment", "linked_failures_passed_after")
            add("failures", JsonArray().apply {
                add(failure("renders back button", 47))
                add(failure("handles tap", 80))
            })
        })
        add("stages", JsonArray())
    }

    private fun report(data: JsonObject, status: String = "before_failed_after_passed") =
        ValidationReport(status, "Full technical report must not be rendered", data.toString(), directory)

    @Test fun `successful summary shows failed test names locations and proposed keys only`() {
        val summary = ValidationSummary.render(report(data()), preview)
        assertTrue(summary.contains("1. renders back button"))
        assertTrue(summary.contains("2. handles tap"))
        assertTrue(summary.contains("test/back_button_test.dart:47"))
        assertTrue(summary.contains("test/back_button_test.dart:80"))
        assertTrue(summary.contains("Proposed key: oldKey → newKey"))
        assertTrue(summary.contains("Passed with proposed edit"))
        assertTrue(summary.contains("Proposed edits: 3"))
        assertTrue(summary.contains("No repair applied yet."))
        for (hidden in listOf("Baseline", "provenance", "Stages", "linked_failures_passed_after", "Full technical", "a".repeat(40), directory.toString())) {
            assertFalse("Unexpected technical detail: $hidden", summary.contains(hidden))
        }
    }

    @Test fun `failed check shows its reason and actual after outcomes`() {
        val data = data()
        data.addProperty("reason", "A selected test still fails.")
        val failures = data.getAsJsonObject("failureEvidence").getAsJsonArray("failures")
        failures[0].asJsonObject.addProperty("afterOutcome", "failed")
        failures[1].asJsonObject.addProperty("afterOutcome", "incomplete")
        val summary = ValidationSummary.render(report(data, "repair_failed"), preview)
        assertTrue(summary.contains("Check not successful."))
        assertTrue(summary.contains("A selected test still fails."))
        assertTrue(summary.contains("Still failing"))
        assertTrue(summary.contains("Did not complete"))
        assertTrue(summary.contains(directory.toString()))
        assertFalse(summary.contains("Passed with proposed edit"))
    }

    @Test fun `missing failure records do not imply zero failures or success`() {
        val data = JsonObject().apply { addProperty("reason", "Baseline could not be prepared.") }
        val summary = ValidationSummary.render(report(data, "baseline_setup_failed"), preview)
        assertTrue(summary.contains("Baseline could not be prepared."))
        assertTrue(summary.contains("No failed test names were recorded."))
        assertFalse(summary.contains("Passed with proposed edit"))
        assertFalse(summary.contains("null"))
    }

    @Test fun `missing edit and skipped result are not displayed as repaired`() {
        val data = data()
        val failures = data.getAsJsonObject("failureEvidence").getAsJsonArray("failures")
        failures.forEach {
            it.asJsonObject.remove("proposedEdit")
            it.asJsonObject.addProperty("afterOutcome", "skipped")
        }
        val summary = ValidationSummary.render(report(data, "requires_investigation"), preview)
        assertTrue(summary.contains("No linked finder edit recorded."))
        assertTrue(summary.contains("Skipped — not verified"))
        assertFalse(summary.contains("Passed with proposed edit"))
    }

    @Test fun `passing but unattributed failure displays the precise approval blocker`() {
        val data = data().apply {
            addProperty("baselineCoverageMatches", true)
            getAsJsonObject("failureEvidence").apply {
                addProperty("assessment", "requires_investigation")
                addProperty("batchCoverageComplete", false)
                getAsJsonArray("failures")[1].asJsonObject.apply {
                    addProperty("testName", "Biometric tool tip")
                    addProperty("assessment", "requires_investigation")
                    add("reasons", JsonArray().apply { add("Failure is not a recognized missing-widget diagnostic.") })
                }
                add("issues", JsonArray().apply {
                    add("Every batch rename needs an attributed before failure that passes after repair.")
                })
            }
        }
        val report = report(data, "requires_investigation")
        val summary = ValidationSummary.render(report, preview)
        assertTrue(summary.contains("Passed with proposed edit"))
        val biometric = summary.substringAfter("2. Biometric tool tip")
        assertTrue(biometric.contains("Passed afterward — repair attribution not verified"))
        assertTrue(biometric.contains("Reason: Failure is not a recognized missing-widget diagnostic."))
        assertFalse(biometric.contains("Passed with proposed edit"))
        assertTrue(summary.contains("Why approval is blocked"))
        assertTrue(summary.contains("Every batch rename needs an attributed before failure"))
        assertFalse(summary.contains("inventories did not match"))
        assertFalse(report.successful)
        assertEquals(data.toString(), report.rawJson)
    }

    @Test fun `baseline mismatch is shown separately from passing attributed tests`() {
        val data = data().apply { addProperty("baselineCoverageMatches", false) }
        val summary = ValidationSummary.render(report(data, "requires_investigation"), preview)
        assertTrue(summary.contains("Passed with proposed edit"))
        assertTrue(summary.contains("baseline/current test inventories did not match or could not be verified"))
    }

    @Test fun `missing attribution or an inconsistent reason never implies verified repair`() {
        for (field in listOf("assessment", "beforeOutcome", "proposedEdit", "reasons")) {
            val data = data()
            data.getAsJsonObject("failureEvidence").getAsJsonArray("failures").forEach {
                if (field == "reasons") it.asJsonObject.add("reasons", JsonArray().apply { add("Attribution incomplete.") })
                else it.asJsonObject.remove(field)
            }
            val summary = ValidationSummary.render(report(data, "requires_investigation"), preview)
            assertTrue(summary.contains("Passed afterward — repair attribution not verified"))
            assertFalse(summary.contains("Passed with proposed edit"))
        }
    }

    @Test fun `changed inputs and failed cleanup retain actionable warnings`() {
        val data = data().apply {
            addProperty("originalFilesUnchanged", false)
            addProperty("workspaceRemoved", false)
        }
        val summary = ValidationSummary.render(report(data, "input_changed"), preview)
        assertTrue(summary.contains("target inputs changed"))
        assertTrue(summary.contains("cleanup did not complete"))
        assertTrue(summary.contains(directory.toString()))
    }

    @Test fun `unknown preservation and cleanup failure status remain visible`() {
        val data = data().apply {
            remove("originalFilesUnchanged")
            remove("workspaceRemoved")
        }
        val summary = ValidationSummary.render(report(data, "cleanup_failed"), preview)
        assertTrue(summary.contains("input preservation was not verified"))
        assertTrue(summary.contains("cleanup did not complete"))
    }

    @Test fun `malformed display data has a concise fallback instead of a raw report dump`() {
        val summary = ValidationSummary.render(ValidationReport("blocked", "raw technical details", "not json", directory), preview)
        assertTrue(summary.contains("could not be displayed"))
        assertTrue(summary.contains(directory.toString()))
        assertFalse(summary.contains("raw technical details"))
    }

    @Test fun `rendering never changes raw evidence patch or eligibility`() {
        val report = report(data())
        val raw = report.rawJson
        val patch = preview.patch
        ValidationSummary.render(report, preview)
        assertEquals(raw, report.rawJson)
        assertEquals(patch, preview.patch)
        assertTrue(report.successful)
    }

    @Test fun `all batch edits remain visible when only one unrelated key has linked failures`() {
        val project = Path.of("/example/project")
        val proposal = literalBatchFixture(project)
        val files = proposal.getAsJsonObject("preview").getAsJsonArray("files")
        val firstFile = files[0].asJsonObject
        val secondFile = firstFile.deepCopy().apply {
            addProperty("file", "test/other_test.dart")
            getAsJsonArray("edits").remove(0)
        }
        firstFile.getAsJsonArray("edits").remove(1)
        files.add(secondFile)
        proposal.getAsJsonArray("candidateRenames")[1].asJsonObject
            .getAsJsonArray("references")[0].asJsonObject.addProperty("file", "test/other_test.dart")
        val preview = PreviewReport.parse(ProcessResult(0, proposal.toString(), ""), project)
        val evidence = data().apply {
            addProperty("reason", "Selected tests did not pass after the proposed edits.")
            getAsJsonObject("failureEvidence").apply {
                addProperty("batchCoverageComplete", false)
                add("failures", literalBatchFailures().apply { remove(1) })
            }
        }
        val report = report(evidence, "repair_failed")
        val summary = ValidationSummary.render(report, preview)
        val inventory = summary.substringAfter("All proposed finder edits (not validation results)")

        assertTrue(summary.contains("Proposed key: alpha → alpha_new"))
        assertFalse(summary.contains("Proposed key: beta → beta_new"))
        assertTrue(inventory.contains("test/widget_test.dart\n   Line 1: 'alpha' → 'alpha_new'"))
        assertTrue(inventory.contains("test/other_test.dart\n   Line 1: 'beta' → 'beta_new'"))
        assertTrue(inventory.contains("listing an edit does not mean it passed validation"))
        assertTrue(summary.contains("Check not successful."))
        assertTrue(summary.contains(directory.toString()))
        assertFalse(report.successful)
        assertEquals(proposal.toString(), preview.rawJson)
        assertEquals(proposal.getAsJsonObject("preview").get("patch").asString, preview.patch)
        assertEquals(evidence.toString(), report.rawJson)
    }

    @Test fun `new after failure is separate from repaired original failures`() {
        val data = data().apply {
            addProperty("reason", "Selected tests did not pass after the proposed edits.")
            getAsJsonObject("failureEvidence").add("afterFailures", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("testName", "Edit Text Field Alert - errorMsg is not empty")
                    addProperty("testFile", "test/alert_test.dart")
                    addProperty("beforeOutcome", "passed")
                    addProperty("afterOutcome", "failed")
                    addProperty("diagnostic", "EXCEPTION CAUGHT BY FLUTTER TEST FRAMEWORK\n" +
                        "Found 0 widgets with key [<'textFormFieldKeyButton'>]: []\n" +
                        "When the exception was thrown, this was the stack:\n#0 internal frame")
                    add("failureLocation", JsonObject().apply {
                        addProperty("file", "test/alert_test.dart")
                        addProperty("line", 99)
                    })
                })
            })
        }
        val report = report(data, "repair_failed")
        val summary = ValidationSummary.render(report, preview)
        assertTrue(summary.contains("Passed with proposed edit"))
        val after = summary.substringAfter("Failures after the proposed edits")
        assertTrue(after.contains("Edit Text Field Alert - errorMsg is not empty"))
        assertTrue(after.contains("test/alert_test.dart:99"))
        assertTrue(after.contains("New failure — passed before, failed after proposed edits"))
        assertTrue(after.contains("Found 0 widgets with key [<'textFormFieldKeyButton'>]"))
        assertFalse(after.contains("Passed with proposed edit"))
        assertFalse(after.contains("Proposed key:"))
        assertFalse(after.contains("#0 internal frame"))
        assertFalse(report.successful)
        assertEquals(data.toString(), report.rawJson)
    }

    @Test fun `after failures without original failures retain truthful prior outcomes`() {
        for ((prior, label) in mapOf("failed" to "Still failing", "skipped" to "skipped before",
            "missing_or_ambiguous" to "prior outcome not verified")) {
            val data = data().apply {
                getAsJsonObject("failureEvidence").apply {
                    add("failures", JsonArray())
                    add("afterFailures", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("testName", "after failure")
                            addProperty("testFile", "test/alert_test.dart")
                            addProperty("beforeOutcome", prior)
                        })
                    })
                }
            }
            val summary = ValidationSummary.render(report(data, "repair_failed"), preview)
            assertTrue(summary.contains(label))
            assertTrue(summary.contains("after failure"))
            assertTrue(summary.contains("See after.log"))
            assertFalse(summary.contains("New failure"))
            assertFalse(summary.contains("No failed test names were recorded"))
            assertFalse(summary.contains("Passed with proposed edit"))
        }
    }

    @Test fun `older reports warn when after failure details were not recorded`() {
        val data = data().apply {
            getAsJsonObject("failureEvidence").add("after", JsonObject().apply { addProperty("failed", 1) })
        }
        val summary = ValidationSummary.render(report(data, "repair_failed"), preview)
        assertTrue(summary.contains("no after-failure details. See after.log."))
    }

    @Test fun `all edits in one file are shown even without failure evidence`() {
        val project = Path.of("/example/project")
        val proposal = literalBatchFixture(project)
        val preview = PreviewReport.parse(ProcessResult(0, proposal.toString(), ""), project)
        val report = report(JsonObject(), "baseline_setup_failed")
        val summary = ValidationSummary.render(report, preview)

        assertTrue(summary.contains("No failed test names were recorded."))
        assertTrue(summary.contains("'alpha' → 'alpha_new'"))
        assertTrue(summary.contains("'beta' → 'beta_new'"))
        assertFalse(summary.contains("Passed with proposed edit"))
        assertFalse(report.successful)
    }
}
