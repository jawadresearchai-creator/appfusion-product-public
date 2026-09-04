package com.appfusion.product.shared.security

import kotlinx.coroutines.runBlocking

/**
 * Application-context probe for the Apple Keychain adapter.
 *
 * Kotlin/Native's standalone test executable is not an iOS application and can
 * receive errSecNotAvailable from Keychain Services. This facade is invoked by
 * a simulator-installed host app so the same production adapter is exercised
 * inside a real application security context.
 */
class AppleKeychainHostProbe {
    fun runProbe(): String = runBlocking {
        val service = "com.appfusion.product.secureblob.probe"
        val account = "host-app-kek"
        val wrapper = AppleKeychainKeyWrapper(service = service, account = account)
        wrapper.deleteForProbe()
        try {
            val dataKey = ByteArray(32) { (it * 5 + 11).toByte() }
            val wrapped = wrapper.wrap(dataKey)
            check(wrapper.keyExistsForProbe()) { "Apple Keychain KEK was not persisted" }
            check(dataKey.contentEquals(wrapper.unwrap(wrapped))) { "Apple Keychain unwrap mismatch" }

            val secondWrapper = AppleKeychainKeyWrapper(service = service, account = account)
            check(dataKey.contentEquals(secondWrapper.unwrap(wrapped))) {
                "Apple Keychain KEK did not persist across wrapper instances"
            }

            val tampered = wrapped.copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            val rejected = runCatching { secondWrapper.unwrap(tampered) }.isFailure
            check(rejected) { "Tampered wrapped DEK was not rejected" }

            val secureBlob = SecureBlobService(secondWrapper)
            val plaintext = "apple keychain backed secure blob".encodeToByteArray()
            val encoded = secureBlob.protect(plaintext)
            check(plaintext.contentEquals(secureBlob.unprotect(encoded))) {
                "SecureBlob round-trip failed with Apple Keychain KEK"
            }
            "OK"
        } finally {
            wrapper.deleteForProbe()
        }.also {
            check(!wrapper.keyExistsForProbe()) { "Apple Keychain probe cleanup failed" }
        }
    }
}
