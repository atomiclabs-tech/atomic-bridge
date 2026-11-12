package tech.atomiclabs.link.core.fakes

import tech.atomiclabs.link.core.command.DisplayGate
import tech.atomiclabs.link.core.command.DisplayGateResult

class FakeDisplayGate : DisplayGate {
    var result: DisplayGateResult = DisplayGateResult.Ready
    var beforePrepare: suspend () -> Unit = {}
    var calls = 0
    override suspend fun prepare(): DisplayGateResult {
        calls++
        beforePrepare()
        return result
    }
}
