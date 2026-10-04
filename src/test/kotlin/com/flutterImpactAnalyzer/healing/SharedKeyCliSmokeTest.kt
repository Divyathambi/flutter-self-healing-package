package com.flutterImpactAnalyzer.healing

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Real CLI/Flutter interoperability, only in repositories created by this test. */
class SharedKeyCliSmokeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `optional real shared value workflow`() = exercise(symbol = false)
    @Test fun `optional real shared member workflow`() = exercise(symbol = true)

    private fun exercise(symbol: Boolean) {
        val enabled = System.getenv("HEALER_SHARED_SMOKE") == "1"
        val healer = System.getenv("HEALER_SMOKE_ROOT")
        val sdkPath = System.getenv("HEALER_SMOKE_FLUTTER")
        assumeTrue("Opt-in disposable shared-key CLI/Flutter test", enabled && healer != null && sdkPath != null)
        val root = temporary.root.toPath().toRealPath()
        val project = Files.createDirectory(root.resolve("project"))
        val sdk = Path.of(sdkPath!!).toRealPath()
        fun write(path: String, text: String) {
            val file = project.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        fun execute(arguments: List<String>) {
            val result = PreviewProcessRunner.run(arguments, project, Duration.ofMinutes(3), operationName = "Fixture setup")
            assertEquals(result.stdout + result.stderr, 0, result.exitCode)
        }
        val keys = """
            import 'package:flutter/widgets.dart';
            class AppKeys { static const submitButton = Key('submit_button'); }
        """.trimIndent()
        val widget = """
            import 'package:flutter/widgets.dart';
            import 'keys.dart';
            Widget buildButton() => Text('Submit', textDirection: TextDirection.ltr, key: AppKeys.submitButton);
        """.trimIndent()
        val tests = """
            import 'package:flutter/widgets.dart';
            import 'package:flutter_test/flutter_test.dart';
            import 'package:healer_fixture/keys.dart';
            import 'package:healer_fixture/button.dart';
            void main() {
              testWidgets('finds submit', (tester) async {
                await tester.pumpWidget(buildButton());
                expect(find.byKey(AppKeys.submitButton), findsOneWidget);
                expect(find.byKey(const Key('submit_button')), findsOneWidget);
              });
            }
        """.trimIndent()
        write(".gitignore", ".dart_tool/\nbuild/\n")
        write("pubspec.yaml", """
            name: healer_fixture
            environment:
              sdk: '>=3.11.0 <4.0.0'
            dependencies:
              flutter:
                sdk: flutter
            dev_dependencies:
              flutter_test:
                sdk: flutter
        """.trimIndent())
        write("lib/keys.dart", keys); write("lib/button.dart", widget); write("test/button_test.dart", tests)
        execute(listOf("git", "init", "-q"))
        execute(listOf(sdk.resolve("bin/flutter").toString(), "pub", "get", "--offline"))
        execute(listOf("git", "add", "."))
        execute(listOf("git", "-c", "user.name=Healer Test", "-c", "user.email=healer@example.invalid",
            "-c", "commit.gpgSign=false", "-c", "core.hooksPath=/dev/null", "commit", "-qm", "fixture baseline"))
        write("lib/keys.dart", if (symbol) keys.replace("submitButton", "confirmButton") else keys.replace("'submit_button'", "'confirm_button'"))
        if (symbol) write("lib/button.dart", widget.replace("submitButton", "confirmButton"))
        val command = PreviewCommand.prepare(project.toString(), healer!!, sdk.toString())
        val check = CheckFixWorkflow.execute(command, sdk, createRun = {
            PrivateValidationRun.create(project, it.report.rawJson, root.resolve("validation-runs"))
        })
        assertEquals(if (symbol) "before_compile_failed_after_passed" else "before_failed_after_passed", check.validation?.status)
        assertEquals(tests, Files.readString(project.resolve("test/button_test.dart")))
        val proposal = requireNotNull(check.proposal)
        val request = ApplyRequest.create(proposal, approved = true, root = root.resolve("apply-runs"))
        val result = ApplyWorkflow.execute(request)
        assertEquals(result.details, "applied_and_tests_passed", result.status)
        val expected = if (symbol) tests.replace("AppKeys.submitButton", "AppKeys.confirmButton")
            else tests.replace("Key('submit_button')", "Key('confirm_button')")
        assertEquals(expected, Files.readString(project.resolve("test/button_test.dart")))
        val repeat = ApplyWorkflow.execute(ApplyRequest.create(proposal, approved = true, root = root.resolve("apply-runs")))
        assertEquals(repeat.details, "already_applied", repeat.status)
    }
}
