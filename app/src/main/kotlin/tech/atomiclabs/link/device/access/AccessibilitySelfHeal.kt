package tech.atomiclabs.link.device.access

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import tech.atomiclabs.link.core.log.Logger

/**
 * Turns Atomic's screen control back on when the app starts and finds it switched off.
 *
 * Android turns an accessibility service OFF when its app is force-stopped, and restarts the app
 * moments later. On every process start (unless the in-app kill switch is on), if the one-time adb
 * grant of WRITE_SECURE_SETTINGS is present, the service is re-added to the enabled list so the
 * user never has to re-grant it.
 * Without the grant this is a no-op and the setup screen's manual path still applies.
 */
class AccessibilitySelfHeal(
    private val logger: Logger,
    private val granted: () -> Boolean,
    private val readEnabled: () -> String?,
    private val writeEnabled: (String) -> Boolean,
    private val writeMaster: () -> Boolean,
    private val component: String,
) {
    enum class Result { ALREADY_ON, HEALED, NO_GRANT, FAILED }

    fun isOn(): Boolean = isEnabled(readEnabled(), component)

    fun run(): Result {
        val current = readEnabled()
        if (isEnabled(current, component)) return Result.ALREADY_ON
        if (!granted()) {
            logger.info("AccessibilitySelfHeal", "run", "state=off grant=missing")
            return Result.NO_GRANT
        }
        return try {
            val ok = writeEnabled(withComponent(current, component)) && writeMaster()
            logger.info("AccessibilitySelfHeal", "run", if (ok) "state=healed" else "state=write_refused")
            if (ok) Result.HEALED else Result.FAILED
        } catch (cause: Exception) {
            logger.warn("AccessibilitySelfHeal", "run", "state=failed", cause)
            Result.FAILED
        }
    }

    /** Kill switch: take Atomic out of the enabled list (needs the same grant). */
    fun disable(): Boolean {
        val current = readEnabled()
        if (!isEnabled(current, component)) return true
        if (!granted()) {
            logger.info("AccessibilitySelfHeal", "disable", "state=on grant=missing")
            return false
        }
        return try {
            val ok = writeEnabled(withoutComponent(current, component))
            logger.info("AccessibilitySelfHeal", "disable", if (ok) "state=off" else "state=write_refused")
            ok
        } catch (cause: Exception) {
            logger.warn("AccessibilitySelfHeal", "disable", "state=failed", cause)
            false
        }
    }

    companion object {
        internal fun withoutComponent(list: String?, component: String) =
            entries(list).filterNot { it.equals(component, ignoreCase = true) || expand(it).equals(component, ignoreCase = true) }
                .joinToString(":")

        internal fun entries(list: String?) = list.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }

        internal fun isEnabled(list: String?, component: String) =
            entries(list).any { it.equals(component, ignoreCase = true) || expand(it).equals(component, ignoreCase = true) }

        internal fun withComponent(list: String?, component: String) = (entries(list) + component).joinToString(":")

        /** Android may store "pkg/.Cls"; compare against the fully qualified "pkg/pkg.Cls". */
        private fun expand(entry: String): String {
            val pkg = entry.substringBefore('/')
            val cls = entry.substringAfter('/', "")
            return if (cls.startsWith(".")) "$pkg/$pkg$cls" else entry
        }

        fun forContext(context: Context, logger: Logger): AccessibilitySelfHeal {
            val resolver = context.contentResolver
            val name = ComponentName(context, LinkAccessibilityService::class.java).flattenToString()
            return AccessibilitySelfHeal(
                logger = logger,
                granted = {
                    context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                        PackageManager.PERMISSION_GRANTED
                },
                readEnabled = { Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) },
                writeEnabled = { Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, it) },
                writeMaster = { Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1) },
                component = name,
            )
        }
    }
}
