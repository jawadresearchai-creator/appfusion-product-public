package com.appfusion.product.shared.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreKeyWrapperDeviceTest {
    @Test
    fun keystoreKekIsNonExportableAndAuthenticated() = runBlocking {
        val alias = "appfusion-secureblob-probe-${System.nanoTime()}"
        val wrapper = AndroidKeystoreKeyWrapper(alias)
        wrapper.deleteForProbe()
        try {
            val dataKey = ByteArray(32) { (it * 3 + 7).toByte() }
            val wrapped = wrapper.wrap(dataKey)
            assertTrue(wrapper.keyExistsForProbe())
            assertFalse("Android Keystore AES key must not expose encoded material", wrapper.keyMaterialExportableForProbe())
            assertArrayEquals(dataKey, wrapper.unwrap(wrapped))

            val tampered = wrapped.copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            var rejected = false
            try {
                wrapper.unwrap(tampered)
            } catch (_: Throwable) {
                rejected = true
            }
            assertTrue("tampered wrapped key must fail authentication", rejected)
        } finally {
            wrapper.deleteForProbe()
        }
        assertFalse(wrapper.keyExistsForProbe())
    }
}
