package tech.atomiclabs.link.storage

import android.security.keystore.KeyGenParameterSpec
import io.mockk.*
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.connection.assertSafe
import tech.atomiclabs.link.core.fakes.FakeLogger
import tech.atomiclabs.link.core.port.*

class KeystoreCipherTest {
    @Test fun aes256GcmUsesAFreshIvAndChecksTheAuthenticationTag() = runTest {
        val logger = FakeLogger()
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val creates = mutableListOf<Boolean>()
        val cipher = KeystoreCipher(logger, StandardTestDispatcher(testScheduler)) { creates += it; key }
        val plain = "private-session-fixture".encodeToByteArray()
        val first = (cipher.encrypt(plain) as PortResult.Success).value
        val second = (cipher.encrypt(plain) as PortResult.Success).value
        assertFalse(first.copyOfRange(0, 12).contentEquals(second.copyOfRange(0, 12)))
        assertEquals(plain.size + 28, first.size)
        assertArrayEquals(plain, (cipher.decrypt(first) as PortResult.Success).value)
        assertEquals(listOf(true, true, false), creates)
        val tampered = first.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertTrue(cipher.decrypt(tampered) is PortResult.Failure)
        assertTrue(cipher.decrypt(byteArrayOf(1)) is PortResult.Failure)
        assertSafe(logger, null, plain.decodeToString())
    }

    @Test fun keyFailureIsTypedAndItsEntirePrivateGraphIsSanitized() = runTest {
        val logger = FakeLogger()
        val original = IllegalStateException("private-key", SecurityException("private-key-cause"))
        original.addSuppressed(IllegalArgumentException("private-key-suppressed"))
        val cipher = KeystoreCipher(logger, StandardTestDispatcher(testScheduler)) { throw original }
        val result = cipher.encrypt(byteArrayOf(1)) as PortResult.Failure
        assertEquals(FailureKind.IO, result.failure.kind)
        val graph = generateSequence(result.failure.cause) { it.cause }.toList()
        assertTrue(graph.any { it.stackTrace.contentEquals(original.stackTrace) })
        assertNotNull(result.failure.cause.cause)
        assertTrue(graph.any { node -> node.suppressed.any { it.stackTrace.contentEquals(original.suppressed.single().stackTrace) } })
        assertSafe(logger, result.failure.cause, "private-key", "private-key-cause", "private-key-suppressed")
    }

    @Test fun androidKeyCreationUsesTheRequiredAliasAndUnrestrictedAesGcmParameters() = runTest {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val store = mockk<KeyStore>()
        val generator = mockk<KeyGenerator>()
        val spec = mockk<KeyGenParameterSpec>()
        val unusedBuilder = mockk<KeyGenParameterSpec.Builder>(relaxed = true)
        mockkStatic(KeyStore::class, KeyGenerator::class)
        mockkConstructor(KeyGenParameterSpec.Builder::class)
        try {
            every { KeyStore.getInstance("AndroidKeyStore") } returns store
            every { store.load(null) } just Runs
            every { store.containsAlias("link_session_v1") } returns false
            every { KeyGenerator.getInstance("AES", "AndroidKeyStore") } returns generator
            every { anyConstructed<KeyGenParameterSpec.Builder>().setKeySize(256) } returns unusedBuilder
            every { anyConstructed<KeyGenParameterSpec.Builder>().setBlockModes("GCM") } returns unusedBuilder
            every { anyConstructed<KeyGenParameterSpec.Builder>().setEncryptionPaddings("NoPadding") } returns unusedBuilder
            every { anyConstructed<KeyGenParameterSpec.Builder>().setUserAuthenticationRequired(false) } returns unusedBuilder
            every { anyConstructed<KeyGenParameterSpec.Builder>().setIsStrongBoxBacked(false) } returns unusedBuilder
            every { anyConstructed<KeyGenParameterSpec.Builder>().build() } returns spec
            every { generator.init(spec) } just Runs
            every { generator.generateKey() } returns key
            val cipher = KeystoreCipher(FakeLogger(), StandardTestDispatcher(testScheduler))
            assertTrue(cipher.encrypt(byteArrayOf(1)) is PortResult.Success)
            verify(exactly = 1) { anyConstructed<KeyGenParameterSpec.Builder>().setKeySize(256) }
            verify(exactly = 1) { anyConstructed<KeyGenParameterSpec.Builder>().setBlockModes("GCM") }
            verify(exactly = 1) { anyConstructed<KeyGenParameterSpec.Builder>().setEncryptionPaddings("NoPadding") }
            verify(exactly = 1) { anyConstructed<KeyGenParameterSpec.Builder>().setUserAuthenticationRequired(false) }
            verify(exactly = 1) { anyConstructed<KeyGenParameterSpec.Builder>().setIsStrongBoxBacked(false) }
            verify(exactly = 1) { generator.init(spec) }
        } finally { unmockkConstructor(KeyGenParameterSpec.Builder::class); unmockkStatic(KeyStore::class, KeyGenerator::class) }
    }

    @Test fun missingDecryptKeyIsNotSilentlyCreatedAndExistingKeyIsReused() = runTest {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val fixture = KeystoreCipher(FakeLogger(), StandardTestDispatcher(testScheduler)) { key }
        val encrypted = (fixture.encrypt(byteArrayOf(1)) as PortResult.Success).value
        val store = mockk<KeyStore>()
        mockkStatic(KeyStore::class, KeyGenerator::class)
        try {
            every { KeyStore.getInstance("AndroidKeyStore") } returns store
            every { store.load(null) } just Runs
            every { store.containsAlias("link_session_v1") } returns false
            val cipher = KeystoreCipher(FakeLogger(), StandardTestDispatcher(testScheduler))
            assertTrue(cipher.decrypt(encrypted) is PortResult.Failure)
            verify(exactly = 0) { KeyGenerator.getInstance(any<String>(), any<String>()) }
            every { store.containsAlias("link_session_v1") } returns true
            every { store.getKey("link_session_v1", null) } returns key
            assertArrayEquals(byteArrayOf(1), (cipher.decrypt(encrypted) as PortResult.Success).value)
            assertTrue(cipher.encrypt(byteArrayOf(2)) is PortResult.Success)
            verify(exactly = 0) { KeyGenerator.getInstance(any<String>(), any<String>()) }
        } finally { unmockkStatic(KeyStore::class, KeyGenerator::class) }
    }
}
