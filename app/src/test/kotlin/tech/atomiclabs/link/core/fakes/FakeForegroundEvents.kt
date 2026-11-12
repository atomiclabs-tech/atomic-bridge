package tech.atomiclabs.link.core.fakes

import tech.atomiclabs.link.core.port.ForegroundEvents

class FakeForegroundEvents : ForegroundEvents {
    val windowStateTimes = mutableMapOf<String, Long>()
    override fun lastWindowStateAt(packageName: String): Long? = windowStateTimes[packageName]
}
