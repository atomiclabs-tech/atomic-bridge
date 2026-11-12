package tech.atomiclabs.link.core.fakes

import tech.atomiclabs.link.core.log.Logger

class FakeLogger : Logger {
    data class Entry(
        val level: String,
        val component: String,
        val operation: String,
        val context: String,
        val cause: Throwable?,
    )

    val entries = mutableListOf<Entry>()
    override fun debug(component: String, operation: String, context: String) {
        entries += Entry("debug", component, operation, context, null)
    }
    override fun info(component: String, operation: String, context: String) {
        entries += Entry("info", component, operation, context, null)
    }
    override fun warn(component: String, operation: String, context: String, cause: Throwable?) {
        entries += Entry("warn", component, operation, context, cause)
    }
    override fun error(component: String, operation: String, context: String, cause: Throwable) {
        entries += Entry("error", component, operation, context, cause)
    }
}
