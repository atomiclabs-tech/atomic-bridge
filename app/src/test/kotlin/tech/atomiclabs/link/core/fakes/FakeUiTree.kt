package tech.atomiclabs.link.core.fakes

import tech.atomiclabs.link.core.port.*

class FakeUiTree : UiTreeSource {
    var connection: PortResult<Boolean> = PortResult.Success(true)
    var windowResult: PortResult<List<UiWindow>> = PortResult.Success(emptyList())
    var root: ReadValue<UiNode> = ReadValue.Absent
    var focus: ReadValue<UiNode> = ReadValue.Absent
    var beforeConnectionRead: suspend () -> Unit = {}
    var connectionReads = 0
    override suspend fun isConnected(): PortResult<Boolean> {
        connectionReads++
        beforeConnectionRead()
        return connection
    }
    override suspend fun windows() = windowResult
    override suspend fun activeRoot() = root
    override suspend fun inputFocus() = focus
}

class FakeUiNode(var properties: UiNodeProperties) : UiNode {
    val nodes = mutableListOf<UiNode>()
    val actions = mutableListOf<NodeAction>()
    var readFailure: PortFailure? = null
    var childFailure: PortFailure? = null
    var parentNode: ReadValue<UiNode> = ReadValue.Absent
    var nextFocus: ReadValue<UiNode> = ReadValue.Absent
    var actionResult: PortResult<Unit> = PortResult.Success(Unit)
    override suspend fun read(): PortResult<UiNodeProperties> =
        readFailure?.let { PortResult.Failure(it) } ?: PortResult.Success(properties)
    override suspend fun children(): PortResult<List<UiNode>> =
        childFailure?.let { PortResult.Failure(it) } ?: PortResult.Success(nodes.toList())
    override suspend fun parent() = parentNode
    override suspend fun nextInputFocus() = nextFocus
    override suspend fun perform(action: NodeAction): PortResult<Unit> {
        actions += action
        return actionResult
    }
}
