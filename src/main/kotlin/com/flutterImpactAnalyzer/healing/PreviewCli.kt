package com.flutterImpactAnalyzer.healing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/** Only a preview command is constructed here. No shell, pub, test, or apply commands. */
data class PreviewCommand(val project: Path, val workingDirectory: Path, val arguments: List<String>) {
    companion object {
        fun prepare(projectPath: String, healerPath: String, flutterSdkPath: String): PreviewCommand {
            fun directory(value: String, label: String): Path {
                require(value.isNotBlank()) { "$label is required." }
                val input = Path.of(value)
                require(input.isAbsolute) { "$label must be an absolute path." }
                val path = input.toRealPath()
                require(Files.isDirectory(path)) { "$label must be a directory." }
                return path
            }
            val project = directory(projectPath, "Target project")
            val healer = directory(healerPath, "Healer directory")
            val sdk = directory(flutterSdkPath, "Flutter SDK")
            require(Files.isRegularFile(project.resolve("pubspec.yaml")) && Files.exists(project.resolve(".git"))) {
                "Target must be a Git-root Dart/Flutter project (worktrees are supported)."
            }
            val script = healer.resolve("bin/flutter_test_healer.dart")
            val packages = healer.resolve(".dart_tool/package_config.json")
            require(Files.isRegularFile(script)) { "Healer directory has no bin/flutter_test_healer.dart." }
            require(Files.isRegularFile(packages)) {
                "Healer dependencies are not prepared. Set them up separately; preview will not run pub get."
            }
            val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            val dart = sdk.resolve("bin/cache/dart-sdk/bin/" + if (windows) "dart.exe" else "dart")
            require(Files.isRegularFile(dart) && Files.isExecutable(dart)) {
                "Flutter SDK has no ready bundled Dart executable. Prepare the SDK separately."
            }
            return PreviewCommand(project, healer, listOf(
                dart.toString(), "--packages=$packages", script.toString(),
                "preview", "--project", project.toString(), "--base", "HEAD", "--json"
            ))
        }
    }
}

data class PreviewReport(
    val status: String,
    val reason: String,
    val baseline: String,
    val warnings: List<String>,
    val files: List<String>,
    val editCount: Int,
    val patch: String,
    val rawJson: String
) {
    companion object {
        /** Fail closed on malformed output, unsupported schemas, or exit/status contradictions. */
        internal fun parse(output: ProcessResult, expectedProject: Path): PreviewReport {
            require(output.exitCode == 0 || output.exitCode == 2) {
                "Preview command failed (exit ${output.exitCode}).\n${output.stderr.take(8000)}"
            }
            try {
                val root = JsonParser.parseString(output.stdout).asJsonObject
                fun JsonObject.text(key: String): String {
                    val value = get(key)
                    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Missing string: $key" }
                    return value.asString
                }
                fun JsonObject.number(key: String): Int {
                    val value = get(key)
                    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Missing number: $key" }
                    return value.asBigDecimal.intValueExact()
                }
                val schema = root.number("schemaVersion")
                require(schema in setOf(1, 2, 3)) { "Unsupported preview schema." }
                for (key in listOf("filesModified", "testsExecuted")) {
                    val value = root.get(key)
                    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean && !value.asBoolean) {
                        "Report does not describe a read-only preview."
                    }
                }
                require(Path.of(root.text("project")) == expectedProject) { "Report belongs to a different project." }
                val baseline = root.text("baseline")
                require(baseline.matches(Regex("[a-f0-9]{40}([a-f0-9]{24})?"))) { "Missing resolved Git baseline." }
                val warnings = root.getAsJsonArray("warnings").map {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Invalid warning." }
                    it.asString
                }
                val preview = root.getAsJsonObject("preview")
                val status = preview.text("status")
                require(status in setOf("requires_review", "blocked", "no_changes")) { "Unknown preview status." }
                require(output.exitCode == if (status == "blocked") 2 else 0) { "Exit code contradicts preview status." }
                val patch = preview.text("patch")
                val count = preview.number("editCount")
                val files = preview.getAsJsonArray("files").map { it.asJsonObject.text("file") }
                if (status == "requires_review") {
                    require(count > 0 && files.isNotEmpty() && patch.isNotBlank() && warnings.isEmpty()) {
                        "Incomplete or contradictory proposal."
                    }
                } else {
                    require(count == 0 && files.isEmpty() && patch.isEmpty()) { "Unexpected edits in non-reviewable report." }
                    require(status != "no_changes" || warnings.isEmpty()) { "Warnings cannot be reported as no changes." }
                }
                if (schema == 2) SharedKeyReportContract.validatePreview(root)
                if (schema == 3) LiteralBatchReportContract.validatePreview(root)
                return PreviewReport(status, preview.text("reason"), baseline, warnings, files, count, patch, output.stdout)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid preview report: ${e.message}", e)
            }
        }
    }
}


