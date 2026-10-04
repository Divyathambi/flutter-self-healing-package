package com.flutterImpactAnalyzer.healing

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class LiteralBatchCliSmokeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `optional real batch check and guarded apply`() {
        val healer = System.getenv("HEALER_SMOKE_ROOT")
        val sdkPath = System.getenv("HEALER_SMOKE_FLUTTER")
        assumeTrue("Opt-in disposable batch workflow", System.getenv("HEALER_BATCH_SMOKE") == "1" && healer != null && sdkPath != null)
        val root = temporary.root.toPath().toRealPath()
        val project = Files.createDirectory(root.resolve("project"))
        val sdk = Path.of(sdkPath!!).toRealPath()
        fun write(path: String, value: String) {
            val file = project.resolve(path); Files.createDirectories(file.parent); Files.writeString(file, value)
        }
        fun execute(vararg args: String) {
            val result = PreviewProcessRunner.run(args.toList(), project, Duration.ofMinutes(3), operationName = "Fixture setup")
            assertEquals(result.stdout + result.stderr, 0, result.exitCode)
        }
        val source = """
            import 'package:flutter/widgets.dart';
            Widget a() => Text('A', textDirection: TextDirection.ltr, key: Key('alpha'));
            Widget b() => Text('B', textDirection: TextDirection.ltr, key: Key('beta'));
        """.trimIndent()
        val tests = """
            import 'package:flutter/widgets.dart';
            import 'package:flutter_test/flutter_test.dart';
            import 'package:batch_fixture/widgets.dart';
            void main() {
              testWidgets('alpha', (tester) async {
                await tester.pumpWidget(a());
                expect(find.byKey(Key('alpha')), findsOneWidget);
              });
              testWidgets('beta', (tester) async {
                await tester.pumpWidget(b());
                expect(find.byKey(Key('beta')), findsOneWidget);
              });
            }
        """.trimIndent()
        write(".gitignore", ".dart_tool/\nbuild/\n")
        write("pubspec.yaml", "name: batch_fixture\nenvironment:\n  sdk: '>=3.11.0 <4.0.0'\ndependencies:\n  flutter:\n    sdk: flutter\ndev_dependencies:\n  flutter_test:\n    sdk: flutter\n")
        write("lib/widgets.dart", source); write("test/widgets_test.dart", tests)
        execute("git", "init", "-q")
        execute(sdk.resolve("bin/flutter").toString(), "pub", "get", "--offline")
        execute("git", "add", ".")
        execute("git", "-c", "user.name=Healer Test", "-c", "user.email=healer@example.invalid", "-c", "commit.gpgSign=false",
            "-c", "core.hooksPath=/dev/null", "commit", "-qm", "fixture baseline")
        write("lib/widgets.dart", source.replace("'alpha'", "'alpha_new'").replace("'beta'", "'beta_new'"))
        val command = PreviewCommand.prepare(project.toString(), healer!!, sdk.toString())
        val check = CheckFixWorkflow.execute(command, sdk, createRun = {
            PrivateValidationRun.create(project, it.report.rawJson, root.resolve("validation-runs"))
        })
        assertEquals(2, check.selection.report.editCount)
        assertEquals("before_failed_after_passed", check.validation?.status)
        assertEquals(tests, Files.readString(project.resolve("test/widgets_test.dart")))
        val proposal = requireNotNull(check.proposal)
        val applied = ApplyWorkflow.execute(ApplyRequest.create(proposal, true, root.resolve("apply-runs")))
        assertEquals(applied.details, "applied_and_tests_passed", applied.status)
        assertEquals(tests.replace("Key('alpha')", "Key('alpha_new')").replace("Key('beta')", "Key('beta_new')"),
            Files.readString(project.resolve("test/widgets_test.dart")))
    }
}
