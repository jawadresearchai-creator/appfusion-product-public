package com.appfusion.product.shared.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreKeyWrapper(
    private val alias: String,
    override val keyId: String = "android-keystore-kek-v1",
) : DeviceKeyWrapper {
    override suspend fun wrap(clearKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(KEY_WRAP_AAD)
        val iv = cipher.iv
        require(iv.size in 1..255) { "Unexpected Android Keystore GCM IV length" }
        val encrypted = cipher.doFinal(clearKey)
        return ByteArray(1 + iv.size + encrypted.size).also { output ->
            output[0] = iv.size.toByte()
            iv.copyInto(output, 1)
            encrypted.copyInto(output, 1 + iv.size)
        }
    }

    override suspend fun unwrap(wrappedKey: ByteArray): ByteArray {
        require(wrappedKey.size > 1) { "Truncated wrapped key" }
        val ivLength = wrappedKey[0].toInt() and 0xff
        require(ivLength in 1 until wrappedKey.size) { "Invalid wrapped-key IV length" }
        val iv = wrappedKey.copyOfRange(1, 1 + ivLength)
        val ciphertext = wrappedKey.copyOfRange(1 + ivLength, wrappedKey.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(KEY_WRAP_AAD)
        return cipher.doFinal(ciphertext)
    }

    fun keyExistsForProbe(): Boolean = keyStore().containsAlias(alias)

    fun keyMaterialExportableForProbe(): Boolean = getOrCreateKey().encoded != null

    fun deleteForProbe() {
        val store = keyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
