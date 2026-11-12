package tech.atomiclabs.link.service

import tech.atomiclabs.link.core.log.Logger

/** Decides which system startup actions may request the existing runtime. */
object BootActions {
    fun shouldStart(action: String?): Boolean =
        action == "android.intent.action.BOOT_COMPLETED" || action == "android.intent.action.MY_PACKAGE_REPLACED"

    fun handle(action: String?, logger: Logger, start: () -> Unit) {
        if (shouldStart(action)) start()
        else logger.warn("BootReceiver", "onReceive", "BootReceiver ignored action=$action")
    }
}
