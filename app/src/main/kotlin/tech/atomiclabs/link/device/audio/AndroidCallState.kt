package tech.atomiclabs.link.device.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidCallState(private val context: Context, private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : CallStateSource {
    override suspend fun read(): PortResult<CallState> = try {
        withTimeout(2_000) { withContext(dispatcher) {
            if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Phone state permission is required.")
            }
            val manager = checkNotNull(context.getSystemService(TelephonyManager::class.java)) { "Telephony is unavailable." }
            val state = when (manager.callState) {
                TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
                TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
                else -> error("The platform returned an unknown call state.")
            }
            PortResult.Success(state)
        } }
    } catch (cause: TimeoutCancellationException) { failure(cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) { throw cause
    } catch (cause: SecurityException) { failure(cause, FailureKind.PERMISSION_DENIED)
    } catch (cause: IllegalArgumentException) { failure(cause, FailureKind.PLATFORM)
    } catch (cause: IllegalStateException) { failure(cause, FailureKind.PLATFORM)
    } catch (cause: UnsupportedOperationException) { failure(cause, FailureKind.PLATFORM)
    }

    private fun failure(cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("AndroidCallState", "read", "state=failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause,
            permission = if (kind == FailureKind.PERMISSION_DENIED) Manifest.permission.READ_PHONE_STATE else null))
    }
}
