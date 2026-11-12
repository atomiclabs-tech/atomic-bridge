package tech.atomiclabs.link.core.setup

import tech.atomiclabs.link.core.port.Permission
import tech.atomiclabs.link.core.port.PermissionSnapshot

data class PermissionRow(val permission: Permission, val granted: Boolean?, val optional: Boolean) {
    companion object {
        fun unknown(): List<PermissionRow> = Permission.entries.map {
            PermissionRow(it, null, it == Permission.NOTIFICATIONS)
        }

        fun from(snapshot: PermissionSnapshot): List<PermissionRow> {
            val values = listOf(
                snapshot.accessibility, snapshot.batteryExempt, snapshot.unusedAppRestrictionsDisabled,
                snapshot.notifications, snapshot.sms, snapshot.call, snapshot.contacts,
                snapshot.location, snapshot.backgroundLocation,
                snapshot.notificationAccess, snapshot.allFiles, snapshot.microphone, snapshot.phoneState,
            )
            return Permission.entries.mapIndexed { index, permission ->
                PermissionRow(permission, values[index], permission == Permission.NOTIFICATIONS)
            }
        }
    }
}
