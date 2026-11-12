// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

/** Validates event classes and resolves a declared Home/launch entry when no event class is available. */
interface ActivityResolver {
    fun isActivity(packageName: String, className: String): Boolean
    fun resolveActivity(packageName: String, className: String? = null): String?
}
