// Adapted from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at da782a8.
package tech.atomiclabs.link.device.phone
import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.telephony.SmsManager
import java.util.UUID
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidSmsSender(private val context: Context, private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val newId: () -> String = { UUID.randomUUID().toString() }) : SmsSender {
    override suspend fun send(to: String, body: String): SmsOutcome {
        var parts = 0
        var submitted = false
        return try {
            withTimeout(15_000) { withContext(dispatcher) {
                if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    throw SecurityException("SMS permission is missing.")
                }
                val manager = checkNotNull(context.getSystemService(SmsManager::class.java)) { "SMS service is unavailable." }
                val messages = manager.divideMessage(body)
                check(messages.isNotEmpty()) { "SMS segmentation produced no parts." }
                parts = messages.size
                val action = context.packageName + ".SMS_SENT." + newId()
                val receiver = RadioResults(action, parts)
                val pending = arrayListOf<PendingIntent>()
                context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
                var primary: Exception? = null
                try {
                    prepareCallbacks(action, parts, pending)
                    submitted = true
                    manager.sendMultipartTextMessage(to, null, messages, pending, null)
                    acknowledged(receiver.completed.await(), parts)
                } catch (cause: Exception) {
                    primary = cause
                    throw cause
                } finally {
                    val cleanup = cleanup(receiver, pending)
                    if (cleanup != null) {
                        if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
                    }
                }
            } }
        } catch (cause: TimeoutCancellationException) {
            report("radioWait", cause)
            if (submitted) SmsOutcome.Unconfirmed(parts) else SmsOutcome.Failed(PortFailure(FailureKind.TIMED_OUT, cause))
        } catch (cause: CancellationException) { throw cause
        } catch (cause: SecurityException) {
            report("send", cause)
            SmsOutcome.Failed(PortFailure(FailureKind.PERMISSION_DENIED, cause, permission = Manifest.permission.SEND_SMS))
        } catch (cause: Exception) {
            report("send", cause)
            SmsOutcome.Failed(PortFailure(FailureKind.PLATFORM, cause))
        }
    }
    private fun prepareCallbacks(action: String, parts: Int, pending: MutableList<PendingIntent>) {
        for (index in 0 until parts) {
            pending += PendingIntent.getBroadcast(context, index,
                Intent(action).setPackage(context.packageName).putExtra(PART, index),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
    private fun acknowledged(code: Int, parts: Int): SmsOutcome {
        if (code == Activity.RESULT_OK) return SmsOutcome.Sent(parts)
        val cause = IllegalStateException("The radio rejected an SMS part.")
        report("radio", cause)
        return SmsOutcome.RadioFailure(code, PortFailure(FailureKind.PLATFORM, cause, platformCode = code))
    }
    private inner class RadioResults(private val action: String, private val parts: Int) : BroadcastReceiver() {
        val completed = CompletableDeferred<Int>()
        private val received = mutableSetOf<Int>()
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                check(intent != null && intent.action == action && intent.hasExtra(PART)) { "SMS callback is invalid." }
                val index = intent.getIntExtra(PART, -1)
                check(index in 0 until parts) { "SMS callback part is invalid." }
                synchronized(received) {
                    if (!received.add(index)) logger.warn("AndroidSmsSender", "radio", "state=duplicate_part")
                    else if (resultCode != Activity.RESULT_OK) completed.complete(resultCode)
                    else if (received.size == parts) completed.complete(Activity.RESULT_OK)
                }
            } catch (cause: Exception) {
                report("radioCallback", cause)
                completed.completeExceptionally(cause)
            }
        }
    }
    private fun cleanup(receiver: BroadcastReceiver, pending: List<PendingIntent>): Exception? {
        var failure: Exception? = null
        fun record(cause: Exception) {
            report("cleanup", cause)
            val previous = failure
            if (previous == null) failure = cause else if (previous !== cause) previous.addSuppressed(cause)
        }
        try { context.unregisterReceiver(receiver) } catch (cause: Exception) { record(cause) }
        pending.forEach { try { it.cancel() } catch (cause: Exception) { record(cause) } }
        return failure
    }
    private fun report(operation: String, cause: Throwable) =
        logger.error("AndroidSmsSender", operation, "state=failed", FailureDiagnostics.sanitized(cause))
    private companion object { const val PART = "atomic.sms.part" }
}
