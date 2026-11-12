package tech.atomiclabs.link.device.access

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.device.phone.AndroidActivityResolver

/** One runtime-owned hub connects Android lifecycle calls to the tree port and activity resolver. */
class AccessibilityHub(
    private val logger: Logger,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val eventFeed: EventFeed? = null,
    activityResolver: ActivityResolver? = null,
) : UiTreeSource, ForegroundEvents {
    @Volatile private var service: AccessibilityService? = null
    @Volatile private var connectedCallback: (() -> Unit)? = null
    private val activities = mutableMapOf<String, String>()
    private val windowStateTimes = mutableMapOf<String, Long>()
    private val activityResolver = activityResolver ?: AndroidActivityResolver(
        { checkNotNull(service) { "The accessibility service is disconnected." }.packageManager }, logger)

    /** Snapshot of the actual service; consumers own dispatch/timeouts and handle a later disconnect. */
    fun currentService(): PortResult<AccessibilityService> = service?.let { PortResult.Success(it) }
        ?: failure("currentService", IllegalStateException("The accessibility service is disconnected."), FailureKind.UNAVAILABLE)

    /** Replaces/clears the callback for subsequent connections; Runtime registers before service startup. */
    fun setConnectedCallback(callback: (() -> Unit)?) { connectedCallback = callback }

    fun onConnected(connected: AccessibilityService) {
        service = connected
        logger.info("AccessibilityHub", "onConnected", "state=connected")
        // Publish the service first so the callback can immediately use the same current-service contract.
        try { connectedCallback?.invoke() }
        catch (cause: Exception) {
            logger.error("AccessibilityHub", "connectedCallback", "state=connected", FailureDiagnostics.sanitized(cause))
        }
    }

    fun onUnbind(disconnected: AccessibilityService) {
        if (service === disconnected) {
            service = null
            synchronized(activities) { activities.clear() }
            synchronized(windowStateTimes) { windowStateTimes.clear() }
        }
        logger.info("AccessibilityHub", "onUnbind", "state=disconnected")
    }

    fun onEvent(event: AccessibilityEvent?) {
        if (event == null) return
        eventFeed?.onEvent(event)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        synchronized(windowStateTimes) { windowStateTimes[packageName] = clock.monotonicMs() }
        val activity = event.className?.toString() ?: return
        val resolved = activityResolver.resolveActivity(packageName, activity) ?: return
        synchronized(activities) { activities[packageName] = resolved }
    }

    override fun lastWindowStateAt(packageName: String): Long? =
        synchronized(windowStateTimes) { windowStateTimes[packageName] }

    fun onInterrupt() {
        logger.info("AccessibilityHub", "onInterrupt", "state=interrupted")
    }

    fun activityForPackage(packageName: String): String? {
        synchronized(activities) { activities[packageName] }?.let { return it }
        if (service == null) return null
        // Resolve an entry only in the absence of a validated window-state event; never cache it as resumed.
        val entry = activityResolver.resolveActivity(packageName)
        return synchronized(activities) { activities[packageName] } ?: entry
    }

    override suspend fun isConnected(): PortResult<Boolean> = PortResult.Success(service != null)

    override suspend fun windows(): PortResult<List<UiWindow>> {
        val raw = checked("windows") { connected ->
            clearCache(connected, "windows")
            requireNotNull(connected.windows) { "The accessibility window list is unavailable." }
        }
        if (raw is PortResult.Failure) return raw
        val windows = mutableListOf<UiWindow>()
        for (window in (raw as PortResult.Success).value) {
            when (val snapshot = AndroidUiWindow(window, logger, dispatcher).snapshot()) {
                is PortResult.Success -> if (snapshot.value.root != null) windows += snapshot.value
                is PortResult.Failure -> Unit // The adapter logs the permitted per-window skip at its source.
            }
        }
        return PortResult.Success(windows)
    }

    override suspend fun activeRoot(): ReadValue<UiNode> = optional("activeRoot") {
        clearCache(it, "activeRoot")
        it.rootInActiveWindow
    }

    override suspend fun inputFocus(): ReadValue<UiNode> = optional("inputFocus") {
        clearCache(it, "inputFocus")
        it.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    private fun clearCache(connected: AccessibilityService, operation: String) {
        if (!connected.clearCache()) logger.warn("AccessibilityHub", operation, "state=cache_not_cleared")
    }

    private suspend fun optional(
        operation: String,
        read: (AccessibilityService) -> AccessibilityNodeInfo?,
    ): ReadValue<UiNode> = when (val result = checked(operation, read)) {
        is PortResult.Failure -> ReadValue.Unreadable(result.failure)
        is PortResult.Success -> result.value?.let {
            ReadValue.Present(AndroidUiNode(it, logger, dispatcher))
        } ?: ReadValue.Absent
    }

    private suspend fun <T> checked(operation: String, read: (AccessibilityService) -> T): PortResult<T> {
        val connected = service ?: return failure(operation,
            IllegalStateException("The accessibility service is disconnected."), FailureKind.UNAVAILABLE)
        return try {
            PortResult.Success(withTimeout(5_000) { withContext(dispatcher) { read(connected) } })
        } catch (cause: TimeoutCancellationException) {
            failure(operation, cause, FailureKind.TIMED_OUT)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            failure(operation, cause, if (cause is SecurityException) FailureKind.PERMISSION_DENIED else FailureKind.PLATFORM)
        }
    }

    private fun failure(operation: String, cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("AccessibilityHub", operation, "state=read_failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause))
    }
}
