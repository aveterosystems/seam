// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the core's sealed state blob lives, and the key it's sealed with. */
interface StateStore {
    /** 32 bytes. The core seals the state blob with it (AES-256-GCM). */
    val storageKey: ByteArray
    fun load(): ByteArray?
    fun save(blob: ByteArray)
}

/**
 * The storage key is random, and kept on disk only wrapped by a non-exportable AES-256-GCM key in
 * Android Keystore (StrongBox when the phone has it). Copying Seam's files off the phone gets you
 * nothing: unwrapping needs this phone's hardware.
 */
class KeystoreStateStore(private val dir: File) : StateStore {
    private val stateFile = File(dir, "state.bin")
    private val wrappedKeyFile = File(dir, "storage_key.bin")

    override val storageKey: ByteArray by lazy { unwrapOrCreate() }

    override fun load(): ByteArray? = stateFile.takeIf { it.exists() }?.readBytes()

    override fun save(blob: ByteArray) = writeAtomically(stateFile, blob)

    private fun unwrapOrCreate(): ByteArray {
        val wrapping = wrappingKey()
        if (wrappedKeyFile.exists()) {
            val bytes = wrappedKeyFile.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(128, bytes, 0, 12))
            return cipher.doFinal(bytes, 12, bytes.size - 12)
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapping) }
        writeAtomically(wrappedKeyFile, cipher.iv + cipher.doFinal(key))
        return key
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        fun generate(strongBox: Boolean): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setIsStrongBoxBacked(strongBox)
                    .build(),
            )
            generateKey()
        }
        return try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false) // still hardware-backed (TEE) on any phone Seam supports
        }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(file)) { "couldn't replace ${file.name}" }
    }

    private companion object {
        const val ALIAS = "seam.storage.v1"
    }
}

/** Non-secret settings: which relay and group this phone belongs to. */
interface SessionPrefs {
    var relayUrl: String?
    var groupId: ByteArray?
}
