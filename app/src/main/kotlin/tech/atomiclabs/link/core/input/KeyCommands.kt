// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at commit da782a8.
package tech.atomiclabs.link.core.input

import kotlinx.serialization.json.*
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.port.*

class KeyCommands(
    private val globals: GlobalKeys, private val volume: VolumeControl,
    private val power: PowerState, private val text: TextEntry, private val clock: Clock,
) {
    fun needs(body: BodyParams): RouteNeeds = when (name(body)) {
        "volume_up", "volume_down" -> RouteNeeds(exclusive = true)
        "power" -> RouteNeeds(accessibility = true, exclusive = true)
        else -> RouteNeeds(true, true)
    }

    suspend fun execute(body: BodyParams): CommandResult {
        val key = name(body)
        val action = globalNames[key]
        val via = when {
            action != null -> { global(action); if (key == "escape") "back" else "global_action" }
            key == "volume_up" || key == "volume_down" -> {
                volume.adjust(if (key == "volume_up") VolumeDirection.UP else VolumeDirection.DOWN)
                    .inputValue(ErrorCode.GESTURE_FAILED, "The volume adjustment failed.")
                "audio"
            }
            key == "power" -> togglePower()
            key == "enter" -> { text.enter(); "ime" }
            key == "delete" -> { text.delete(); "set_text" }
            key == "tab" -> { text.tab(); "focus" }
            else -> inputFail(ErrorCode.UNSUPPORTED_KEY, "Supported keys are ${supported.joinToString(", ")}.")
        }
        return CommandResult.Success(buildJsonObject { put("key", key); put("via", via) })
    }

    private suspend fun togglePower(): String {
        if (power.isInteractive().inputValue(ErrorCode.WAKE_FAILED, "The display state could not be read.")) {
            global(GlobalAction.LOCK_SCREEN)
            return "lock_screen"
        }
        val deadline = clock.monotonicMs() + 2_000
        power.wakeWithBrightLock(2_000).inputValue(ErrorCode.WAKE_FAILED, "The display could not be woken.")
        while (!power.isInteractive().inputValue(ErrorCode.WAKE_FAILED, "The display state could not be read.")) {
            val remaining = deadline - clock.monotonicMs()
            if (remaining <= 0) inputFail(ErrorCode.WAKE_FAILED, "The display stayed off after waking.")
            clock.sleep(minOf(100, remaining))
        }
        return "wake"
    }

    private suspend fun global(action: GlobalAction) {
        globals.perform(action).inputValue(ErrorCode.GESTURE_FAILED, "The global key action was refused.")
    }

    private fun name(body: BodyParams): String = body.requiredString("key").trim().lowercase()

    companion object {
        private val globalNames = mapOf("back" to GlobalAction.BACK, "escape" to GlobalAction.BACK,
            "home" to GlobalAction.HOME, "recents" to GlobalAction.RECENTS, "recent_apps" to GlobalAction.RECENTS,
            "overview" to GlobalAction.RECENTS, "notifications" to GlobalAction.NOTIFICATIONS,
            "quick_settings" to GlobalAction.QUICK_SETTINGS, "power_dialog" to GlobalAction.POWER_DIALOG,
            "lock_screen" to GlobalAction.LOCK_SCREEN)
        val supported = globalNames.keys + listOf("power", "volume_up", "volume_down", "enter", "delete", "tab")
    }
}
