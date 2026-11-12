// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

/** Monotonic time of the last window-state event per package, from the accessibility service. */
fun interface ForegroundEvents {
    fun lastWindowStateAt(packageName: String): Long?
}
