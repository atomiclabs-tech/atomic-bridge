// forge-allow: no-test Android service shell forwards lifecycle to the tested runtime and service lifetime.
package tech.atomiclabs.link.service

import android.app.Service
import android.content.Intent

class ConnectionService : Service() {
    override fun onCreate() {
        super.onCreate()
        (application as LinkApp).runtime.serviceCreated(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        (application as LinkApp).runtime.start()
        return START_STICKY
    }

    override fun onDestroy() {
        (application as LinkApp).runtime.serviceDestroyed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
