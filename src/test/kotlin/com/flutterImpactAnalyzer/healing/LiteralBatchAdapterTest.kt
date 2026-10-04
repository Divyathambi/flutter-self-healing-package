package com.flutterImpactAnalyzer.healing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

internal fun literalBatchFixture(project: Path): JsonObject = JsonObject().apply {
    addProperty("schemaVersion", 3); addProperty("project", project.toString()); addProperty("baseline", "a".repeat(40))
    addProperty("filesModified", false); addProperty("testsExecuted", false); add("warnings", JsonArray())
    add("candidateRenames", JsonArray().apply {
        for ((i, key) in listOf("alpha", "beta").withIndex()) add(JsonObject().apply {
            addProperty("kind", "literal_key"); addProperty("file", "lib/$key.dart"); addProperty("line", 1)
            addProperty("oldKey", key); addProperty("newKey", "${key}_new")
            add("references", JsonArray().apply { add(JsonObject().apply {
                addProperty("file", "test/widget_test.dart"); addProperty("line", 1)
                addProperty("offset", 10 + 20 * i); addProperty("length", key.length + 2)
            }) })
        })
    })
    add("preview", JsonObject().apply {
        addProperty("status", "requires_review"); addProperty("reason", "Independent batch")
        addProperty("sourceFile", "lib/alpha.dart"); addProperty("sourceSha256", "b".repeat(64))
        addProperty("editCount", 2); addProperty("patch", "--- before\n+++ after\n")
        add("sources", JsonArray().apply { for (key in listOf("alpha", "beta")) add(JsonObject().apply {
            addProperty("file", "lib/$key.dart"); addProperty("sha256", "b".repeat(64))
        }) })
        add("supportingSha256", JsonObject().apply { for (key in listOf("alpha", "beta")) addProperty("lib/$key.dart", "b".repeat(64)) })
        add("files", JsonArray().apply { add(JsonObject().apply {
            addProperty("file", "test/widget_test.dart"); addProperty("sha256Before", "c".repeat(64)); addProperty("sha256After", "d".repeat(64))
            add("edits", JsonArray().apply { for ((i, key) in listOf("alpha", "beta").withIndex()) add(JsonObject().apply {
                addProperty("offset", 10 + 20 * i); addProperty("length", key.length + 2); addProperty("line", 1)
                addProperty("before", "'$key'"); addProperty("replacement", "'${key}_new'")
            }) })
        }) })
    })
}

internal fun literalBatchFailures(): JsonArray = JsonArray().apply {
    for ((i, key) in listOf("alpha", "beta").withIndex()) add(JsonObject().apply {
        addProperty("assessment", "linked_failure_passed_after"); addProperty("beforeOutcome", "failed"); addProperty("afterOutcome", "passed")
        addProperty("testName", key); addProperty("testFile", "test/widget_test.dart")
        add("proposedEdit", JsonObject().apply {
            addProperty("oldKey", key); addProperty("newKey", "${key}_new")
            addProperty("file", "test/widget_test.dart"); addProperty("offset", 10 + 20 * i)
        })
    })
}

class LiteralBatchAdapterTest {
    private val project = Path.of("/example/project")
    private fun parse(data: JsonObject) = PreviewReport.parse(ProcessResult(0, data.toString(), ""), project)

    @Test fun `accepts a consolidated schema three batch without changing bytes`() {
        val data = literalBatchFixture(project); val result = parse(data)
        assertEquals(data.toString(), result.rawJson); assertEquals(2, result.editCount)
        assertEquals(listOf("test/widget_test.dart"), result.files)
    }

    @Test fun `rejects incomplete non-independent or overlapping batches`() {
        val mutations: List<(JsonObject) -> Unit> = listOf(
            { it.getAsJsonArray("candidateRenames")[1].asJsonObject.addProperty("newKey", "alpha") },
            { it.getAsJsonArray("candidateRenames")[1].asJsonObject.addProperty("oldKey", "alpha") },
            { it.getAsJsonArray("candidateRenames")[1].asJsonObject.addProperty("kind", "shared_key_value") },
            { it.getAsJsonObject("preview").getAsJsonObject("supportingSha256").remove("lib/beta.dart") },
            { it.getAsJsonObject("preview").getAsJsonArray("sources").remove(1) },
            { it.getAsJsonObject("preview").getAsJsonArray("files")[0].asJsonObject.getAsJsonArray("edits")[1].asJsonObject.addProperty("offset", 11) },
            { it.getAsJsonObject("preview").addProperty("editCount", 1) }
        )
        mutations.forEachIndexed { i, mutate ->
            val data = literalBatchFixture(project); mutate(data)
            assertThrows("mutation $i", IllegalArgumentException::class.java) { parse(data) }
        }
    }

    @Test fun `each rename needs its own bound runtime evidence`() {
        val preview = literalBatchFixture(project)
        val evidence = JsonObject().apply { addProperty("batchCoverageComplete", true); add("failures", literalBatchFailures()) }
        LiteralBatchReportContract.validateEvidence(preview, evidence)
        evidence.getAsJsonArray("failures").remove(1)
        assertThrows(IllegalArgumentException::class.java) { LiteralBatchReportContract.validateEvidence(preview, evidence) }
    }
}
