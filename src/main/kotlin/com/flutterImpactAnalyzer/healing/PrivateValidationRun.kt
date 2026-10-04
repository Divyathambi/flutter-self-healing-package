package com.flutterImpactAnalyzer.healing

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** Each request has its own private directory; no shared/latest report can authorize another request. */
internal class PrivateValidationRun private constructor(val directory: Path, val previewBytes: ByteArray) {
    val previewPath: Path = directory.resolve("preview.json")
    val previewSha256: String = sha256(previewBytes)

    fun write(name: String, text: String) {
        require(name.matches(Regex("[a-zA-Z0-9.-]+")))
        Files.newByteChannel(directory.resolve(name), setOf(CREATE_NEW, WRITE), privateFile).use { channel ->
            val bytes = java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
        }
    }

    fun verifyPreview() {
        require(previewPath.toRealPath() == previewPath && Files.isRegularFile(previewPath, NOFOLLOW_LINKS)) {
            "Saved preview identity changed; generate a new preview."
        }
        require(Files.size(previewPath) == previewBytes.size.toLong() && Files.readAllBytes(previewPath).contentEquals(previewBytes)) {
            "Saved preview content changed; no validation accepted."
        }
    }

    companion object {
        private val privateDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val privateFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

        fun defaultRoot(): Path = Path.of(System.getProperty("user.home"), ".flutter-test-healer", "runs")

        fun create(project: Path, rawPreview: String, root: Path = defaultRoot()): PrivateValidationRun =
            createWithPrefix(project, rawPreview, root, "plugin-validation-")

        fun createApply(project: Path, rawPreview: String, root: Path = defaultRoot()): PrivateValidationRun =
            createWithPrefix(project, rawPreview, root, "plugin-apply-")

        private fun createWithPrefix(project: Path, rawPreview: String, root: Path, prefix: String): PrivateValidationRun {
            require(root.isAbsolute && root.normalize() == root) { "Artifact root must be an absolute, normalized path." }
            val target = project.toRealPath()
            require(!root.startsWith(target)) { "Validation artifacts must be outside the target project." }
            var ancestor: Path? = root
            while (ancestor != null) {
                require(!Files.isSymbolicLink(ancestor)) { "Artifact paths must not contain symlinks." }
                ancestor = ancestor.parent
            }
            var existing = root
            while (!Files.exists(existing, NOFOLLOW_LINKS)) existing = existing.parent
            require(Files.getFileStore(existing).supportsFileAttributeView("posix")) {
                "Private validation artifacts currently require POSIX permissions."
            }
            Files.createDirectories(root, privateDirectory)
            require(root.toRealPath() == root && !root.toRealPath().startsWith(target)) { "Unsafe artifact root." }
            require(Files.getPosixFilePermissions(root) == privateDirectory.value()) {
                "Artifact root must be private (0700). Its existing permissions were not changed."
            }
            val directory = Files.createTempDirectory(root, prefix, privateDirectory)
            val run = PrivateValidationRun(directory, rawPreview.toByteArray(Charsets.UTF_8))
            run.write("preview.json", rawPreview)
            run.verifyPreview()
            return run
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}