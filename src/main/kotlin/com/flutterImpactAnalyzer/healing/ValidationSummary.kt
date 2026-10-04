package com.flutterImpactAnalyzer.healing

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Display only. Eligibility and exact evidence checks remain in the CLI report parsers. */
internal object ValidationSummary {
    fun render(report: ValidationReport, preview: PreviewReport): String {
        val data = runCatching { JsonParser.parseString(report.rawJson).asJsonObject }.getOrNull()
            ?: return "Failed-test details could not be displayed.\nReport: ${report.directory}\n\n" +
                "See Finder edits for the full proposed patch."
        val evidence = data.objectValue("failureEvidence")
        val failures = evidence?.get("failures")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }.orEmpty()
        val compileErrors = evidence?.get("compileErrors")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }.orEmpty()
        val afterFailures = evidence?.objects("afterFailures").orEmpty()
        return buildString {
            if (!report.successful) {
                append("Check not successful.\n")
                append(data.text("reason") ?: "The repair could not be verified.")
                append("\n\n")
            }
            if (compileErrors.isNotEmpty()) {
                append("Tests could not compile before the repair.\n\n")
                compileErrors.forEach { error ->
                    append("${error.text("file") ?: "Test file"}${error.positiveLine()?.let { ":$it" }.orEmpty()}\n")
                    append("   Proposed member: ${error.text("oldName") ?: "?"} → ${error.text("newName") ?: "?"}\n")
                }
                append(if (report.successful) "\nThe baseline tests passed with the proposed edits.\n\n" else "\nRepair not verified.\n\n")
            } else if (failures.isEmpty()) {
                append(if (afterFailures.isEmpty()) "No failed test names were recorded.\n"
                    else "No failure names were recorded before the proposed edits.\n")
            } else {
                append("Recorded failures before the proposed edits\n\n")
                failures.forEachIndexed { index, failure ->
                    append("${index + 1}. ${failure.text("testName") ?: "Unnamed test"}\n")
                    val location = failure.objectValue("failureLocation")
                    val file = failure.text("testFile") ?: location?.text("file")
                    if (file != null) {
                        val line = if (location?.text("file") == file) location.positiveLine() else null
                        append("   $file${line?.let { ":$it" }.orEmpty()}\n")
                    }
                    val edit = failure.objectValue("proposedEdit")
                    val oldKey = edit?.text("oldKey")
                    val newKey = edit?.text("newKey")
                    if (oldKey != null && newKey != null) {
                        append("   Proposed key: $oldKey → $newKey\n")
                    } else {
                        append("   No linked finder edit recorded.\n")
                    }
                    val reasons = failure.strings("reasons")
                    val attributed = failure.text("assessment") == "linked_failure_passed_after" &&
                        failure.text("beforeOutcome") == "failed" && edit != null && reasons.isEmpty()
                    val outcome = when (failure.text("afterOutcome")) {
                        "passed" -> if (attributed) "Passed with proposed edit"
                            else "Passed afterward — repair attribution not verified"
                        "failed" -> "Still failing after proposed edit"
                        "skipped" -> "Skipped — not verified"
                        "incomplete" -> "Did not complete — not verified"
                        else -> "Not verified after proposed edit"
                    }
                    append("   $outcome\n")
                    reasons.take(5).forEach { append("   Reason: ${it.take(600)}\n") }
                    append("\n")
                }
            }
            if (afterFailures.isNotEmpty()) {
                append("\nFailures after the proposed edits\n\n")
                afterFailures.forEachIndexed { index, failure ->
                    append("${index + 1}. ${failure.text("testName") ?: "Unnamed test"}\n")
                    val location = failure.objectValue("failureLocation")
                    val file = location?.text("file") ?: failure.text("testFile")
                    if (file != null) {
                        append("   $file${location?.positiveLine()?.let { ":$it" }.orEmpty()}\n")
                    }
                    append("   " + when (failure.text("beforeOutcome")) {
                        "passed" -> "New failure — passed before, failed after proposed edits"
                        "failed" -> "Still failing after proposed edits"
                        "skipped" -> "Failed after proposed edits — skipped before"
                        else -> "Failed after proposed edits — prior outcome not verified"
                    } + "\n")
                    val diagnostic = failure.text("diagnostic").orEmpty().lineSequence()
                        .map { it.trim() }
                        .takeWhile { !it.startsWith("When the exception") && !it.startsWith("#") }
                        .filter { it.isNotEmpty() && !it.contains("EXCEPTION CAUGHT BY FLUTTER TEST FRAMEWORK") }
                        .take(6).joinToString("\n   ").take(1200)
                    if (diagnostic.isNotEmpty()) append("   $diagnostic\n")
                    append("   See after.log for full diagnostics.\n\n")
                }
            } else if (runCatching { evidence?.objectValue("after")?.get("failed")?.asInt ?: 0 }.getOrDefault(0) > 0) {
                append("\nThe after-run recorded failures, but this report has no after-failure details. See after.log.\n")
            }
            if (!report.successful) {
                val issues = evidence?.strings("issues").orEmpty().toMutableList()
                if (data.flag("baselineCoverageMatches") == false) {
                    issues.add("The selected baseline/current test inventories did not match or could not be verified.")
                }
                if (issues.isNotEmpty()) {
                    append("\nWhy approval is blocked\n")
                    issues.distinct().take(8).forEach { append("- ${it.take(600)}\n") }
                }
            }
            appendProposedEdits(preview)
            append("Proposed edits: ${preview.editCount}. See Finder edits for the full patch.\n")
            append("No repair applied yet.")

            val changed = report.status == "input_changed" || data.flag("originalFilesUnchanged") == false
            val cleanup = report.status == "cleanup_failed" || data.flag("workspaceRemoved") == false
            val preservationUnknown = data.flag("testsExecuted") == true && data.flag("originalFilesUnchanged") == null
            if (changed) append("\n\nWarning: target inputs changed during checking. Review your files and check again.")
            if (preservationUnknown) append("\n\nWarning: input preservation was not verified. Review your files before retrying.")
            if (cleanup) append("\n\nWarning: disposable-file cleanup did not complete. Review retained files before retrying.")
            if (!report.successful || changed || cleanup || preservationUnknown) {
                append("\nReport for investigation: ${report.directory}")
            }
        }
    }

    private fun StringBuilder.appendProposedEdits(preview: PreviewReport) {
        val proposal = runCatching { JsonParser.parseString(preview.rawJson).asJsonObject }.getOrNull()
            ?.objectValue("preview") ?: return
        val files = proposal.objects("files")
        if (files.none { it.objects("edits").isNotEmpty() }) return
        append("\nAll proposed finder edits (not validation results)\n")
        append("Includes edits without a linked failure record; listing an edit does not mean it passed validation.\n\n")
        files.forEach { file ->
            val edits = file.objects("edits")
            if (edits.isNotEmpty()) {
                append("${file.text("file") ?: "Test file"}\n")
                edits.forEach { edit ->
                    append("   ${edit.positiveLine()?.let { "Line $it: " }.orEmpty()}")
                    append("${edit.text("before") ?: "?"} → ${edit.text("replacement") ?: "?"}\n")
                }
                append("\n")
            }
        }
    }

    private fun JsonObject.objects(key: String): List<JsonObject> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }.orEmpty()

    private fun JsonObject.strings(key: String): List<String> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { item -> item.isJsonPrimitive && item.asJsonPrimitive.isString }?.asString }
            ?.filter { it.isNotBlank() }.orEmpty()

    private fun JsonObject.objectValue(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.flag(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.positiveLine(): Int? = runCatching {
        get("line")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asBigDecimal?.intValueExact()?.takeIf { it > 0 }
    }.getOrNull()
}
