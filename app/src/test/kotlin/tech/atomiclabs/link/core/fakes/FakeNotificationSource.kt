package tech.atomiclabs.link.core.fakes

import tech.atomiclabs.link.core.port.*

class FakeNotificationSource : NotificationSource {
    var enabled: PortResult<Boolean> = PortResult.Success(false)
    var registrationFailure: PortFailure? = null
    var closeResult: PortResult<Unit> = PortResult.Success(Unit)
    private val listeners = mutableListOf<(NotificationEvent) -> Unit>()
    override suspend fun isEnabled() = enabled
    override suspend fun register(listener: (NotificationEvent) -> Unit): PortResult<NotificationSubscription> {
        registrationFailure?.let { return PortResult.Failure(it) }
        listeners += listener
        return PortResult.Success(NotificationSubscription {
            if (closeResult is PortResult.Success) listeners -= listener
            closeResult
        })
    }
    fun emit(event: NotificationEvent) { listeners.toList().forEach { it(event) } }
}
