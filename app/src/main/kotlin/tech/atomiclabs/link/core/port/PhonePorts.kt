// forge-allow: no-test declarations only
package tech.atomiclabs.link.core.port

/** Lists launchable apps and checks launch/intent/broadcast results; no-handler and absent-app are explicit. */
interface AppControl {
    suspend fun launchableApps(): PortResult<List<LaunchableApp>>
    suspend fun launch(packageName: String): AppLaunchOutcome
    suspend fun startActivity(intent: IntentSpec): ActivityOutcome
    suspend fun sendBroadcast(intent: IntentSpec): PortResult<Unit>
}

/** Empty clipboard is an empty string; denied reads are failures. Focused reads may use Trampoline. */
interface ClipboardAccess {
    suspend fun read(): PortResult<String>
    suspend fun write(text: String): PortResult<Unit>
}

/** Sends one media key and returns the platform keycode; a refused dispatch is a failure. */
fun interface MediaKeys { suspend fun send(action: MediaAction): PortResult<Int> }

/** Last-known fixes may be empty; current-fix absence differs from failure, with a maximum 10 s wait. */
interface LocationSource {
    suspend fun lastKnown(): PortResult<List<LocationFix>>
    suspend fun current(timeoutMs: Long): ReadValue<LocationFix>
}

/** Returns bounded contact search results; permission failures name the required Android permission. */
fun interface ContactsSource {
    suspend fun search(query: String, limit: Int): PortResult<List<Contact>>
}

/** Places one direct call; missing permission or a refused call is a failure, with no dialer fallback. */
fun interface CallControl { suspend fun call(number: String): PortResult<Unit> }

/** Sends once, awaits multipart radio results for at most 15 s, and never retries unconfirmed sends. */
fun interface SmsSender { suspend fun send(to: String, body: String): SmsOutcome }

/** Opens one compose activity with read grants and reports its resolved target or distinct no-handler result. */
fun interface ShareLauncher { suspend fun open(request: ShareRequest): ShareLaunchOutcome }

/** Confines names to outbound cache and returns FileProvider URIs; prune/write failures open nothing. */
interface OutboundFiles {
    suspend fun prune(olderThanEpochMs: Long): PortResult<Unit>
    suspend fun write(bytes: ByteArray, fileName: String?, contentType: String): PortResult<OutboundAttachment>
}
