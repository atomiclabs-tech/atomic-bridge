// forge-allow: no-test Android Activity shell only forwards to the tested StatusViewBinder.
package tech.atomiclabs.link.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import tech.atomiclabs.link.service.ServiceStarter

class StatusActivity : Activity() {
    private val screen by lazy { (application as StatusViewBinder.Factory).create(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceStarter.start(this)
        screen.create()
        forwardLink(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardLink(intent)
    }
    private fun forwardLink(incoming: Intent) {
        if (incoming.action == Intent.ACTION_VIEW) incoming.dataString?.let {
            incoming.data = null
            screen.openLink(it)
        }
    }
    override fun onResume() {
        super.onResume()
        screen.resume()
    }
    override fun onPause() {
        screen.pause()
        super.onPause()
    }
    override fun onDestroy() {
        screen.destroy()
        super.onDestroy()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        screen.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
