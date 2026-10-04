package com.flutterImpactAnalyzer.healing

import com.google.gson.JsonObject

/** Structural/binding checks only. The CLI still resolves symbols and owns every write. */
internal object SharedKeyReportContract {
    private val kinds = setOf("shared_key_value", "shared_key_symbol")
    private val identifier = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    fun isSymbol(root: JsonObject): Boolean = root.get("schemaVersion")?.asInt == 2 &&
        root.getAsJsonArray("candidateRenames").single().asJsonObject.text("kind") == "shared_key_symbol"

    fun validatePreview(root: JsonObject) {
        val candidate = root.objects("candidateRenames").single()
        val kind = candidate.text("kind")
        require(kind in kinds) { "Unsupported shared-key candidate kind." }
        require(identifier.matches(candidate.text("owner"))) { "Invalid shared-key owner." }
        val oldName = candidate.text("oldName")
        val newName = candidate.text("newName")
        require(identifier.matches(oldName) && identifier.matches(newName)) { "Invalid shared-key member name." }
        val symbol = kind == "shared_key_symbol"
        require(if (symbol) oldName != newName else oldName == newName) { "Contradictory shared-key change." }
        val oldKey = candidate.text("oldKey")
        val newKey = candidate.text("newKey")
        require(oldKey != newKey) { "Shared-key candidate has no change." }
        if (symbol) require(oldKey == oldName && newKey == newName) { "Shared member names do not match edits." }
        val references = candidate.objects("references")
        references.forEach {
            require(testPath(it.text("file")) && it.integer("offset") >= 0 && it.integer("length") > 0 &&
                it.integer("line") > 0 && it.integer("column") > 0 &&
                it.text("editKind") == if (symbol) "shared_member" else "key_literal") { "Invalid shared finder reference." }
        }
        val preview = root.getAsJsonObject("preview")
        if (preview.text("status") != "requires_review") {
            if (preview.text("status") == "no_changes") require(references.isEmpty()) { "Unrepaired shared references remain." }
            return
        }
        val files = preview.objects("files")
        require(files.map { it.text("file") }.toSet().size == files.size) { "Duplicate shared test files." }
        val edits = files.flatMap { file ->
            require(testPath(file.text("file"))) { "Unsupported shared test path." }
            listOf("sha256Before", "sha256After").forEach {
                require(file.text(it).matches(Regex("[a-f0-9]{64}"))) { "Missing shared test fingerprint." }
            }
            file.objects("edits").also { require(it.isNotEmpty()) }.map { file.text("file") to it }
        }
        require(edits.isNotEmpty() && edits.size == preview.integer("editCount") && references.size == edits.size) {
            "Shared finder coverage does not match proposed edits."
        }
        require(edits.map { it.first to it.second.integer("offset") }.toSet().size == edits.size) { "Duplicate shared edits." }
        references.forEach { reference ->
            val edit = edits.single { (file, edit) -> file == reference.text("file") && edit.integer("offset") == reference.integer("offset") }.second
            require(edit.integer("length") == reference.integer("length") && edit.integer("line") == reference.integer("line") &&
                edit.text("before").length == edit.integer("length") && edit.text("replacement").isNotEmpty()) { "Shared edit span mismatch." }
            if (symbol) require(edit.text("before") == oldName && edit.text("replacement") == newName) { "Shared edit changes more than the member name." }
        }
        require(references.map { it.text("file") to it.integer("offset") }.toSet().size == references.size) { "Duplicate shared references." }
    }

    fun validateCompile(root: JsonObject, preview: JsonObject, flutter: String) {
        require(isSymbol(preview)) { "Compile validation requires a shared member rename." }
        val evidence = root.getAsJsonObject("failureEvidence")
        require(root.text("baselineCoverageComparison") == "baseline_after" &&
            evidence.text("assessment") == "linked_compile_errors_passed_after" &&
            evidence.text("mode") == "shared_symbol_compile_failure" &&
            evidence.text("inventoryComparison") == "baseline_after" && evidence.flag("baselineCoverageMatches") == true &&
            evidence.getAsJsonArray("issues").size() == 0 && evidence.getAsJsonArray("failures").size() == 0) {
            "Compile validation is missing exact attribution or inventory checks."
        }
        val ids = root.identities("validatedTestIdentities")
        require(evidence.identities("baselineTestIdentities") == ids && evidence.identities("afterTestIdentities") == ids) {
            "Baseline/after test inventories differ."
        }
        for (counts in listOf(root.getAsJsonObject("baselineCheck").getAsJsonObject("tests"), evidence.getAsJsonObject("after"))) {
            require(counts.flag("complete") == true && counts.flag("runnerSuccess") == true &&
                counts.integer("passed") == ids.size && counts.integer("failed") == 0 &&
                counts.integer("skipped") == 0 && counts.integer("incomplete") == 0) { "Incomplete passing test inventory." }
        }
        val before = evidence.getAsJsonObject("before")
        require(before.flag("complete") == true && before.flag("runnerSuccess") == false &&
            before.integer("skipped") == 0 && before.integer("incomplete") == 0) { "Incomplete compiler loading evidence." }
        val stages = root.objects("stages")
        for (name in listOf("baseline", "before", "after")) {
            val stage = stages.single { it.text("stage") == name }
            require(stage.text("executable") == flutter && stage.flag("timedOut") == false && stage.flag("logTruncated") == false &&
                (stage.get("startError") == null || stage.get("startError").isJsonNull) &&
                if (name == "before") stage.integer("exitCode") != 0 else stage.integer("exitCode") == 0) {
                "Incomplete or contradictory compile validation stage: $name"
            }
        }
        val candidate = preview.objects("candidateRenames").single()
        val references = candidate.objects("references")
        val errors = evidence.objects("compileErrors")
        require(errors.size == references.size && errors.isNotEmpty()) { "Missing exact compiler errors." }
        references.forEach { reference ->
            require(errors.count { error ->
                error.text("file") == reference.text("file") &&
                    listOf("line", "column", "offset", "length").all { error.integer(it) == reference.integer(it) } &&
                    error.text("oldName") == candidate.text("oldName") && error.text("newName") == candidate.text("newName") &&
                    error.text("category") == "missing_static_member" && error.text("linkKind") == "compiler_error_at_approved_member_identifier"
            } == 1) { "Compiler error does not match an approved member edit." }
        }
    }

    private fun testPath(value: String): Boolean = value.startsWith("test/") && value.endsWith(".dart") &&
        '\\' !in value && value.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun JsonObject.text(key: String): String {
        val value = get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Missing string: $key" }
        return value.asString
    }

    private fun JsonObject.integer(key: String): Int {
        val value = get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Missing integer: $key" }
        return value.asBigDecimal.intValueExact()
    }

    private fun JsonObject.flag(key: String): Boolean? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "Invalid boolean: $key" }
        return value.asBoolean
    }

    private fun JsonObject.objects(key: String): List<JsonObject> = getAsJsonArray(key).map { it.asJsonObject }

    private fun JsonObject.identities(key: String): Set<String> {
        val values = getAsJsonArray(key).map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank()) { "Invalid test identity." }
            it.asString
        }
        require(values.isNotEmpty() && values.toSet().size == values.size) { "Missing or duplicate test identities." }
        return values.toSet()
    }
}

