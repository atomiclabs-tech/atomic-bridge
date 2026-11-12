package tech.atomiclabs.link.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import tech.atomiclabs.link.core.command.FailureDiagnostics
import tech.atomiclabs.link.core.log.Logger
import tech.atomiclabs.link.core.port.*

/** AES-GCM bytes are a 12-byte fresh IV followed by ciphertext and its 128-bit authentication tag. */
class KeystoreCipher(
    private val logger: Logger,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val key: (create: Boolean) -> SecretKey = ::androidKey,
) : SessionCipher {
    override suspend fun encrypt(plaintext: ByteArray): PortResult<ByteArray> = operation("encrypt") {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        check(cipher.iv.size == 12) { "The session IV size is invalid." }
        cipher.iv + cipher.doFinal(plaintext)
    }

    override suspend fun decrypt(ciphertext: ByteArray): PortResult<ByteArray> = operation("decrypt") {
        require(ciphertext.size >= 28) { "The encrypted session is truncated." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
        cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
    }

    private suspend fun operation(name: String, block: () -> ByteArray): PortResult<ByteArray> = try {
        PortResult.Success(withTimeout(2_000) { withContext(dispatcher) { block() } })
    } catch (cause: CancellationException) {
        if (cause !is TimeoutCancellationException) throw cause
        failed(name, cause, FailureKind.TIMED_OUT)
    } catch (cause: Exception) { failed(name, cause, FailureKind.IO) }

    private fun failed(operation: String, cause: Exception, kind: FailureKind): PortResult.Failure {
        val safe = FailureDiagnostics.sanitized(cause)
        logger.error("KeystoreCipher", operation, "path=session.bin state=cipher_failed", safe)
        return PortResult.Failure(PortFailure(kind, safe))
    }

    private companion object {
        const val ALIAS = "link_session_v1"
        fun androidKey(create: Boolean): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(ALIAS)) {
                return store.getKey(ALIAS, null) as? SecretKey
                    ?: throw IllegalStateException("The session key is unavailable.")
            }
            check(create) { "The session key is missing." }
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).apply {
                setKeySize(256)
                setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                setUserAuthenticationRequired(false)
                setIsStrongBoxBacked(false)
            }.build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
        }
    }
}
