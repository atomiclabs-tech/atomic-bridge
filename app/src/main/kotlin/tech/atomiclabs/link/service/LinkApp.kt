// forge-allow: no-test Android Application shell retains one tested runtime and forwards startup and status binding.
package tech.atomiclabs.link.service

import android.app.Application
import android.app.Activity
import tech.atomiclabs.link.ui.StatusViewBinder

class LinkApp : Application(), StatusViewBinder.Factory {
    lateinit var runtime: LinkRuntime
        private set

    lateinit var killSwitch: KillSwitch
        private set

    override fun onCreate() {
        super.onCreate()
        killSwitch = KillSwitch.forContext(this, AndroidLogger(),
            stopProcess = {
                stopService(android.content.Intent(this, ConnectionService::class.java))
                android.os.Process.killProcess(android.os.Process.myPid())
            },
            startLink = { ServiceStarter.start(this) })
        killSwitch.onProcessStart()
        runtime = LinkRuntime(this)
        ServiceStarter.start(this)
    }

    override fun create(activity: Activity) = runtime.create(activity, killSwitch) {
        android.widget.Toast.makeText(activity, tech.atomiclabs.link.R.string.status_killed_toast, android.widget.Toast.LENGTH_LONG).show()
        activity.finishAffinity()
    }
}
