package tech.atomiclabs.link.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.connection.SessionFilePolicy
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

/** One-shot file adapter; an unacknowledged replacement restores the prior encrypted file state. */
class FileSessionStore(
    private val directory: File,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val writeStaged: (File, ByteArray) -> Unit = { file, bytes ->
        FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
    },
    private val move: (File, File) -> Unit = { from, to ->
        Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING); Unit
    },
) : SessionFilePolicy.Files {
    private val mutex = Mutex()
    private val target = File(directory, "session.bin")
    private val backup = File(directory, "session.bin.unreadable")

    override suspend fun read(): ReadValue<ByteArray> = mutex.withLock {
        try {
            withTimeout(2_000) { withContext(dispatcher) {
                requireDirectory()
                try { ReadValue.Present(Files.readAllBytes(target.toPath())) }
                catch (cause: NoSuchFileException) { ReadValue.Absent }
            } }
        } catch (cause: CancellationException) {
            if (cause !is TimeoutCancellationException) throw cause
            ReadValue.Unreadable(failure("read", cause, FailureKind.TIMED_OUT))
        } catch (cause: Exception) { ReadValue.Unreadable(failure("read", cause)) }
    }

    override suspend fun replace(encrypted: ByteArray, preserveUnreadable: Boolean): PortResult<Unit> = mutex.withLock {
        val replacement = Replacement()
        try {
            withTimeout(2_000) { withContext(dispatcher) {
                try {
                    replacement.stage(encrypted, preserveUnreadable)
                    ensureActive()
                    replacement.promote(preserveUnreadable) { ensureActive() }
                } finally { replacement.cleanup() }
            } }
            PortResult.Success(Unit)
        } catch (cause: Exception) {
            // withContext can discard a completed rename's result on cancellation; snapshots outlive that boundary.
            withContext(NonCancellable) {
                try { withTimeout(2_000) { withContext(dispatcher) { replacement.rollback() } } }
                catch (rollback: Exception) {
                    cause.addSuppressed(rollback)
                    failure("rollback", rollback)
                }
            }
            if (cause is CancellationException && cause !is TimeoutCancellationException) throw cause
            PortResult.Failure(failure("replace", cause,
                if (cause is TimeoutCancellationException) FailureKind.TIMED_OUT else FailureKind.IO))
        }
    }

    private inner class Replacement {
        private var oldTarget: ByteArray? = null
        private var oldBackup: ByteArray? = null
        private var archiveAttempted = false
        private var promotionAttempted = false
        private var staged: File? = null

        fun stage(encrypted: ByteArray, preserveUnreadable: Boolean) {
            requireDirectory()
            oldTarget = snapshot(target)
            if (preserveUnreadable && oldTarget != null) oldBackup = snapshot(backup)
            staged = Files.createTempFile(directory.toPath(), "session-", ".tmp").toFile()
            writeStaged(checkNotNull(staged), encrypted)
        }

        fun promote(preserveUnreadable: Boolean, checkActive: () -> Unit) {
            if (preserveUnreadable && oldTarget != null) {
                archiveAttempted = true
                move(target, backup)
                checkActive()
            }
            promotionAttempted = true
            move(checkNotNull(staged), target)
            staged = null
            checkActive()
        }

        fun rollback() {
            if (archiveAttempted || promotionAttempted) restore(target, oldTarget)
            if (archiveAttempted) restore(backup, oldBackup)
        }

        fun cleanup() {
            staged?.let { file ->
                try {
                    if (!Files.deleteIfExists(file.toPath())) {
                        logger.debug("FileSessionStore", "cleanup", "path=session_tmp state=already_absent")
                    }
                } catch (cause: Exception) { failure("cleanup", cause) }
            }
        }
    }

    private fun snapshot(file: File): ByteArray? = try { Files.readAllBytes(file.toPath()) }
        catch (cause: NoSuchFileException) { null }

    private fun restore(file: File, bytes: ByteArray?) {
        if (bytes == null) {
            if (!Files.deleteIfExists(file.toPath())) {
                logger.debug("FileSessionStore", "rollback", "path=session.bin state=already_absent")
            }
            return
        }
        val recovery = Files.createTempFile(directory.toPath(), "session-restore-", ".tmp").toFile()
        // Rollback uses the actual disk primitives, not the injected forward-write/move seams.
        FileOutputStream(recovery).use { output -> output.write(bytes); output.fd.sync() }
        Files.move(recovery.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun requireDirectory() {
        check(Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java).isDirectory) {
            "The session directory is unavailable."
        }
    }

    private fun failure(operation: String, cause: Exception, kind: FailureKind = FailureKind.IO): PortFailure {
        val safe = FailureDiagnostics.sanitized(cause)
        logger.error("FileSessionStore", operation, "path=session.bin state=file_failed", safe)
        return PortFailure(kind, safe)
    }
}
