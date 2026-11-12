// Ported from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ScreenReader.kt at commit da782a8.
package tech.atomiclabs.link.core.screen

import tech.atomiclabs.link.core.port.UiNodeProperties

object NodeFilter {
    fun emits(node: UiNodeProperties): Boolean = with(node) {
        bounds.right > bounds.left && bounds.bottom > bounds.top &&
            (!text.isNullOrBlank() || !contentDescription.isNullOrBlank() ||
                clickable || longClickable || scrollable || editable)
    }
}
