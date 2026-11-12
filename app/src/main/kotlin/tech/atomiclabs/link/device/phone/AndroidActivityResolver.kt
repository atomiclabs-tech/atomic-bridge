package tech.atomiclabs.link.device.phone

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.ActivityResolver

class AndroidActivityResolver(
    private val packages: () -> PackageManager,
    private val logger: Logger,
) : ActivityResolver {
    @Suppress("DEPRECATION")
    override fun isActivity(packageName: String, className: String): Boolean = try {
        packages().getActivityInfo(ComponentName(packageName, qualified(packageName, className)), 0)
        true
    } catch (cause: PackageManager.NameNotFoundException) {
        logger.debug("AndroidActivityResolver", "isActivity", "package=$packageName state=not_activity")
        false
    } catch (cause: Exception) {
        logger.error("AndroidActivityResolver", "isActivity", "package=$packageName state=resolve_failed",
            FailureDiagnostics.sanitized(cause))
        false
    }

    @Suppress("DEPRECATION")
    override fun resolveActivity(packageName: String, className: String?): String? = try {
        val manager = packages()
        val candidate = className ?: entryClass(manager, packageName)
        if (candidate == null) {
            logger.debug("AndroidActivityResolver", "resolveActivity", "package=$packageName state=no_entry")
            null
        } else {
            val name = qualified(packageName, candidate)
            val info = activityInfo(manager, packageName, name)
            // A declared alias names its target; it cannot describe a later trampoline destination.
            val target = info.targetActivity
            if (target == null) name else {
                val targetName = qualified(packageName, target)
                check(activityInfo(manager, packageName, targetName).targetActivity == null) {
                    "The Activity alias target is not a concrete Activity."
                }
                targetName
            }
        }
    } catch (cause: PackageManager.NameNotFoundException) {
        logger.debug("AndroidActivityResolver", "resolveActivity", "package=$packageName state=not_activity")
        null
    } catch (cause: Exception) {
        logger.error("AndroidActivityResolver", "resolveActivity", "package=$packageName state=resolve_failed",
            FailureDiagnostics.sanitized(cause))
        null
    }

    @Suppress("DEPRECATION")
    private fun entryClass(manager: PackageManager, packageName: String): String? {
        val home = manager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setPackage(packageName), PackageManager.MATCH_DEFAULT_ONLY)
        if (home != null) {
            val info = requireNotNull(home.activityInfo) { "The Home resolver returned no Activity metadata." }
            check(info.packageName == packageName) { "The Home Activity belongs to another package." }
            return requireNotNull(info.name) { "The Home Activity has no class name." }
        }
        val launch = manager.getLaunchIntentForPackage(packageName) ?: return null
        val component = requireNotNull(launch.component) { "The launch entry has no component." }
        check(component.packageName == packageName) { "The launch Activity belongs to another package." }
        return component.className
    }

    @Suppress("DEPRECATION")
    private fun activityInfo(manager: PackageManager, packageName: String, name: String): ActivityInfo {
        val info = manager.getActivityInfo(ComponentName(packageName, name), 0)
        check(info.packageName == packageName && info.name != null && qualified(packageName, info.name) == name) {
            "The resolved Activity metadata does not match its component."
        }
        return info
    }

    private fun qualified(packageName: String, className: String): String {
        require(className.isNotBlank()) { "The Activity class name is empty." }
        return when {
            className.startsWith('.') -> packageName + className
            '.' !in className -> "$packageName.$className"
            else -> className
        }
    }
}
