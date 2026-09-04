@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package com.appfusion.product.shared.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecUseDataProtectionKeychain
import platform.Security.kSecValueData
import platform.posix.memcpy

class AppleKeychainKeyWrapper(
    service: String,
    account: String = "secureblob-kek",
    override val keyId: String = "apple-keychain-kek-v1",
) : DeviceKeyWrapper {
    private val store = AppleKeychainByteStore(service, account)

    override suspend fun wrap(clearKey: ByteArray): ByteArray {
        val kek = keyBytes()
        return try {
            wrapWithRawAesKey(kek, clearKey)
        } finally {
            kek.fill(0)
        }
    }

    override suspend fun unwrap(wrappedKey: ByteArray): ByteArray {
        val kek = keyBytes()
        return try {
            unwrapWithRawAesKey(kek, wrappedKey)
        } finally {
            kek.fill(0)
        }
    }

    fun keyExistsForProbe(): Boolean = store.read() != null

    fun deleteForProbe() = store.delete()

    private suspend fun keyBytes(): ByteArray {
        store.read()?.let {
            require(it.size == 32) { "Invalid Apple Keychain KEK size" }
            return it
        }
        val generated = generateAes256KeyBytes()
        if (store.addIfAbsent(generated)) return generated
        generated.fill(0)
        return requireNotNull(store.read()) { "Apple Keychain KEK creation raced but no key exists" }
            .also { require(it.size == 32) { "Invalid Apple Keychain KEK size" } }
    }
}

private class AppleKeychainByteStore(
    private val service: String,
    private val account: String,
) {
    fun addIfAbsent(data: ByteArray): Boolean = memScoped {
        val serviceRef = CFBridgingRetain(service)
        val accountRef = CFBridgingRetain(account)
        val dataRef = CFBridgingRetain(data.toNSData())
        try {
            val query = cfDictionaryOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to serviceRef,
                kSecAttrAccount to accountRef,
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                kSecAttrSynchronizable to kCFBooleanFalse,
                kSecUseDataProtectionKeychain to kCFBooleanTrue,
                kSecValueData to dataRef,
            )
            val status = SecItemAdd(query, null)
            CFBridgingRelease(query)
            when (status) {
                errSecSuccess -> true
                errSecDuplicateItem -> false
                else -> error("Apple Keychain add failed: $status")
            }
        } finally {
            CFBridgingRelease(dataRef)
            CFBridgingRelease(accountRef)
            CFBridgingRelease(serviceRef)
        }
    }

    fun read(): ByteArray? = memScoped {
        val serviceRef = CFBridgingRetain(service)
        val accountRef = CFBridgingRetain(account)
        try {
            val result = alloc<CFTypeRefVar>()
            val query = cfDictionaryOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to serviceRef,
                kSecAttrAccount to accountRef,
                kSecAttrSynchronizable to kCFBooleanFalse,
                kSecUseDataProtectionKeychain to kCFBooleanTrue,
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
            )
            val status = SecItemCopyMatching(query, result.ptr)
            CFBridgingRelease(query)
            when (status) {
                errSecItemNotFound -> null
                errSecSuccess -> {
                    val value = requireNotNull(result.value) { "Apple Keychain returned no data" }
                    (CFBridgingRelease(value) as NSData).toByteArray()
                }
                else -> error("Apple Keychain read failed: $status")
            }
        } finally {
            CFBridgingRelease(accountRef)
            CFBridgingRelease(serviceRef)
        }
    }

    fun delete() = memScoped {
        val serviceRef = CFBridgingRetain(service)
        val accountRef = CFBridgingRetain(account)
        try {
            val query = cfDictionaryOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to serviceRef,
                kSecAttrAccount to accountRef,
                kSecAttrSynchronizable to kCFBooleanFalse,
                kSecUseDataProtectionKeychain to kCFBooleanTrue,
            )
            val status = SecItemDelete(query)
            CFBridgingRelease(query)
            require(status == errSecSuccess || status == errSecItemNotFound) {
                "Apple Keychain delete failed: $status"
            }
        } finally {
            CFBridgingRelease(accountRef)
            CFBridgingRelease(serviceRef)
        }
    }
}

private fun MemScope.cfDictionaryOf(
    vararg items: Pair<CFStringRef?, CFTypeRef?>,
): CFDictionaryRef? {
    val keys = allocArrayOf(*items.map { it.first }.toTypedArray())
    val values = allocArrayOf(*items.map { it.second }.toTypedArray())
    return CFDictionaryCreate(
        kCFAllocatorDefault,
        keys.reinterpret(),
        values.reinterpret(),
        items.size.convert(),
        null,
        null,
    )
}

private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).apply {
    if (length > 0u) usePinned { memcpy(it.addressOf(0), bytes, length) }
}

private fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = size.convert())
}
