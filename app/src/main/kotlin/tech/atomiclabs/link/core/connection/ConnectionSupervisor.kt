package tech.atomiclabs.link.core.connection

import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import tech.atomiclabs.link.core.command.CommandRouter
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.Envelope
import tech.atomiclabs.link.core.protocol.EnvelopeCodec
import tech.atomiclabs.link.core.status.StatusReporter

/** Process-lifetime connection loop. Runtime launches run() once; user Retry is connect(). */
class ConnectionSupervisor(
    private val sockets: RelaySocketFactory,
    private val network: NetworkMonitor,
    private val sessions: SessionStore,
    private val settings: SettingsStore,
    private val identity: DeviceIdentity,
    private val clock: Clock,
    private val router: CommandRouter,
    private val reporter: StatusReporter,
    private val logger: Logger,
    private val newId: () -> String,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val streams: List<OutboundStream> = emptyList(),
) : ConnectionControl, SessionTokenProvider, StatusSource {
    private enum class Wait { NONE, ORDINARY, NETWORK, RATE_LIMIT, REAUTH }
    private sealed interface Event {
        data object Connect : Event
        data class Pair(val code: String) : Event { override fun toString() = "Pair(private code redacted)" }
        data object RelayUrlChanged : Event
        data class Network(val result: PortResult<Boolean>) : Event
        data class Ready(val generation: Long, val socket: RelaySocket) : Event
        data class Inbound(val generation: Long, val event: SocketEvent) : Event
        data class Failed(val generation: Long, val cause: Throwable,
            val origin: ConnectionFailureOrigin = ConnectionFailureOrigin.INTERNAL) : Event
        data class Watchdog(val generation: Long, val phase: ConnectionPhase) : Event
        data class Retry(val serial: Long) : Event
        data class Stable(val generation: Long) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val running = Mutex()
    private val mutableState = MutableStateFlow(ConnectionState())
    val states: StateFlow<ConnectionState> = mutableState.asStateFlow()
    override val changes: Flow<PortResult<StatusSnapshot>> = states.map { current() }
    private val codec = EnvelopeCodec(logger)
    private val auth = AuthFrames(newId)
    private val system = SystemChannel(clock, logger, newId)
    private lateinit var scope: CoroutineScope
    private var session: StoredSession? = null
    private var sessionProblem: PortFailure? = null
    private var loaded = false
    private var pairing: String? = null
    private var device: DeviceDescription? = null
    private var deviceId: String? = null
    private var endpoint: RelayUrl? = null
    private var socket: RelaySocket? = null
    private var generation = 0L
    private var retrySerial = 0L
    private var wait = Wait.NONE
    private var reauth = false
    private var reauthRetries = 0
    private var onlineSince: Long? = null
    private var attemptJob: Job? = null
    private var reportJob: Job? = null
    private val outboundStreams = OutboundStreams(logger)
    private var watchdog: Job? = null
    private var retryJob: Job? = null
    private var stableJob: Job? = null
    private var sentSecrets: List<String> = emptyList()

    override suspend fun connect(): PortResult<Unit> { events.send(Event.Connect); return PortResult.Success(Unit) }
    override suspend fun pair(code: String): PortResult<Unit> { events.send(Event.Pair(code)); return PortResult.Success(Unit) }
    override suspend fun applyRelayUrl(): PortResult<Unit> { events.send(Event.RelayUrlChanged); return PortResult.Success(Unit) }
    override suspend fun token(): ReadValue<String> = session?.let { ReadValue.Present(it.sessionToken) }
        ?: sessionProblem?.let { ReadValue.Unreadable(it) } ?: ReadValue.Absent

    override suspend fun current(): PortResult<StatusSnapshot> {
        val info = device ?: return PortResult.Failure(mutableState.value.failure
            ?: PortFailure(FailureKind.UNAVAILABLE, IllegalStateException("Device identity is not ready.")))
        val state = mutableState.value
        return PortResult.Success(StatusSnapshot(
            if (state.rePairNeeded) ConnectionPhase.REAUTH_REQUIRED else state.phase,
            endpoint?.httpBase, deviceId, grant(), info.appVersion, state.failure,
            state.failureOrigin, state.retryScheduled,
        ))
    }

    suspend fun run(): Unit = coroutineScope {
        check(running.tryLock()) { "The connection loop is already running." }
        scope = this
        val networkJob = launch {
            try { network.changes.collect { events.send(Event.Network(it)) } }
            catch (cause: Throwable) {
                if (cause is CancellationException) throw cause
                logger.error("ConnectionSupervisor", "observeNetwork", "state=observing", FailureDiagnostics.sanitized(cause))
            }
        }
        try {
            initialize(connectUnpaired = false)
            while (isActive) {
                val event = events.receive()
                try { handle(event) }
                catch (cause: Throwable) {
                    ensureNotCancelled(cause)
                    failed(cause, if (states.value.phase == ConnectionPhase.AUTHENTICATING)
                        ConnectionFailureOrigin.AUTHENTICATION else ConnectionFailureOrigin.INTERNAL)
                }
            }
        } finally {
            networkJob.cancel()
            retryJob?.cancel()
            withContext(NonCancellable) { closeAttempt() }
            running.unlock()
        }
    }

    private suspend fun initialize(connectUnpaired: Boolean) {
        var origin = ConnectionFailureOrigin.INTERNAL
        try {
            prepareIdentity()
            origin = ConnectionFailureOrigin.SESSION
            val readable = if (!loaded && pairing == null) loadSession() else sessionProblem == null || pairing != null
            endpoint = null
            origin = ConnectionFailureOrigin.CONFIGURATION
            endpoint = configureEndpoint()
            if (!readable) publish(ConnectionPhase.SESSION_UNREADABLE, sessionProblem, ConnectionFailureOrigin.SESSION)
            else if (connectUnpaired || session != null) beginAttempt()
            else publish(ConnectionPhase.UNPAIRED)
        } catch (cause: Throwable) {
            ensureNotCancelled(cause)
            closeAttempt(); cancelWait()
            val failure = logFailure("initialize", cause)
            val unreadable = sessionProblem?.takeIf { pairing == null }
            if (unreadable != null) {
                val combined = IllegalStateException("Session and connection preparation failed.", unreadable.cause)
                combined.addSuppressed(failure.cause)
                publish(ConnectionPhase.SESSION_UNREADABLE, unreadable.copy(cause = combined), ConnectionFailureOrigin.SESSION)
            } else publish(if (session == null) ConnectionPhase.UNPAIRED else ConnectionPhase.BACKOFF, failure,
                (cause as? ConnectionFailure)?.origin ?: origin)
        }
    }

    private suspend fun loadSession(): Boolean {
        when (val value = withTimeout(60_000) { sessions.read() }) {
            ReadValue.Absent -> { loaded = true; sessionProblem = null; pairing = null; return true }
            is ReadValue.Present -> { loaded = true; sessionProblem = null; session = value.value; return true }
            is ReadValue.Unreadable -> {
                loaded = true
                sessionProblem = safe(value.failure)
                logger.error("ConnectionSupervisor", "readSession", "state=loading", sessionProblem!!.cause)
            }
        }
        publish(ConnectionPhase.SESSION_UNREADABLE, sessionProblem, ConnectionFailureOrigin.SESSION)
        return false
    }

    private suspend fun prepareIdentity() {
        device = checked("identity") { identity.read() }
        deviceId = setting("deviceId") { settings.deviceId() } ?: newId().also {
            checked("saveDeviceId", origin = ConnectionFailureOrigin.CONFIGURATION) { settings.saveDeviceId(it) }
        }
        require(Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}").matches(deviceId!!)) {
            "The saved device id is invalid."
        }
    }

    private suspend fun configureEndpoint(): RelayUrl {
        val rawUrl = setting("relayUrl") { settings.relayUrl() }
            ?: throw IllegalStateException("The relay URL is missing.")
        return RelayUrl.parse(rawUrl)
    }

    private suspend fun relayUrlChanged() {
        val saved = try { configureEndpoint() }
        catch (cause: Throwable) {
            ensureNotCancelled(cause)
            publish(ConnectionPhase.BACKOFF, logFailure("applyRelayUrl", cause), ConnectionFailureOrigin.CONFIGURATION)
            closeAttempt(); cancelWait()
            return
        }
        if (saved.httpBase == endpoint?.httpBase) return
        val reconnect = session != null || pairing != null && states.value.phase in setOf(
            ConnectionPhase.CONNECTING, ConnectionPhase.AUTHENTICATING, ConnectionPhase.BACKOFF,
        )
        endpoint = saved
        closeAttempt(); cancelWait(); reauth = false; reauthRetries = 0
        if (reconnect) beginAttempt() else publish(ConnectionPhase.UNPAIRED)
    }

    private suspend fun handle(event: Event) {
        when (event) {
            Event.RelayUrlChanged -> relayUrlChanged()
            Event.Connect -> {
                if (session == null && pairing == null) { publish(ConnectionPhase.UNPAIRED); return }
                if (mutableState.value.phase == ConnectionPhase.ONLINE) return
                reauth = false; reauthRetries = 0
                initialize(connectUnpaired = true)
            }
            is Event.Pair -> {
                val code = event.code.trim().uppercase(Locale.ROOT)
                if (code.length != 6 || code.any { it !in 'A'..'Z' && it !in '0'..'9' }) {
                    val cause = IllegalArgumentException("The pairing code must be six letters or digits.")
                    logger.error("ConnectionSupervisor", "pair", "state=invalid_code", FailureDiagnostics.sanitized(cause))
                    publish(ConnectionPhase.UNPAIRED, PortFailure(FailureKind.INVALID_DATA, cause), ConnectionFailureOrigin.CONFIGURATION)
                    return
                }
                closeAttempt(); cancelWait(); reauth = false; reauthRetries = 0
                pairing = code; initialize(connectUnpaired = true)
            }
            is Event.Network -> when (val result = event.result) {
                is PortResult.Failure -> logger.error("ConnectionSupervisor", "networkAvailable", "state=${states.value.phase}",
                    FailureDiagnostics.sanitized(result.failure.cause))
                is PortResult.Success -> if (result.value && wait == Wait.ORDINARY && states.value.phase == ConnectionPhase.BACKOFF) {
                    schedule(Wait.NETWORK, ReconnectPolicy.NETWORK_WAIT_MS)
                }
            }
            is Event.Ready -> if (event.generation == generation) socket = event.socket else closeSocket(event.socket)
            is Event.Inbound -> if (event.generation == generation) when (val inbound = event.event) {
                SocketEvent.Opened -> opened()
                is SocketEvent.Text -> codec.decode(inbound.frame)?.let { frame(it) }
                is SocketEvent.Closed -> failed(IllegalStateException("The relay socket closed (code ${inbound.code})."),
                    ConnectionFailureOrigin.TRANSPORT)
                is SocketEvent.Failed -> failed(ConnectionFailure("socket", safe(inbound.failure),
                    if (inbound.failure.kind == FailureKind.INVALID_DATA) ConnectionFailureOrigin.INTERNAL
                    else ConnectionFailureOrigin.TRANSPORT))
            }
            is Event.Failed -> if (event.generation == generation) failed(event.cause, event.origin)
            is Event.Watchdog -> if (event.generation == generation && states.value.phase == event.phase) {
                failed(IllegalStateException("The connection made no progress for 30 seconds."),
                    if (event.phase == ConnectionPhase.CONNECTING) ConnectionFailureOrigin.TRANSPORT
                    else ConnectionFailureOrigin.AUTHENTICATION)
            }
            is Event.Retry -> if (event.serial == retrySerial) {
                if (reauth) reauthRetries++
                beginAttempt()
            }
            is Event.Stable -> if (event.generation == generation && states.value.phase == ConnectionPhase.ONLINE) {
                policy.onlineFor(clock.monotonicMs() - onlineSince!!); publish(ConnectionPhase.ONLINE)
            }
        }
    }

    private suspend fun beginAttempt() {
        val previous = states.value.takeIf {
            it.phase == ConnectionPhase.BACKOFF || it.phase == ConnectionPhase.REAUTH_REQUIRED
        }
        closeAttempt(); cancelWait(); generation++
        val attempt = generation
        val url = checkNotNull(endpoint) { "The relay URL is not configured." }
        publish(ConnectionPhase.CONNECTING, previous?.failure, previous?.failureOrigin ?: ConnectionFailureOrigin.NONE)
        watch(ConnectionPhase.CONNECTING)
        attemptJob = scope.launch {
            try {
                val connected = checked("connect", 30_000, ConnectionFailureOrigin.TRANSPORT) { sockets.connect(url.websocketUrl) }
                events.send(Event.Ready(attempt, connected))
                connected.events.collect { events.send(Event.Inbound(attempt, it)) }
                events.send(Event.Failed(attempt, IllegalStateException("The socket event stream ended."), ConnectionFailureOrigin.TRANSPORT))
            } catch (cause: Throwable) {
                if (cause is CancellationException && !currentCoroutineContext().isActive) throw cause
                events.send(Event.Failed(attempt, cause, ConnectionFailureOrigin.TRANSPORT))
            }
        }
    }

    private suspend fun opened() {
        if (states.value.phase != ConnectionPhase.CONNECTING) return
        publish(ConnectionPhase.AUTHENTICATING); watch(ConnectionPhase.AUTHENTICATING)
        sentSecrets = listOfNotNull(pairing, session?.sessionToken, session?.refreshToken)
        send(auth.auth(checkNotNull(deviceId), checkNotNull(device), session, pairing))
    }

    private suspend fun frame(envelope: Envelope) {
        if (envelope.channel == "system" && states.value.phase == ConnectionPhase.AUTHENTICATING) {
            if (envelope.type == "auth.ok") { accepted(envelope); return }
            if (envelope.type == "auth.fail") { rejected(envelope); return }
        }
        val online = states.value.phase == ConnectionPhase.ONLINE
        system.handle(envelope, online, sentSecrets + listOfNotNull(session?.sessionToken, session?.refreshToken, pairing))
            ?.let { send(it) }
        if (!online) return
        val command = codec.decodeCommand(envelope) ?: return
        val attempt = generation
        val commandSocket = checkNotNull(socket)
        scope.launch {
            try {
                val response = router.dispatch(command)
                if (attempt == generation && states.value.phase == ConnectionPhase.ONLINE) {
                    send(codec.responseEnvelope(response, newId()), commandSocket)
                } else logger.warn("ConnectionSupervisor", "response", FailureDiagnostics.requestContext(command.requestId, null))
            } catch (cause: Throwable) {
                if (cause is CancellationException && !currentCoroutineContext().isActive) throw cause
                logger.error("ConnectionSupervisor", "sendResponse", FailureDiagnostics.requestContext(command.requestId, null),
                    FailureDiagnostics.sanitized(cause))
                events.send(Event.Failed(attempt, cause))
            }
        }
    }

    private suspend fun accepted(envelope: Envelope) {
        val adopted = auth.accepted(envelope.payload, session, clock.epochMs())
        checked("saveSession", origin = ConnectionFailureOrigin.SESSION) { sessions.save(adopted) }
        session = adopted; sessionProblem = null; loaded = true; pairing = null
        reauth = false; reauthRetries = 0; watchdog?.cancel(); onlineSince = clock.monotonicMs()
        publish(ConnectionPhase.ONLINE)
        val expiry = adopted.grants["bridge"]
        if (!adopted.grants.containsKey("bridge") || expiry != null && expiry <= clock.epochMs() / 1_000) {
            logger.warn("ConnectionSupervisor", "grant", "state=online bridge grant expired")
        }
        val attempt = generation
        val authenticatedSocket = checkNotNull(socket)
        stableJob = scope.launch { clock.sleep(30_000); events.send(Event.Stable(attempt)) }
        reportJob = scope.launch {
            try { reporter.run(checkNotNull(deviceId)) { outbound ->
                check(attempt == generation) { "The status connection ended." }
                send(outbound, authenticatedSocket); PortResult.Success(Unit)
            } }
            catch (cause: Throwable) {
                if (cause is CancellationException && !currentCoroutineContext().isActive) throw cause
                events.send(Event.Failed(attempt, cause))
            }
        }
        outboundStreams.start(scope, streams, attempt,
            { attempt == generation && states.value.phase == ConnectionPhase.ONLINE && socket === authenticatedSocket },
            { outbound -> send(outbound, authenticatedSocket) })
    }

    private suspend fun rejected(envelope: Envelope) {
        val timeout = auth.timedOut(envelope.payload)
        val cause = IllegalStateException(if (timeout) "Relay authentication timed out." else "Relay authentication was rejected.")
        if (timeout) { reauth = false; failed(cause, ConnectionFailureOrigin.AUTHENTICATION); return }
        logFailure("authenticate", cause)
        closeAttempt()
        if (pairing != null) {
            pairing = null; cancelWait(); publish(ConnectionPhase.PAIRING_REJECTED)
        } else {
            reauth = true; publish(ConnectionPhase.REAUTH_REQUIRED)
            if (reauthRetries < ReconnectPolicy.MAX_AUTH_RETRIES) schedule(Wait.REAUTH, ReconnectPolicy.REAUTH_WAIT_MS)
            else {
                cancelWait()
                logger.error("ConnectionSupervisor", "authRetriesExhausted", "state=REAUTH_REQUIRED", cause)
            }
        }
    }

    private suspend fun failed(cause: Throwable, source: ConnectionFailureOrigin = ConnectionFailureOrigin.INTERNAL) {
        onlineSince?.let { policy.onlineFor(clock.monotonicMs() - it) }
        val failure = logFailure("connectionLoop", cause)
        val origin = (cause as? ConnectionFailure)?.origin ?: source
        val delay = policy.failed()
        closeAttempt()
        if (reauth) {
            publish(ConnectionPhase.REAUTH_REQUIRED, failure, origin)
            if (reauthRetries < ReconnectPolicy.MAX_AUTH_RETRIES) schedule(Wait.REAUTH, ReconnectPolicy.REAUTH_WAIT_MS)
            else cancelWait()
        } else {
            publish(ConnectionPhase.BACKOFF, failure, origin)
            if (failure.httpStatus == 429) schedule(Wait.RATE_LIMIT, ReconnectPolicy.RATE_LIMIT_MS)
            else schedule(Wait.ORDINARY, delay)
        }
    }

    private fun schedule(kind: Wait, delayMs: Long) {
        cancelWait(); wait = kind
        val serial = retrySerial
        retryJob = scope.launch { clock.sleep(delayMs); events.send(Event.Retry(serial)) }
        if (states.value.failure != null) mutableState.update { it.copy(retryScheduled = true) }
    }

    private fun cancelWait() {
        retrySerial++; retryJob?.cancel(); wait = Wait.NONE
        mutableState.update { it.copy(retryScheduled = false) }
    }
    private fun watch(phase: ConnectionPhase) {
        watchdog?.cancel()
        val attempt = generation
        watchdog = scope.launch { clock.sleep(30_000); events.send(Event.Watchdog(attempt, phase)) }
    }

    private suspend fun closeAttempt() {
        generation++
        attemptJob?.cancel(); reportJob?.cancel(); watchdog?.cancel(); stableJob?.cancel()
        outboundStreams.stop()
        onlineSince = null
        val old = socket
        socket = null
        if (old != null) closeSocket(old)
    }

    private suspend fun closeSocket(old: RelaySocket) {
        try {
            when (val result = withTimeout(5_000) { old.close(1000, "Connection attempt ended.") }) {
                is PortResult.Success -> Unit
                is PortResult.Failure -> logger.error("ConnectionSupervisor", "close", "state=${states.value.phase}",
                    FailureDiagnostics.sanitized(result.failure.cause))
            }
        } catch (cause: Exception) {
            if (cause is CancellationException && !currentCoroutineContext().isActive) throw cause
            logger.error("ConnectionSupervisor", "close", "state=${states.value.phase}", FailureDiagnostics.sanitized(cause))
        }
    }

    private suspend fun send(envelope: Envelope, current: RelaySocket = checkNotNull(socket) { "The relay socket is unavailable." }) {
        checked("send", origin = ConnectionFailureOrigin.TRANSPORT) { current.send(codec.encode(envelope)) }
    }

    private suspend fun <T> checked(operation: String, timeoutMs: Long = 10_000,
        origin: ConnectionFailureOrigin = ConnectionFailureOrigin.INTERNAL, block: suspend () -> PortResult<T>): T =
        try {
            withTimeout(timeoutMs) {
                when (val result = block()) {
                    is PortResult.Success -> result.value
                    is PortResult.Failure -> throw ConnectionFailure(operation, safe(result.failure), origin)
                }
            }
        } catch (cause: TimeoutCancellationException) {
            ensureNotCancelled(cause)
            throw ConnectionFailure(operation, PortFailure(FailureKind.IO, FailureDiagnostics.sanitized(cause)), origin)
        }

    private suspend fun setting(operation: String, read: suspend () -> ReadValue<String>): String? = withTimeout(10_000) {
        when (val result = read()) {
            ReadValue.Absent -> null
            is ReadValue.Present -> result.value
            is ReadValue.Unreadable -> throw ConnectionFailure(operation, safe(result.failure), ConnectionFailureOrigin.CONFIGURATION)
        }
    }

    private fun publish(phase: ConnectionPhase, failure: PortFailure? = null,
        origin: ConnectionFailureOrigin = ConnectionFailureOrigin.NONE) {
        mutableState.value = ConnectionState(phase, policy.attempt, reauthRetries, reauth, failure,
            if (failure == null) ConnectionFailureOrigin.NONE else origin)
    }

    private fun logFailure(operation: String, cause: Throwable): PortFailure {
        val failure = if (cause is ConnectionFailure) cause.failure else PortFailure(FailureKind.IO, FailureDiagnostics.sanitized(cause))
        logger.error("ConnectionSupervisor", if (cause is ConnectionFailure) cause.operation else operation,
            "state=${states.value.phase} attempt=${policy.attempt}", failure.cause)
        return failure
    }

    private fun safe(failure: PortFailure): PortFailure = failure.copy(cause = FailureDiagnostics.sanitized(failure.cause))
    private suspend fun ensureNotCancelled(cause: Throwable) {
        if (cause is CancellationException && !currentCoroutineContext().isActive) throw cause
    }
    private fun grant(): GrantExpiry = session?.let {
        if (!it.grants.containsKey("bridge")) GrantExpiry.Missing
        else it.grants["bridge"]?.let(GrantExpiry::At) ?: GrantExpiry.Never
    } ?: GrantExpiry.Missing
}
