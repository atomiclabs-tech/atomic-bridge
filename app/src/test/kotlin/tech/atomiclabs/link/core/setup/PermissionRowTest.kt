package tech.atomiclabs.link.core.setup

import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.port.*

class PermissionRowTest {
    @Test fun everyPermissionAppearsOnceInOrderWithOnlyNotificationsOptional() {
        val rows = PermissionRow.from(PermissionSnapshot(true, false, true, false, true, false, true, false, true))
        assertEquals(Permission.entries, rows.map { it.permission })
        assertEquals(listOf(true, false, true, false, true, false, true, false, true, false, false, false, false), rows.map { it.granted })
        assertEquals(listOf(Permission.NOTIFICATION_ACCESS, Permission.ALL_FILES, Permission.MICROPHONE, Permission.PHONE_STATE),
            rows.takeLast(4).map { it.permission })
        assertEquals(listOf(Permission.NOTIFICATIONS), rows.filter { it.optional }.map { it.permission })
    }

    @Test fun unreadPermissionsRemainUnknownInsteadOfFabricatedDenials() {
        val rows = PermissionRow.unknown()
        assertEquals(13, rows.size)
        assertTrue(rows.all { it.granted == null })
    }
    @Test fun appendedRowsUseTheirActualSnapshotFieldsInEnumOrder() {
        val rows = PermissionRow.from(PermissionSnapshot(false, false, false, false, false, false, false, false, false,
            notificationAccess = true, allFiles = false, microphone = true, phoneState = false))
        assertEquals(listOf(true, false, true, false), rows.takeLast(4).map { it.granted })
    }
}
