// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenReader.kt at commit da782a8.
package tech.atomiclabs.link.core.screen

import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

/** Shared foreground resolver for screen routes and device status. */
class WindowOrder(private val source: UiTreeSource, private val logger: Logger) {
    suspend fun snapshot(): ScreenSnapshot {
        val byLayer = source.windows().valueOrThrow().filter { it.root != null }.sortedByDescending { it.layer }
        val primary = byLayer.firstOrNull { it.type == WindowType.APPLICATION && (it.active || it.focused) }
        val windows = if (primary == null) byLayer else listOf(primary) + byLayer.filter { it !== primary }
        val roots = windows.map { window ->
            val root = requireNotNull(window.root)
            ScreenWindow(window, root, root.read().valueOrThrow())
        }
        if (roots.isNotEmpty()) {
            val application = roots.firstOrNull { it.window?.type == WindowType.APPLICATION }
            val foreground = if (application != null) application.properties.packageName else activePackage()
            return ScreenSnapshot(roots, foreground)
        }
        logger.warn("WindowOrder", "activeRootFallback", "state=no_readable_windows")
        return when (val root = source.activeRoot()) {
            ReadValue.Absent -> ScreenSnapshot(emptyList(), null)
            is ReadValue.Unreadable -> throw ScreenReadException(root.failure)
            is ReadValue.Present -> {
                val properties = root.value.read().valueOrThrow()
                // No window metadata is fabricated when only the real active root is available.
                ScreenSnapshot(listOf(ScreenWindow(null, root.value, properties)), properties.packageName)
            }
        }
    }

    suspend fun foregroundPackage(): String? = snapshot().packageName

    private suspend fun activePackage(): String? = when (val root = source.activeRoot()) {
        ReadValue.Absent -> null
        is ReadValue.Unreadable -> throw ScreenReadException(root.failure)
        is ReadValue.Present -> root.value.read().valueOrThrow().packageName
    }
}
