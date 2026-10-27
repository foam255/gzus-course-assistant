package com.gzus.courseassistant.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureFileStore(context: Context) {
    private val root = File(context.filesDir, "secure").apply { mkdirs() }
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun read(name: String): String? = runCatching {
        val file = File(root, name)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size < 13 || bytes[0] != VERSION) return null
        val ivLength = bytes[1].toInt() and 0xff
        if (ivLength !in 12..32 || bytes.size <= ivLength + 2) return null
        val iv = bytes.copyOfRange(2, 2 + ivLength)
        val ciphertext = bytes.copyOfRange(2 + ivLength, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }.getOrNull()

    fun write(name: String, value: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val bytes = byteArrayOf(VERSION, iv.size.toByte()) + iv + ciphertext
        val target = File(root, name)
        val temp = File(root, "$name.tmp")
        temp.writeBytes(bytes)
        runCatching {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        true
    }.getOrDefault(false)

    fun delete(name: String) {
        File(root, name).delete()
    }

    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "course_assistant_state_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
    }
}
