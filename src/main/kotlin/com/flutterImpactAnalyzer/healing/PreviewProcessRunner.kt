package com.flutterImpactAnalyzer.healing

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Bounded output, wall-clock timeout, and best-effort child-process cleanup. Never uses a shell. */
internal object PreviewProcessRunner {
    fun run(
        arguments: List<String>,
        directory: Path,
        timeout: Duration = Duration.ofSeconds(60),
        outputLimit: Int = 4 * 1024 * 1024,
        operationName: String = "Preview",
        onPoll: () -> Unit = {},
        cancelled: () -> Boolean = { false }
    ): ProcessResult {
        require(!timeout.isNegative && !timeout.isZero && outputLimit > 0)
        if (cancelled()) throw CancellationException("$operationName cancelled.")
        val process = ProcessBuilder(arguments).directory(directory.toFile()).apply {
            environment()["GIT_OPTIONAL_LOCKS"] = "0"
            environment()["DART_SUPPRESS_ANALYTICS"] = "true"
        }.start()
        val readers = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "flutter-healer-preview-output").apply { isDaemon = true }
        }
        val exceeded = AtomicBoolean()
        fun read(stream: InputStream): String = stream.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (count > outputLimit - output.size()) {
                    exceeded.set(true)
                    break
                }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
        val children = linkedMapOf<Long, ProcessHandle>()
        val deadline = System.nanoTime() + timeout.toNanos()
        var nextProgress = System.nanoTime()
        try {
            process.outputStream.close()
            val stdout = readers.submit<String> { read(process.inputStream) }
            val stderr = readers.submit<String> { read(process.errorStream) }
            while (true) {
                process.toHandle().descendants().use { stream -> stream.forEach { children[it.pid()] = it } }
                if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("$operationName cancelled.")
                check(!exceeded.get()) { "$operationName output exceeded the ${outputLimit / 1024} KiB limit; no report accepted." }
                if (!process.isAlive && stdout.isDone && stderr.isDone) {
                    val result = ProcessResult(process.exitValue(), stdout.get(), stderr.get())
                    check(!exceeded.get()) { "$operationName output exceeded the ${outputLimit / 1024} KiB limit; no report accepted." }
                    return result
                }
                check(System.nanoTime() < deadline) { "$operationName exceeded ${timeout.seconds} seconds; no report accepted." }
                if (System.nanoTime() >= nextProgress) {
                    onPoll()
                    nextProgress = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                }
                Thread.sleep(25)
            }
        } finally {
            // Collect descendants before terminating the parent (which can orphan them).
            process.toHandle().descendants().use { stream -> stream.forEach { children[it.pid()] = it } }
            children.values.toList().asReversed().forEach { if (it.isAlive) it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            readers.shutdownNow()
            process.inputStream.close()
            process.errorStream.close()
        }
    }
}


