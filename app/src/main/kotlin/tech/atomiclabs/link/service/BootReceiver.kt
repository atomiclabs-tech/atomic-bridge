// forge-allow: no-test Android receiver shell forwards to tested BootActions and ServiceStarter.
package tech.atomiclabs.link.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) =
        BootActions.handle(intent.action, AndroidLogger()) { ServiceStarter.start(context) }
}
