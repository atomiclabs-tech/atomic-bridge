package tech.atomiclabs.link.service

import android.app.*
import android.content.*
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.capture.*
import tech.atomiclabs.link.core.audio.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.connection.*
import tech.atomiclabs.link.core.events.*
import tech.atomiclabs.link.core.input.*
import tech.atomiclabs.link.core.files.FileRoutes
import tech.atomiclabs.link.core.notify.NotificationFeed
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.phone.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.power.DisplayWaker
import tech.atomiclabs.link.core.screen.*
import tech.atomiclabs.link.core.status.StatusReporter
import tech.atomiclabs.link.core.setup.StatusPresenter
import tech.atomiclabs.link.device.access.*
import tech.atomiclabs.link.device.capture.*
import tech.atomiclabs.link.device.audio.*
import tech.atomiclabs.link.device.files.AndroidFileAccess
import tech.atomiclabs.link.device.notify.*
import tech.atomiclabs.link.device.phone.*
import tech.atomiclabs.link.device.power.*
import tech.atomiclabs.link.net.*
import tech.atomiclabs.link.storage.*
import tech.atomiclabs.link.ui.*

/** One Application-owned composition. Inputs replace platform ports in JVM tests, not the graph. */
class LinkRuntime(
    private val logger: Logger,
    private val inputs: Inputs,
    private val scope: CoroutineScope,
    context: Context? = null,
    private val installFailureHandler: (Thread.UncaughtExceptionHandler) -> Unit =
        Thread::setDefaultUncaughtExceptionHandler,
    private val previousFailureHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler(),
    private val terminateProcess: () -> Unit = {
        Process.killProcess(Process.myPid())
        exitProcess(1)
    },
) {
    data class Inputs(
        val tree: UiTreeSource,
        val foregroundEvents: ForegroundEvents,
        val display: DisplayGate,
        val capture: ScreenshotSource,
        val device: (WindowOrder) -> DeviceStateSource,
        val identity: DeviceIdentity,
        val permissions: PermissionState,
        val sockets: RelaySocketFactory,
        val network: NetworkMonitor,
        val sessions: SessionStore,
        val settings: SettingsStore,
        val clock: Clock,
        val media: (SessionTokenProvider) -> MediaTransfer,
        val activityForPackage: (String) -> String?,
        val openStatus: suspend () -> PortResult<Unit>,
        val connectedCallback: (((() -> Unit)?) -> Unit),
        val close: () -> Unit,
        val gestures: GestureInput,
        val globals: GlobalKeys,
        val volume: VolumeControl,
        val metrics: ScreenMetrics,
        val power: PowerState,
        val events: EventBuffer,
        val apps: AppControl,
        val clipboard: ClipboardAccess,
        val mediaKeys: MediaKeys,
        val location: LocationSource,
        val contacts: ContactsSource,
        val calls: CallControl,
        val sms: SmsSender,
        val outbound: OutboundFiles,
        val shares: ShareLauncher,
        val notifications: NotificationSource,
        val files: FileAccess,
        val probe: AudioProbe,
        val serviceAvailability: (Service?) -> Unit,
        val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        val newId: () -> String = { UUID.randomUUID().toString() },
    )

    constructor(context: Context) : this(context, AndroidLogger())
    private constructor(
        context: Context,
        logger: Logger,
        processScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    ) : this(logger, androidInputs(context, logger, processScope), processScope, context)

    private val started = AtomicBoolean()
    private val stopped = AtomicBoolean()
    private val windows = WindowOrder(inputs.tree, logger)
    val reporter = StatusReporter(inputs.identity, inputs.device(windows), inputs.permissions, inputs.clock, logger,
        inputs.notifications, inputs.newId)
    private val notificationFeed = NotificationFeed(inputs.notifications, logger)
    private lateinit var adoptedSession: SessionTokenProvider
    private val media = inputs.media(SessionTokenProvider { adoptedSession.token() })
    private val walker = ScreenTreeWalker(inputs.clock)
    private val lookup = NodeLookup(walker)
    private val geometry = SwipeGeometry(inputs.metrics)
    private val textEntry = TextEntry(inputs.tree, logger)
    val router = CommandRouter(listOf(
        CoreRoutes(inputs.tree, inputs.clock, inputs.openStatus, logger),
        ScreenRoutes(windows, walker, NodeSearch(walker), NodeDescriber(lookup),
            ScreenHasher(walker), inputs.activityForPackage),
        ScreenshotRoutes(ScreenshotCommand(inputs.capture, media, windows, inputs.clock, logger), ::httpBase),
        InputRoutes(TapCommands(inputs.gestures, windows, walker, lookup, logger), textEntry, geometry,
            ScrollCommand(windows, lookup, geometry, inputs.gestures, logger),
            KeyCommands(inputs.globals, inputs.volume, inputs.power, textEntry, inputs.clock),
            WaitCommand(windows, walker, inputs.clock), inputs.gestures, logger),
        EventRoutes(inputs.events),
        AppRoutes(inputs.apps, logger, inputs.tree, windows, inputs.clock, inputs.foregroundEvents), ClipboardRoutes(inputs.clipboard, logger),
        MediaKeyRoutes(inputs.mediaKeys, logger), LocationRoutes(inputs.location, inputs.clock, logger),
        ContactsRoutes(inputs.contacts, logger), CallSmsRoutes(inputs.calls, inputs.sms, logger),
        ShareRoutes(media, inputs.outbound, inputs.shares, inputs.clock, logger, ::httpBase),
        FileRoutes(inputs.files, media, inputs.permissions, logger, inputs.ioDispatcher, ::httpBase),
        ProbeRoutes(inputs.probe, inputs.permissions, logger),
        UnsupportedRoutes(),
    ), inputs.tree, inputs.display, logger, routeDeadlines = mapOf(
        ("POST" to "/call_audio_probe") to CommandDeadline(117_000),
        ("POST" to "/files/pull") to CommandDeadline(280_000),
        ("POST" to "/files/push") to CommandDeadline(280_000),
    ))
    val supervisor = ConnectionSupervisor(inputs.sockets, inputs.network, inputs.sessions, inputs.settings,
        inputs.identity, inputs.clock, router, reporter, logger, inputs.newId,
        streams = listOf(notificationFeed))
    val status: StatusSource get() = supervisor
    val connection: ConnectionControl get() = supervisor
    val sessionTokens: SessionTokenProvider get() = supervisor
    private val starter = context?.let { ServiceStarter(it, logger, ::start) }
    private val lifetime = context?.let {
        ConnectionServiceLifetime(ServiceNotification(it, logger),
            checkNotNull(it.getSystemService(PowerManager::class.java)), logger, inputs.serviceAvailability, ::start)
    }

    init {
        adoptedSession = supervisor
        inputs.connectedCallback(::requestStart)
    }

    fun requestStart() = checkNotNull(starter) { "Android startup is unavailable in the injected runtime." }.start()
    fun serviceCreated(service: Service) = checkNotNull(lifetime).created(service)
    fun serviceDestroyed() = checkNotNull(lifetime).destroyed()

    fun start() {
        check(!stopped.get()) { "The runtime has been permanently stopped." }
        if (!started.compareAndSet(false, true)) return
        installFailureHandler { thread, cause ->
            try {
                logger.error("LinkApp", "uncaughtException", "threadId=${thread.id}", FailureDiagnostics.sanitized(cause))
            } finally {
                try { previousFailureHandler?.uncaughtException(thread, cause) }
                finally { terminateProcess() }
            }
        }
        scope.launch {
            when (val result = notificationFeed.start()) {
                is PortResult.Success -> Unit
                is PortResult.Failure -> {
                    logger.error("LinkRuntime", "startNotifications", "state=starting",
                        FailureDiagnostics.sanitized(result.failure.cause))
                    throw IllegalStateException("Notification collection could not start.", result.failure.cause)
                }
            }
            supervisor.run()
        }
    }

    fun create(activity: Activity, linkSwitch: LinkSwitch? = null, onKilled: () -> Unit = {}): StatusViewBinder {
        val actions = AndroidSetupActions(activity, logger)
        val restrictedSettings = AndroidRestrictedSettings(activity.applicationContext, logger)
        val presenter = StatusPresenter(status, inputs.permissions, restrictedSettings, connection, actions, inputs.settings, logger,
            { RelayUrl.parse(it).httpBase },
            { seconds -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds)) })
        return StatusViewBinder(activity, presenter, scope, actions::onRequestPermissionsResult, linkSwitch, onKilled)
    }

    suspend fun shutdown() {
        if (!stopped.compareAndSet(false, true)) return
        inputs.connectedCallback(null)
        var failure: Exception? = null
        fun record(operation: String, cause: Exception) {
            logger.error("LinkRuntime", operation, "state=shutdown", FailureDiagnostics.sanitized(cause))
            val previous = failure
            if (previous == null) failure = cause else previous.addSuppressed(cause)
        }
        try {
            withTimeout(10_000) { checkNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
        } catch (cause: Exception) {
            record("stopJobs", cause)
        }
        try {
            withTimeout(5_000) {
                when (val result = notificationFeed.close()) {
                    is PortResult.Success -> Unit
                    is PortResult.Failure -> throw IllegalStateException("Notification collection could not close.", result.failure.cause)
                }
            }
        } catch (cause: Exception) {
            record("closeNotifications", cause)
        }
        try { inputs.close() } catch (cause: Exception) { record("closeClient", cause) }
        failure?.let { throw it }
    }

    private suspend fun httpBase(): String = try {
        withTimeout(5_000) {
            when (val value = inputs.settings.relayUrl()) {
                is ReadValue.Present -> RelayUrl.parse(value.value).httpBase
                is ReadValue.Unreadable -> throw IllegalStateException("The relay address could not be read.", value.failure.cause)
                ReadValue.Absent -> throw IllegalStateException("The relay address is missing.")
            }
        }
    } catch (cause: CancellationException) { throw cause }
    catch (cause: Exception) {
        logger.error("LinkRuntime", "mediaEndpoint", "state=failed", FailureDiagnostics.sanitized(cause))
        throw cause
    }

    private class RuntimeClock : Clock {
        override fun epochMs() = System.currentTimeMillis()
        override fun monotonicMs() = SystemClock.elapsedRealtime()
        override suspend fun sleep(durationMs: Long) { delay(durationMs) }
    }

    companion object {
        private fun androidInputs(context: Context, logger: Logger, scope: CoroutineScope): Inputs {
            val clock = RuntimeClock()
            val events = EventBuffer(clock)
            val hub = AccessibilityHub(logger, clock, Dispatchers.Main.immediate, EventFeed(events, logger),
                AndroidActivityResolver({ context.packageManager }, logger))
            LinkAccessibilityService.hub = hub
            val power = AndroidPowerState(checkNotNull(context.getSystemService(PowerManager::class.java)), logger)
            val keyguard = checkNotNull(context.getSystemService(KeyguardManager::class.java))
            val guard = AndroidKeyguardState(keyguard, logger)
            val trampoline = TrampolineLauncher(context, logger)
            TrampolineActivity.jobs = TrampolineJobs(keyguard,
                checkNotNull(context.getSystemService(ClipboardManager::class.java)), scope, trampoline::complete, logger)
            val settings = AppSettings(context.getSharedPreferences("settings", Context.MODE_PRIVATE), logger)
            val sessions = SessionFilePolicy(FileSessionStore(context.noBackupFilesDir, logger), KeystoreCipher(logger), clock, logger)
            val client = createRelayClient()
            val notifications = NotificationHub(context, clock, logger)
            LinkNotificationListener.hub = notifications
            val foreground = MicrophoneForeground(ServiceNotification(context, logger), logger)
            val callState = AndroidCallState(context, logger)
            val routing = AndroidAudioRouting(checkNotNull(context.getSystemService(AudioManager::class.java)),
                hub, callState, clock, logger)
            val probe = ProbeRunner(AndroidAudioProbe(clock, logger), AndroidTonePlayer(context, clock, logger),
                routing, PrivateAudioSamples(context.cacheDir, logger), foreground, clock, logger)
            return Inputs(
                hub, hub, DisplayWaker(power, guard, trampoline, clock, logger),
                AccessibilityScreenshotSource(hub, ImageEncoder(logger), logger),
                { windows -> AndroidDeviceState(checkNotNull(context.getSystemService(BatteryManager::class.java)),
                    power, hub, windows, hub::activityForPackage, logger) },
                AndroidDeviceIdentity(context, logger),
                AndroidPermissionState(context, ComponentName(context, LinkAccessibilityService::class.java), logger, notifications),
                OkHttpRelaySocketFactory(client, logger),
                AndroidNetworkMonitor(checkNotNull(context.getSystemService(ConnectivityManager::class.java)), logger),
                sessions, settings, clock,
                { provider -> RelayMediaClient(client, provider, logger) }, hub::activityForPackage,
                { openStatus(context, logger) }, hub::setConnectedCallback,
                { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() },
                gestures = GestureDispatcher(hub, logger), globals = AccessibilityGlobalKeys(hub, logger),
                volume = AndroidVolumeControl(checkNotNull(context.getSystemService(AudioManager::class.java)), logger),
                metrics = AndroidScreenMetrics(checkNotNull(context.getSystemService(DisplayManager::class.java)), logger),
                power = power, events = events,
                apps = AndroidAppControl(context, logger), clipboard = AndroidClipboard(context, trampoline, logger),
                mediaKeys = AndroidMediaKeys(context, logger), location = AndroidLocationSource(context, logger),
                contacts = AndroidContactsSource(context, logger), calls = AndroidCallControl(context, logger),
                sms = AndroidSmsSender(context, logger), outbound = OutboundFileStore(context, logger),
                shares = AndroidShareLauncher(context, logger),
                notifications = notifications, files = AndroidFileAccess(context, logger), probe = probe,
                serviceAvailability = foreground::available,
            )
        }

        private suspend fun openStatus(context: Context, logger: Logger): PortResult<Unit> = try {
            withTimeout(5_000) { withContext(Dispatchers.Main.immediate) {
                val intent = Intent(context, StatusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                checkNotNull(intent.resolveActivity(context.packageManager)) { "The status screen is unavailable." }
                context.startActivity(intent)
                PortResult.Success(Unit)
            } }
        } catch (cause: TimeoutCancellationException) {
            openFailure(logger, cause, FailureKind.TIMED_OUT)
        } catch (cause: CancellationException) { throw cause }
        catch (cause: Exception) { openFailure(logger, cause, FailureKind.PLATFORM) }

        private fun openFailure(logger: Logger, cause: Exception, kind: FailureKind): PortResult.Failure {
            val safe = FailureDiagnostics.sanitized(cause)
            logger.error("LinkRuntime", "openStatus", "state=failed", safe)
            return PortResult.Failure(PortFailure(kind, safe))
        }
    }
}
