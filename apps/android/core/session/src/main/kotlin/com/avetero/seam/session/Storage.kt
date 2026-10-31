// SPDX-License-Identifier: AGPL-3.0-only
package com.avetero.seam.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.avetero.seam.protocol.v1.Envelope
import java.io.File
import java.nio.ByteBuffer
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

    /** Durable (file fsynced before rename, directory after). Throws on failure: callers must not send what they couldn't save. */
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
        syncDir() // the renames themselves survive a power cut (SR-2-68)
    }

    private fun syncDir() {
        val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.EINVAL) throw e // some filesystems can't sync a directory: nothing more to do
        } finally {
            Os.close(fd)
        }
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
        syncDir()
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
 * Envelopes sent over the direct link that the Mac hasn't acked yet, so they survive the process
 * being killed. They're already end-to-end sealed, so this holds nothing readable.
 */
interface PendingStore {
    /** Oldest first. Anything unreadable comes back as empty: never a crash, never a blocked start. */
    fun load(): List<Envelope>

    /** Replaces the whole list, atomically. Throws if it couldn't. */
    fun save(envelopes: List<Envelope>)
}

class MemoryPendingStore : PendingStore {
    private var saved = listOf<Envelope>()
    override fun load() = saved
    override fun save(envelopes: List<Envelope>) {
        saved = envelopes.toList()
    }
}

/** One file, `version 1 | (u32 length | Envelope)*`, replaced by write-sync-rename. Keep it in noBackupFilesDir. */
class FilePendingStore(private val dir: File) : PendingStore {
    private val file = File(dir, "pending.bin")

    override fun load(): List<Envelope> = runCatching {
        if (!file.exists()) return emptyList()
        val b = ByteBuffer.wrap(file.readBytes())
        if (b.get() != 1.toByte()) return emptyList()
        buildList {
            while (b.hasRemaining()) {
                val n = b.int
                if (n !in 0..MAX_ENVELOPE || n > b.remaining()) return emptyList()
                add(Envelope.parseFrom(ByteArray(n).also { b.get(it) }))
            }
        }.takeLast(MAX_ENTRIES)
    }.getOrDefault(emptyList())

    override fun save(envelopes: List<Envelope>) {
        if (envelopes.isEmpty()) {
            if (file.exists()) check(file.delete()) { "couldn't clear pending" }
            return
        }
        val parts = envelopes.map { it.toByteArray() }
        val out = ByteBuffer.allocate(1 + parts.sumOf { 4 + it.size }).put(1)
        parts.forEach { out.putInt(it.size).put(it) }
        dir.mkdirs()
        val tmp = File(dir, "pending.bin.tmp")
        FileOutputStream(tmp).use {
            it.write(out.array())
            it.fd.sync()
        }
        check(tmp.renameTo(file)) { "couldn't replace pending" }
    }

    private companion object {
        const val MAX_ENVELOPE = 1_200_000
        const val MAX_ENTRIES = 100
    }
}
