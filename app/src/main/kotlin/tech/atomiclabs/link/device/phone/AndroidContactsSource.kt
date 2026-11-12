// Adapted from upstream app/src/main/kotlin/com/hermesandroid/relay/accessibility/ActionExecutor.kt at da782a8.
package tech.atomiclabs.link.device.phone
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.CommonDataKinds.Phone
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

class AndroidContactsSource(private val context: Context, private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : ContactsSource {
    override suspend fun search(query: String, limit: Int): PortResult<List<Contact>> = try {
        withTimeout(5_000) { withContext(dispatcher) {
            require(limit in 1..100) { "Contact limit is invalid." }
            if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Contacts permission is missing.")
            }
            val uri = Uri.withAppendedPath(Contacts.CONTENT_FILTER_URI, Uri.encode(query))
            val resolver = context.contentResolver
            val cursor = checkNotNull(resolver.query(uri, arrayOf(Contacts._ID, Contacts.DISPLAY_NAME),
                null, null, Contacts.DISPLAY_NAME + " ASC")) { "Contacts query returned no cursor." }
            val found = cursor.use {
                val id = it.getColumnIndexOrThrow(Contacts._ID)
                val name = it.getColumnIndexOrThrow(Contacts.DISPLAY_NAME)
                buildList {
                    while (size < limit && it.moveToNext()) {
                        add(Contact(checkNotNull(it.getString(id)), checkNotNull(it.getString(name)), emptyList()))
                    }
                }
            }
            PortResult.Success(found.map { it.copy(phones = phones(it.id)) })
        } }
    } catch (cause: TimeoutCancellationException) { failed(cause, FailureKind.TIMED_OUT)
    } catch (cause: CancellationException) { throw cause
    } catch (cause: SecurityException) { failed(cause, FailureKind.PERMISSION_DENIED)
    } catch (cause: Exception) { failed(cause, FailureKind.PLATFORM) }

    private fun phones(id: String): List<ContactPhone> {
        val cursor = checkNotNull(context.contentResolver.query(Phone.CONTENT_URI,
            arrayOf(Phone.NUMBER, Phone.TYPE, Phone.LABEL), Phone.CONTACT_ID + " = ?", arrayOf(id), null)) {
            "Contact phone query returned no cursor."
        }
        return cursor.use {
            val number = it.getColumnIndexOrThrow(Phone.NUMBER)
            val type = it.getColumnIndexOrThrow(Phone.TYPE)
            val label = it.getColumnIndexOrThrow(Phone.LABEL)
            buildList { while (it.moveToNext()) add(ContactPhone(checkNotNull(it.getString(number)), it.getInt(type), it.getString(label))) }
        }
    }
    private fun failed(cause: Exception, kind: FailureKind): PortResult.Failure {
        logger.error("AndroidContactsSource", "search", "state=failed", FailureDiagnostics.sanitized(cause))
        return PortResult.Failure(PortFailure(kind, cause,
            permission = if (kind == FailureKind.PERMISSION_DENIED) Manifest.permission.READ_CONTACTS else null))
    }
}
