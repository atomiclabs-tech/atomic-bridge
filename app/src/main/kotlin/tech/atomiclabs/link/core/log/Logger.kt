// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.log

/** Context contains non-sensitive request ids, paths or states, never user content or credentials. */
interface Logger {
    fun debug(component: String, operation: String, context: String)
    fun info(component: String, operation: String, context: String)
    fun warn(component: String, operation: String, context: String, cause: Throwable? = null)
    fun error(component: String, operation: String, context: String, cause: Throwable)
}
