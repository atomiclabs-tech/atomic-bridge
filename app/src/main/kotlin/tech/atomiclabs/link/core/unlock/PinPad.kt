package tech.atomiclabs.link.core.unlock

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.screen.*

/** Keys must be direct, enabled controls inside one SystemUI screen-lock bouncer. */
class PinPad(private val windows: WindowOrder, private val walker: ScreenTreeWalker) {
    data class Keys(val digits: Map<Char, LocatedNode>, val confirm: LocatedNode?)

    suspend fun find(): Keys? {
        val snapshot = windows.snapshot()
        val trusted = snapshot.windows.filter { entry ->
            entry.properties.packageName == PinPadRules.systemUiPackage &&
                entry.window?.type == WindowType.SYSTEM
        }
        val digits = mutableMapOf<Int, MutableMap<Char, LocatedNode>>()
        val confirms = mutableMapOf<Int, LocatedNode>()
        var sim = false
        val outcome = walker.visit(snapshot.copy(windows = trusted), MAX_SEARCH_VISITS, includeContainers = true) { candidate ->
            val p = candidate.properties
            if (p.packageName == PinPadRules.systemUiPackage) {
                if (isSim(candidate)) sim = true
                if (p.clickable && p.enabled && !p.password && !p.editable && inBouncer(candidate)) {
                    for (digit in '0'..'9') if (matchesDigit(p, digit)) {
                        digits.getOrPut(p.windowId) { mutableMapOf() }.putIfAbsent(digit, candidate)
                    }
                    if (matchesConfirm(p)) confirms.putIfAbsent(p.windowId, candidate)
                }
            }
            true
        }
        if (sim || outcome.truncated) return null
        val complete = digits.entries.filter { it.value.size == 10 }
        if (complete.size != 1) return null
        val (windowId, keys) = complete.single()
        return Keys(keys.toMap(), confirms[windowId])
    }

    private suspend fun inBouncer(candidate: LocatedNode): Boolean {
        var node = candidate.node
        var properties = candidate.properties
        repeat(64) {
            currentCoroutineContext().ensureActive()
            if (properties.packageName != PinPadRules.systemUiPackage ||
                properties.windowId != candidate.properties.windowId) return false
            if (PinPadRules.bouncerViewIdSuffixes.any { properties.viewId?.endsWith(it) == true }) return true
            node = when (val parent = node.parent()) {
                ReadValue.Absent -> return false
                is ReadValue.Unreadable -> throw ScreenReadException(parent.failure)
                is ReadValue.Present -> parent.value
            }
            properties = when (val read = node.read()) {
                is PortResult.Success -> read.value
                is PortResult.Failure -> throw ScreenReadException(read.failure)
            }
        }
        return false
    }

    private suspend fun isSim(candidate: LocatedNode): Boolean {
        val p = candidate.properties
        return PinPadRules.simViewIdFragments.any { p.viewId?.contains(it, ignoreCase = true) == true ||
            p.className?.contains(it, ignoreCase = true) == true } ||
            (!p.password && !p.editable && PinPadRules.simLabels.any {
                TextMatch.contains(p.text, it) || TextMatch.contains(p.contentDescription, it)
            } && inBouncer(candidate))
    }

    private fun matchesDigit(properties: UiNodeProperties, digit: Char): Boolean = PinPadRules.digitRules.any { rule ->
        when (rule.field) {
            DigitField.TEXT -> TextMatch.equals(properties.text, digit.toString())
            DigitField.DESCRIPTION -> TextMatch.equals(properties.contentDescription, digit.toString())
            DigitField.VIEW_ID -> properties.viewId?.endsWith("${rule.prefix}$digit") == true
        }
    }

    private fun matchesConfirm(properties: UiNodeProperties): Boolean = PinPadRules.confirmLabels.any { label ->
        TextMatch.equals(properties.text, label) || TextMatch.equals(properties.contentDescription, label)
    } || PinPadRules.confirmViewIdSuffixes.any { properties.viewId?.endsWith(it) == true }
}
