package com.flutterImpactAnalyzer.healing

import com.google.gson.JsonObject

/** Structural checks and evidence binding; the CLI regenerates every actual edit. */
internal object LiteralBatchReportContract {
    fun validatePreview(root: JsonObject) {
        val candidates = root.objects("candidateRenames")
        require(candidates.size >= 2 && candidates.all { it.text("kind") == "literal_key" }) { "Unsupported literal batch." }
        val old = candidates.map { it.text("oldKey") }
        val new = candidates.map { it.text("newKey") }
        require(old.toSet().size == old.size && new.toSet().size == new.size && old.none { it in new } &&
            (old + new).none { it.isEmpty() }) { "Batch mappings are not independent." }
        val preview = root.getAsJsonObject("preview")
        require(preview.text("status") == "requires_review") { "Unsupported batch preview state." }
        val sources = preview.objects("sources")
        require(sources.map { it.text("file") }.toSet().size == sources.size &&
            sources.map { it.text("file") }.toSet() == candidates.map { it.text("file") }.toSet()) { "Incomplete batch sources." }
        val support = preview.getAsJsonObject("supportingSha256")
        sources.forEach {
            require(path(it.text("file"), "lib/") && hash(it.text("sha256")) &&
                support.get(it.text("file"))?.asString == it.text("sha256")) { "Invalid batch source binding." }
        }
        require(sources.any { it.text("file") == preview.text("sourceFile") && it.text("sha256") == preview.text("sourceSha256") })
        val references = candidates.flatMap { candidate ->
            candidate.objects("references").also { require(it.isNotEmpty()) { "Uncovered batch rename." } }
        }
        val files = preview.objects("files")
        require(files.map { it.text("file") }.toSet().size == files.size) { "Duplicate batch test files." }
        val edits = files.flatMap { file ->
            require(path(file.text("file"), "test/") && file.text("file").endsWith("_test.dart") &&
                hash(file.text("sha256Before")) && hash(file.text("sha256After"))) { "Invalid batch test file." }
            var end = -1L
            file.objects("edits").also { require(it.isNotEmpty()) }.map { edit ->
                val offset = edit.int("offset"); val length = edit.int("length")
                require(offset >= 0 && offset.toLong() >= end && length > 0 && edit.int("line") > 0 &&
                    edit.text("before").length == length && edit.text("replacement").isNotEmpty()) { "Invalid batch edit range." }
                end = offset.toLong() + length
                file.text("file") to edit
            }
        }
        require(edits.size == references.size && edits.size == preview.int("editCount")) { "Batch edit coverage mismatch." }
        val keys = references.map { it.text("file") to it.int("offset") }
        require(keys.toSet().size == keys.size) { "Overlapping batch references." }
        references.forEach { ref ->
            val edit = edits.single { (file, edit) -> file == ref.text("file") && edit.int("offset") == ref.int("offset") }.second
            require(edit.int("length") == ref.int("length") && edit.int("line") == ref.int("line")) { "Batch reference mismatch." }
        }
    }

    fun validateEvidence(preview: JsonObject, evidence: JsonObject) {
        val covered = evidence.get("batchCoverageComplete")
        require(covered != null && covered.isJsonPrimitive && covered.asJsonPrimitive.isBoolean && covered.asBoolean) {
            "Batch validation is missing complete rename coverage."
        }
        val failures = evidence.objects("failures")
        preview.objects("candidateRenames").forEach { candidate ->
            require(failures.any { failure ->
                val edit = failure.getAsJsonObject("proposedEdit")
                failure.text("assessment") == "linked_failure_passed_after" && failure.text("beforeOutcome") == "failed" &&
                    failure.text("afterOutcome") == "passed" && edit != null &&
                    edit.text("oldKey") == candidate.text("oldKey") && edit.text("newKey") == candidate.text("newKey") &&
                    candidate.objects("references").any { ref -> ref.text("file") == edit.text("file") && ref.int("offset") == edit.int("offset") }
            }) { "A batch rename has no linked failing-then-passing test." }
        }
    }

    private fun path(value: String, prefix: String) = value.startsWith(prefix) && value.endsWith(".dart") &&
        '\\' !in value && value.split('/').none { it.isEmpty() || it == "." || it == ".." }
    private fun hash(value: String) = value.matches(Regex("[a-f0-9]{64}"))
    private fun JsonObject.objects(key: String) = getAsJsonArray(key).map { it.asJsonObject }
    private fun JsonObject.text(key: String): String {
        val v = get(key); require(v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString) { "Missing string: $key" }
        return v.asString
    }
    private fun JsonObject.int(key: String): Int {
        val v = get(key); require(v != null && v.isJsonPrimitive && v.asJsonPrimitive.isNumber) { "Missing integer: $key" }
        return v.asBigDecimal.intValueExact()
    }
}
