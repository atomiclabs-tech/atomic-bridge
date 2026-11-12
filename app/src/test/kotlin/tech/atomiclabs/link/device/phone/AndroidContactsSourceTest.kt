package tech.atomiclabs.link.device.phone
import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.CommonDataKinds.Phone
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class AndroidContactsSourceTest {
    private val context=mockk<Context>()
    private val resolver=mockk<ContentResolver>()
    private val logger=FakeLogger()
    private fun adapter(test:TestScope):AndroidContactsSource {
        every{context.checkSelfPermission(Manifest.permission.READ_CONTACTS)} returns PackageManager.PERMISSION_GRANTED
        every{context.contentResolver} returns resolver
        return AndroidContactsSource(context,logger,StandardTestDispatcher(test.testScheduler))
    }
    @Test fun contactsAndPhonesHaveActualTypedShapeLimitBoundAndBothCursorsClose()=runTest {
        val adapter=adapter(this)
        val contacts=mockk<Cursor>();val phones=mockk<Cursor>();val uri=mockk<Uri>()
        mockkStatic(Uri::class)
        try {
            every{Uri.encode("private-query")} returns "encoded"
            every{Uri.withAppendedPath(Contacts.CONTENT_FILTER_URI,"encoded")} returns uri
            every{resolver.query(uri,any(),null,null,any<String>())} returns contacts
            every{contacts.getColumnIndexOrThrow(Contacts._ID)} returns 0
            every{contacts.getColumnIndexOrThrow(Contacts.DISPLAY_NAME)} returns 1
            every{contacts.moveToNext()} returnsMany listOf(true,true,false)
            every{contacts.getString(0)} returns "7";every{contacts.getString(1)} returns "private-name"
            every{contacts.close()} just Runs
            every{resolver.query(Phone.CONTENT_URI,any(),Phone.CONTACT_ID+" = ?",eq(arrayOf("7")),null)} returns phones
            every{phones.getColumnIndexOrThrow(Phone.NUMBER)} returns 0
            every{phones.getColumnIndexOrThrow(Phone.TYPE)} returns 1
            every{phones.getColumnIndexOrThrow(Phone.LABEL)} returns 2
            every{phones.moveToNext()} returnsMany listOf(true,false)
            every{phones.getString(0)} returns "private-number";every{phones.getInt(1)} returns 2
            every{phones.getString(2)} returns null;every{phones.close()} just Runs
            assertEquals(PortResult.Success(listOf(Contact("7","private-name",listOf(ContactPhone("private-number",2,null))))),adapter.search("private-query",1))
            verify(exactly=1){contacts.moveToNext()}
            verify(exactly=1){contacts.close();phones.close()}
            assertTrue(logger.entries.isEmpty())
        } finally{unmockkStatic(Uri::class)}
    }
    @Test fun missingPermissionAndFailedQueriesNeverBecomeEmptySuccessOrLeakContacts()=runTest {
        val adapter=adapter(this)
        every{context.checkSelfPermission(Manifest.permission.READ_CONTACTS)} returns PackageManager.PERMISSION_DENIED
        val denied=adapter.search("private-query",20) as PortResult.Failure
        assertEquals("android.permission.READ_CONTACTS",denied.failure.permission)
        verify{resolver wasNot Called}
        every{context.checkSelfPermission(Manifest.permission.READ_CONTACTS)} returns PackageManager.PERMISSION_GRANTED
        mockkStatic(Uri::class)
        try {
            val uri=mockk<Uri>()
            every{Uri.encode(any())} returns "encoded"
            every{Uri.withAppendedPath(Contacts.CONTENT_FILTER_URI,"encoded")} returns uri
            every{resolver.query(uri,any(),null,null,any<String>())} throws IllegalStateException("private-contact")
            assertTrue(adapter.search("private-query",20) is PortResult.Failure)
            every{resolver.query(uri,any(),null,null,any<String>())} returns null
            assertTrue(adapter.search("private-query",20) is PortResult.Failure)
            assertEquals(3,logger.entries.size)
            assertTrue(logger.entries.all{!it.cause!!.stackTraceToString().contains("private-")})
        } finally{unmockkStatic(Uri::class)}
    }
}
