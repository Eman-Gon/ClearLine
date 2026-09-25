package com.clearline.sponsors

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual AndroidKeyStore/AtomicFile test; never substitutes a host JVM provider. */
@RunWith(AndroidJUnit4::class)
class CredentialVaultDeviceTest {
    @Test fun isolatedSyntheticTokenSurvivesReconstructionAndClear() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testId = UUID.randomUUID().toString()
        val keyNamespace = "com.clearline.sponsors.instrumentation.$testId"
        val blobNamespace = "sponsor_test_$testId"
        val firstKey = AndroidCredentialKeySource(keyNamespace)
        val firstBlob = AndroidCredentialBlobStore(context, blobNamespace)
        val cipher = CredentialCipher(firstKey)
        val token = "synthetic-instrumentation-token".toByteArray()
        try {
            val ciphertext = cipher.encrypt("nimble", token)
            firstBlob.write("nimble", ciphertext)
            assertFalse(String(ciphertext).contains("synthetic-instrumentation-token"))
            val restoredCipher = CredentialCipher(AndroidCredentialKeySource(keyNamespace))
            val restoredBlob = AndroidCredentialBlobStore(context, blobNamespace)
            assertArrayEquals(token, restoredCipher.decrypt("nimble", restoredBlob.read("nimble")!!))
            assertTrue(java.io.File(context.noBackupFilesDir, "$blobNamespace/nimble.enc").exists())
            firstKey.delete("nimble")
            assertThrows(Exception::class.java) { restoredCipher.decrypt("nimble", ciphertext) }
            firstBlob.delete("nimble")
            assertNull(firstBlob.read("nimble"))
        } finally {
            token.fill(0)
            firstKey.delete("nimble")
            firstBlob.delete("nimble")
            java.io.File(context.noBackupFilesDir, blobNamespace).deleteRecursively()
        }
    }
}
