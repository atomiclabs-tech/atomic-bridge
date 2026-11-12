package tech.atomiclabs.link.device.power

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class TrampolineLauncher(
    private val context: Context,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : Trampoline {
    private val serial = Mutex()
    private val pending = mutableMapOf<Long, CompletableDeferred<PortResult<String?>>>()
    private var nextId = 0L

    override suspend fun wakeAndDismiss(): PortResult<Unit> = when (val result = runJob(DISMISS)) {
        is PortResult.Failure -> result
        is PortResult.Success -> PortResult.Success(Unit)
    }

    override suspend fun readClipboard(): PortResult<String> = when (val result = runJob(CLIPBOARD)) {
        is PortResult.Failure -> result
        is PortResult.Success -> result.value?.let { PortResult.Success(it) }
            ?: failure("clipboardResult", IllegalStateException("Clipboard callback contained no text."), FailureKind.INVALID_DATA)
    }

    /** A registered suspended core caller is completed exactly once; late callbacks are rejected. */
    fun complete(id: Long, result: PortResult<String?>): Boolean = synchronized(pending) {
        pending[id]?.complete(result) ?: false
    }

    private suspend fun runJob(job: String): PortResult<String?> = try {
        withTimeout(3_000) { serial.withLock {
            val callback = CompletableDeferred<PortResult<String?>>()
            val id = synchronized(pending) { (++nextId).also { pending[it] = callback } }
            try {
                withContext(dispatcher) {
                    context.startActivity(Intent(context, TrampolineActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        .putExtra(EXTRA_JOB, job).putExtra(EXTRA_ID, id))
                }
                callback.await()
            } finally {
                synchronized(pending) { pending.remove(id) }
            }
        } }
    } catch (cause: TimeoutCancellationException) {
        failure("runJob", cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        failure("runJob", cause, if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM)
    }

    private fun failure(operation: String, cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("TrampolineLauncher", operation, "state=job_failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }

    companion object {
        const val EXTRA_JOB = "atomic.job"
        const val EXTRA_ID = "atomic.job_id"
        const val DISMISS = "dismiss"
        const val CLIPBOARD = "clipboard"
    }
}
