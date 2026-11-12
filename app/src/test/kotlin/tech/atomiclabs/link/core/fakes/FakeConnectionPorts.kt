package tech.atomiclabs.link.core.fakes

import kotlinx.coroutines.flow.MutableSharedFlow
import tech.atomiclabs.link.core.port.*

class FakeConnectionControl : ConnectionControl {
    var connects = 0; var relayUrlApplications = 0
    val suppliedCodes = mutableListOf<String>()
    var connectAction: suspend () -> Unit = {}
    var pairAction: suspend (String) -> PortResult<Unit> = { PortResult.Success(Unit) }
    var applyRelayUrlAction: suspend () -> PortResult<Unit> = { PortResult.Success(Unit) }
    override suspend fun connect(): PortResult<Unit> { connects++; connectAction(); return PortResult.Success(Unit) }
    override suspend fun pair(code: String): PortResult<Unit> { suppliedCodes += code; return pairAction(code) }
    override suspend fun applyRelayUrl(): PortResult<Unit> { relayUrlApplications++; return applyRelayUrlAction() }
}

class FakeRelaySocket : RelaySocket {
    override val events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 16)
    val sendAttempts = mutableListOf<String>()
    val closes = mutableListOf<Pair<Int, String>>()
    var sendResult: PortResult<Unit> = PortResult.Success(Unit)
    var closeResult: PortResult<Unit> = PortResult.Success(Unit)
    override suspend fun send(frame: String): PortResult<Unit> {
        sendAttempts += frame
        return sendResult
    }
    override suspend fun close(code: Int, reason: String): PortResult<Unit> {
        closes += code to reason
        return closeResult
    }
}

class FakeSessionStore : SessionStore {
    var value: ReadValue<StoredSession> = ReadValue.Absent
    var saveResult: PortResult<Unit> = PortResult.Success(Unit)
    val saveAttempts = mutableListOf<StoredSession>()
    override suspend fun read() = value
    override suspend fun save(session: StoredSession): PortResult<Unit> {
        saveAttempts += session
        if (saveResult is PortResult.Success) value = ReadValue.Present(session)
        return saveResult
    }
}

class FakeSettingsStore : SettingsStore {
    var url: ReadValue<String> = ReadValue.Absent
    var id: ReadValue<String> = ReadValue.Absent
    var writeResult: PortResult<Unit> = PortResult.Success(Unit)
    val savedRelayUrls = mutableListOf<String>()
    override suspend fun relayUrl() = url
    override suspend fun deviceId() = id
    override suspend fun saveRelayUrl(url: String): PortResult<Unit> {
        savedRelayUrls += url
        if (writeResult is PortResult.Success) this.url = ReadValue.Present(url)
        return writeResult
    }
    override suspend fun saveDeviceId(id: String): PortResult<Unit> {
        if (writeResult is PortResult.Success) this.id = ReadValue.Present(id)
        return writeResult
    }
}
