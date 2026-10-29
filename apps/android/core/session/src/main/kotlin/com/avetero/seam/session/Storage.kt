// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Saved state that can never be opened: its key is gone or wrong. Anything else may be temporary. */
class StateUnreadableException(cause: Throwable) : Exception(cause)

/** Where the core's sealed state blob lives, and the key it's sealed with. */
interface StateStore {
    /** The saved blob, or null on first run. */
    fun load(): ByteArray?

    /**
     * 32 bytes the core seals the state with. Created on first run. Throws [StateUnreadableException]
     * if a saved key can never be unwrapped; other exceptions mean "try again later".
     */
    fun storageKey(): ByteArray

    /** Durable (fsync before rename). Throws on failure: callers must not send what they couldn't save. */
    fun save(blob: ByteArray)

    /** Moves files that can't be opened aside instead of ever overwriting them. Throws if it can't. */
    fun quarantine()
}

/**
 * The storage key is random, and kept on disk only wrapped by a non-exportable AES-256-GCM key in
 * Android Keystore (StrongBox when the phone has it). Copying Seam's files off the phone gets you
 * nothing: unwrapping needs this phone's hardware.
 */
class KeystoreStateStore(private val dir: File) : StateStore {
    private val stateFile = File(dir, "state.bin")
    private val backupFile = File(dir, "state.bin.bak")
    private val wrappedKeyFile = File(dir, "storage_key.bin")
    private var key: ByteArray? = null

    // A crash between the two renames in save() leaves only the backup
    override fun load(): ByteArray? = stateFile.takeIf { it.exists() }?.readBytes() ?: backupFile.takeIf { it.exists() }?.readBytes()

    override fun storageKey(): ByteArray = key ?: unwrapOrCreate().also { key = it }

    override fun save(blob: ByteArray) {
        val tmp = writeSynced("state.bin.tmp", blob)
        if (stateFile.exists()) {
            backupFile.delete()
            check(stateFile.renameTo(backupFile)) { "couldn't keep a backup" }
        }
        check(tmp.renameTo(stateFile)) { "couldn't replace the state" }
    }

    override fun quarantine() {
        val suffix = ".unreadable-${System.currentTimeMillis()}"
        listOf(stateFile, backupFile, wrappedKeyFile).filter { it.exists() }.forEach {
            check(it.renameTo(File(dir, it.name + suffix))) { "couldn't move ${it.name} aside" }
        }
        key = null
    }

    private fun unwrapOrCreate(): ByteArray {
        val wrapping = wrappingKey()
        if (wrappedKeyFile.exists()) {
            val bytes = wrappedKeyFile.readBytes()
            if (bytes.size < 12 + 16) throw StateUnreadableException(IllegalStateException("wrapped key is truncated"))
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(128, bytes, 0, 12))
                return cipher.doFinal(bytes, 12, bytes.size - 12)
            } catch (e: GeneralSecurityException) {
                // Wrong tag (a different Keystore key), or the key was invalidated: never coming back
                if (e is AEADBadTagException || e is InvalidKeyException || e is UnrecoverableKeyException) throw StateUnreadableException(e)
                throw e
            }
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapping) }
        check(writeSynced("storage_key.bin.tmp", cipher.iv + cipher.doFinal(key)).renameTo(wrappedKeyFile)) { "couldn't save the key" }
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

    private fun writeSynced(name: String, bytes: ByteArray): File {
        dir.mkdirs()
        val tmp = File(dir, name)
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        return tmp
    }

    private companion object {
        const val ALIAS = "seam.storage.v1"
    }
}

/**
 * Which relay and group this phone belongs to, kept inside the core's sealed state (appData) so
 * it can never drift apart from the identity: version (1) | u16 URL length | URL | 16-byte group.
 */
internal data class RelayTarget(val url: String, val groupId: ByteArray) {
    fun encode(): ByteArray {
        val u = url.toByteArray()
        return byteArrayOf(1, (u.size shr 8).toByte(), u.size.toByte()) + u + groupId
    }

    companion object {
        fun decode(b: ByteArray): RelayTarget? {
            if (b.size < 3 || b[0] != 1.toByte()) return null
            val n = ((b[1].toInt() and 0xff) shl 8) or (b[2].toInt() and 0xff)
            if (b.size != 3 + n + 16) return null
            return RelayTarget(String(b, 3, n), b.copyOfRange(3 + n, b.size))
        }
    }
}
