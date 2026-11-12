package io.humansignal.sdk.internal

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Diskteki parçaları şifreler (§3.9). */
internal interface ChunkCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM. Dosya düzeni: `[12 bayt IV][şifreli metin + 16 bayt etiket]`.
 * IV'yi sağlayıcı üretir (Keystore anahtarları dışarıdan IV kabul etmez — randomized encryption).
 */
internal class AesGcmCipher(private val keyProvider: () -> SecretKey) : ChunkCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = c.iv
        check(iv.size == IV_LEN) { "unexpected IV length ${iv.size}" }
        val ct = c.doFinal(plain)
        return ByteArray(IV_LEN + ct.size).also {
            System.arraycopy(iv, 0, it, 0, IV_LEN)
            System.arraycopy(ct, 0, it, IV_LEN, ct.size)
        }
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size >= IV_LEN + TAG_BITS / 8) { "ciphertext too short" }
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
        return c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}

/** Android Keystore'da cihazdan çıkarılamayan AES-256 anahtarı. */
internal object KeystoreKey {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val ALIAS = "io.humansignal.sdk.chunk-key.v1"

    @Volatile private var cached: SecretKey? = null

    @Synchronized
    fun getOrCreate(): SecretKey {
        cached?.let { return it }
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
        cached = key
        return key
    }
}
