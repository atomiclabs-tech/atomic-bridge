package tech.atomiclabs.link.service

import android.util.Log
import tech.atomiclabs.link.core.log.Logger

class AndroidLogger : Logger {
    override fun debug(component: String, operation: String, context: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "$component/$operation $context")
        }
    }

    override fun info(component: String, operation: String, context: String) {
        Log.i(TAG, "$component/$operation $context")
    }

    override fun warn(component: String, operation: String, context: String, cause: Throwable?) {
        Log.w(TAG, "$component/$operation $context", cause)
    }

    override fun error(component: String, operation: String, context: String, cause: Throwable) {
        Log.e(TAG, "$component/$operation $context", cause)
    }

    private companion object {
        const val TAG = "AtomicLink"
    }
}
