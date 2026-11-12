package theseus.agent.storage

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM: случайный nonce и привязка к записи не позволяют переставлять зашифрованные payload. */
class JournalCipher(
    hexKey: String,
) {
    private val key = SecretKeySpec(hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "AES")
    private val random = SecureRandom()

    init {
        require(hexKey.matches(Regex("[0-9a-fA-F]{64}"))) { "Ключ журнала должен содержать 64 hex-символа" }
    }

    fun encrypt(
        id: String,
        plaintext: String,
    ): String {
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(id.toByteArray())
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(
        id: String,
        encrypted: String,
    ): String {
        val bytes = Base64.getDecoder().decode(encrypted)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, NONCE_SIZE)))
        cipher.updateAAD(id.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(NONCE_SIZE, bytes.size)).toString(Charsets.UTF_8)
    }

    companion object {
        private const val NONCE_SIZE = 12
        private const val TAG_BITS = 128
    }
}
