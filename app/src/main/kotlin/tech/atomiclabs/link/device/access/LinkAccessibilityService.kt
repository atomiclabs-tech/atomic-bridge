// forge-allow: no-test Android service entry point only forwards to the runtime-owned tested AccessibilityHub.
package tech.atomiclabs.link.device.access

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class LinkAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        hub.onConnected(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hub.onUnbind(this)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = hub.onEvent(event)
    override fun onInterrupt() = hub.onInterrupt()

    companion object {
        lateinit var hub: AccessibilityHub
    }
}
