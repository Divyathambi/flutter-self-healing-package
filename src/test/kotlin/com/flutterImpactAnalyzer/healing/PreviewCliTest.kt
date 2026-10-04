package com.flutterImpactAnalyzer.healing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class PreviewCliTest {
    @get:Rule val temporary = TemporaryFolder()
    private val project = Path.of("/example/project")

    private fun report(status: String = "requires_review"): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", 1)
        addProperty("project", project.toString())
        addProperty("baseline", "a".repeat(40))
        addProperty("filesModified", false)
        addProperty("testsExecuted", false)
        add("warnings", JsonArray())
        add("preview", JsonObject().apply {
            addProperty("status", status)
            addProperty("reason", "Candidate only; not validated.")
            addProperty("editCount", if (status == "requires_review") 1 else 0)
            addProperty("patch", if (status == "requires_review") "--- old\n+++ new\n" else "")
            add("files", JsonArray().apply {
                if (status == "requires_review") add(JsonObject().apply { addProperty("file", "test/widget_test.dart") })
            })
        })
    }

    private fun parse(json: JsonObject, exit: Int = 0) = PreviewReport.parse(ProcessResult(exit, json.toString(), ""), project)

    @Test fun `reviewable proposal remains unvalidated`() {
        val result = parse(report())
        assertEquals("requires_review", result.status)
        assertEquals(1, result.editCount)
        assertEquals(listOf("test/widget_test.dart"), result.files)
        assertTrue(result.patch.isNotBlank())
    }

    @Test fun `blocked exit two retains warnings without a patch`() {
        val data = report("blocked")
        data.getAsJsonArray("warnings").add("Combined key and color change")
        val result = parse(data, 2)
        assertEquals("blocked", result.status)
        assertEquals(listOf("Combined key and color change"), result.warnings)
        assertEquals("", result.patch)
    }

    @Test fun `no changes is distinct from reviewable or passed`() {
        val result = parse(report("no_changes"))
        assertEquals("no_changes", result.status)
        assertEquals(0, result.editCount)
    }

    @Test fun `exit status contradictions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { parse(report("blocked"), 0) }
        assertThrows(IllegalArgumentException::class.java) { parse(report(), 2) }
        assertThrows(IllegalArgumentException::class.java) { parse(report(), 64) }
    }

    @Test fun `unknown and fractional schemas are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { parse(report().apply { addProperty("schemaVersion", 4) }) }
        assertThrows(IllegalArgumentException::class.java) { parse(report().apply { addProperty("schemaVersion", 1.5) }) }
    }

    @Test fun `wrong project and missing baseline are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { parse(report().apply { addProperty("project", "/other") }) }
        assertThrows(IllegalArgumentException::class.java) { parse(report().apply { remove("baseline") }) }
    }

    @Test fun `mutating or test executing reports are rejected`() {
        for (flag in listOf("filesModified", "testsExecuted")) {
            assertThrows(IllegalArgumentException::class.java) { parse(report().apply { addProperty(flag, true) }) }
            assertThrows(IllegalArgumentException::class.java) { parse(report().apply { addProperty(flag, "false") }) }
            assertThrows(IllegalArgumentException::class.java) { parse(report().apply { remove(flag) }) }
        }
    }

    @Test fun `malformed output is never accepted as an empty preview`() {
        for (text in listOf("", "not json", "{}", "[]")) {
            assertThrows(IllegalArgumentException::class.java) {
                PreviewReport.parse(ProcessResult(0, text, ""), project)
            }
        }
    }

    @Test fun `contradictory edits and warning states are rejected`() {
        val data = report("blocked")
        data.getAsJsonObject("preview").addProperty("patch", "unexpected edit")
        assertThrows(IllegalArgumentException::class.java) { parse(data, 2) }
        val warnings = report()
        warnings.getAsJsonArray("warnings").add("Unsupported change")
        assertThrows(IllegalArgumentException::class.java) { parse(warnings) }
        val empty = report()
        empty.getAsJsonObject("preview").addProperty("editCount", 0)
        assertThrows(IllegalArgumentException::class.java) { parse(empty) }
        val unknown = report()
        unknown.getAsJsonObject("preview").addProperty("status", "passed")
        assertThrows(IllegalArgumentException::class.java) { parse(unknown) }
    }

    private fun commandFixture(): Triple<Path, Path, Path> {
        val root = temporary.newFolder("paths with spaces").toPath()
        val target = Files.createDirectories(root.resolve("target ; literal"))
        Files.writeString(target.resolve("pubspec.yaml"), "name: fixture\n")
        Files.writeString(target.resolve(".git"), "gitdir: fixture\n")
        val healer = Files.createDirectories(root.resolve("healer"))
        Files.createDirectories(healer.resolve("bin"))
        Files.writeString(healer.resolve("bin/flutter_test_healer.dart"), "")
        Files.createDirectories(healer.resolve(".dart_tool"))
        Files.writeString(healer.resolve(".dart_tool/package_config.json"), "{}")
        val sdk = Files.createDirectories(root.resolve("flutter"))
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val dart = sdk.resolve("bin/cache/dart-sdk/bin/" + if (windows) "dart.exe" else "dart")
        Files.createDirectories(dart.parent)
        Files.writeString(dart, "")
        dart.toFile().setExecutable(true)
        return Triple(target, healer, sdk)
    }

    @Test fun `command uses exact argument paths and direct Dart without pub resolution`() {
        val (target, healer, sdk) = commandFixture()
        val command = PreviewCommand.prepare(target.toString(), healer.toString(), sdk.toString())
        assertEquals(target.toRealPath(), command.project)
        assertEquals(healer.toRealPath(), command.workingDirectory)
        assertEquals(listOf("preview", "--project", target.toRealPath().toString(), "--base", "HEAD", "--json"), command.arguments.takeLast(6))
        assertTrue(command.arguments[1].startsWith("--packages="))
        assertFalse(command.arguments.any { it in setOf("run", "pub", "test", "apply", "--approve", "sh", "-c") })
    }

    @Test fun `unprepared dependencies and relative paths are rejected`() {
        val (target, healer, sdk) = commandFixture()
        Files.delete(healer.resolve(".dart_tool/package_config.json"))
        assertThrows(IllegalArgumentException::class.java) { PreviewCommand.prepare(target.toString(), healer.toString(), sdk.toString()) }
        assertThrows(IllegalArgumentException::class.java) { PreviewCommand.prepare("relative", healer.toString(), sdk.toString()) }
    }

    private fun fixture(vararg args: String): List<String> = listOf(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", requireNotNull(System.getProperty("healer.test.fixture.classpath")),
        PreviewProcessFixture::class.java.name
    ) + args

    @Test fun `process captures stdout and stderr separately`() {
        val result = PreviewProcessRunner.run(fixture("ok"), temporary.root.toPath())
        assertEquals(0, result.exitCode)
        assertEquals("preview output", result.stdout)
        assertEquals("diagnostic output", result.stderr)
    }

    @Test fun `oversized output is rejected`() {
        val error = assertThrows(IllegalStateException::class.java) {
            PreviewProcessRunner.run(fixture("large"), temporary.root.toPath(), outputLimit = 1024)
        }
        assertTrue(error.message!!.contains("output exceeded"))
    }

    @Test fun `overall timeout terminates a running process`() {
        val error = assertThrows(IllegalStateException::class.java) {
            PreviewProcessRunner.run(fixture("sleep"), temporary.root.toPath(), Duration.ofMillis(300))
        }
        assertTrue(error.message!!.contains("exceeded"))
    }

    @Test fun `cancellation before startup does not launch command`() {
        assertThrows(CancellationException::class.java) {
            PreviewProcessRunner.run(listOf("nonexistent-executable"), temporary.root.toPath(), cancelled = { true })
        }
    }

    @Test fun `running process can be cancelled`() {
        val polls = AtomicInteger()
        assertThrows(CancellationException::class.java) {
            PreviewProcessRunner.run(fixture("sleep"), temporary.root.toPath(), cancelled = { polls.incrementAndGet() > 5 })
        }
    }

    @Test fun `timeout also terminates a spawned child`() {
        val pidFile = temporary.root.toPath().resolve("child.pid")
        assertThrows(IllegalStateException::class.java) {
            PreviewProcessRunner.run(fixture("child", pidFile.toString()), temporary.root.toPath(), Duration.ofSeconds(2))
        }
        assertTrue("Child fixture should have started", Files.exists(pidFile))
        val child = ProcessHandle.of(Files.readString(pidFile).trim().toLong())
        repeat(40) {
            if (child.isEmpty || !child.get().isAlive) return
            Thread.sleep(25)
        }
        fail("Spawned child survived timeout cleanup")
    }

    @Test fun `optional real CLI preview smoke test`() {
        val target = System.getenv("HEALER_SMOKE_PROJECT")
        val healer = System.getenv("HEALER_SMOKE_ROOT")
        val sdk = System.getenv("HEALER_SMOKE_FLUTTER")
        assumeTrue("Opt-in read-only local CLI check", target != null && healer != null && sdk != null)
        val command = PreviewCommand.prepare(target!!, healer!!, sdk!!)
        val result = PreviewReport.parse(PreviewProcessRunner.run(command.arguments, command.workingDirectory), command.project)
        assertTrue(result.status in setOf("requires_review", "blocked", "no_changes"))
    }
}


