# The serialization library supplies its runtime rules; retain the app's wire and session models.
-keep @kotlinx.serialization.Serializable class tech.atomiclabs.link.** { *; }
-keep class tech.atomiclabs.link.**$$serializer { *; }

# Android and persisted accessibility component names must continue to resolve after shrinking.
-keepnames class tech.atomiclabs.link.service.LinkApp
-keepnames class tech.atomiclabs.link.ui.StatusActivity
-keepnames class tech.atomiclabs.link.device.access.LinkAccessibilityService
-keepnames class tech.atomiclabs.link.device.power.TrampolineActivity
-keepnames class tech.atomiclabs.link.device.notify.LinkNotificationListener
-keepnames class tech.atomiclabs.link.service.ConnectionService
-keepnames class tech.atomiclabs.link.service.BootReceiver
