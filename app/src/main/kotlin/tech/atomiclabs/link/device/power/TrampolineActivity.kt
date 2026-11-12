// forge-allow: no-test Android activity entry point only forwards lifecycle calls to tested TrampolineJobs.
package tech.atomiclabs.link.device.power

import android.app.Activity
import android.os.Bundle

class TrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jobs.onCreate(this, intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        jobs.onWindowFocusChanged(this, hasFocus)
    }

    override fun onDestroy() {
        jobs.onDestroyed(this)
        super.onDestroy()
    }

    companion object { lateinit var jobs: TrampolineJobs }
}
