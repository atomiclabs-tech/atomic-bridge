package tech.atomiclabs.link.service

import android.content.Context
import tech.atomiclabs.link.core.log.Logger

/**
 * The in-app kill switch. Off by default: the link always comes back on its own (after a crash,
 * reboot or Settings force stop) with screen control restored. Only the user's own tap on
 * "Kill link" keeps it down, across restarts and reboots, until they tap "Turn link back on".
 */
class KillSwitch(
    private val logger: Logger,
    private val load: () -> Boolean,
    private val save: (Boolean) -> Unit,
    private val disableScreenControl: () -> Unit,
    private val enableScreenControl: () -> Unit,
    private val stopProcess: () -> Unit,
    private val startLink: () -> Unit,
) : tech.atomiclabs.link.ui.LinkSwitch {
    override fun isKilled() = load()

    /** Process start: heal screen control and connect unless the user killed the link. */
    fun onProcessStart(): Boolean {
        val killed = load()
        if (killed) logger.info("KillSwitch", "onProcessStart", "state=killed")
        else enableScreenControl()
        return killed
    }

    override fun kill() {
        save(true)
        logger.info("KillSwitch", "kill", "state=killed")
        disableScreenControl()
        stopProcess()
    }

    override fun turnOn() {
        save(false)
        logger.info("KillSwitch", "turnOn", "state=on")
        enableScreenControl()
        startLink()
    }

    companion object {
        fun forContext(context: Context, logger: Logger, stopProcess: () -> Unit, startLink: () -> Unit): KillSwitch {
            val prefs = context.getSharedPreferences("kill_switch", Context.MODE_PRIVATE)
            val heal = { tech.atomiclabs.link.device.access.AccessibilitySelfHeal.forContext(context, logger) }
            return KillSwitch(
                logger = logger,
                load = { prefs.getBoolean("killed", false) },
                save = { prefs.edit().putBoolean("killed", it).commit() },
                disableScreenControl = { heal().disable() },
                enableScreenControl = { heal().run() },
                stopProcess = stopProcess,
                startLink = startLink,
            )
        }
    }
}
