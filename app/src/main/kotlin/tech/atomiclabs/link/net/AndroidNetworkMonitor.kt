package tech.atomiclabs.link.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

/** Each collection owns one default-network callback; cancelling collection unregisters it. */
class AndroidNetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : NetworkMonitor {
    override val changes = callbackFlow<PortResult<Boolean>> {
        fun emit(result: PortResult<Boolean>) {
            if (trySend(result).isFailure) logger.warn("AndroidNetworkMonitor", "notify", "state=observer_closed")
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { emit(PortResult.Success(true)) }
            override fun onLost(network: Network) { emit(PortResult.Success(false)) }
            override fun onUnavailable() { emit(PortResult.Success(false)) }
        }
        var registered = false
        try {
            withTimeout(5_000) { withContext(dispatcher) {
                connectivity.registerDefaultNetworkCallback(callback)
                registered = true
            } }
            send(isAvailable())
            awaitClose()
        } catch (cause: TimeoutCancellationException) {
            send(failure("register", cause, FailureKind.TIMED_OUT))
            close()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            send(failure("register", cause))
            close()
        } finally {
            if (registered) withContext(NonCancellable) {
                try {
                    withTimeout(5_000) { withContext(dispatcher) { connectivity.unregisterNetworkCallback(callback) } }
                } catch (cause: Exception) {
                    failure("unregister", cause, if (cause is TimeoutCancellationException) FailureKind.TIMED_OUT else kind(cause))
                }
            }
        }
    }.buffer(Channel.UNLIMITED)

    override suspend fun isAvailable(): PortResult<Boolean> = try {
        PortResult.Success(withTimeout(5_000) { withContext(dispatcher) {
            val network = connectivity.activeNetwork
            if (network == null) false else {
                val capabilities = checkNotNull(connectivity.getNetworkCapabilities(network)) {
                    "The active network capabilities could not be read."
                }
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } })
    } catch (cause: TimeoutCancellationException) {
        failure("isAvailable", cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        failure("isAvailable", cause)
    }

    private fun failure(operation: String, cause: Exception, kind: FailureKind = kind(cause)): PortResult.Failure {
        val safe = FailureDiagnostics.sanitized(cause)
        logger.error("AndroidNetworkMonitor", operation, "state=failed", safe)
        return PortResult.Failure(PortFailure(kind, safe))
    }

    private fun kind(cause: Exception) = if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM
}
