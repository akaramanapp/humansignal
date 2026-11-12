package io.humansignal.sdk.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class LocalStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    // JVM testinde Keystore yok: aynı AES-GCM kodu yazılımsal bir anahtarla sınanır.
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmCipher { key }

    private val sidA = "aaaaaaaa-0000-0000-0000-000000000001"
    private val sidB = "bbbbbbbb-0000-0000-0000-000000000002"

    private fun chunk(seq: Int, size: Int = 1000): EncodedChunk {
        val gz = ByteArray(size) { (it * 31 + seq).toByte() }
        return EncodedChunk(seq, seq * 5L, seq * 5L + 5, gz, ChunkEncoder.sha256Hex(gz), 3, listOf("sensor"), size * 4)
    }

    @Test fun aesGcmRoundTripAndLayout() {
        val plain = "merhaba dünya".repeat(100).toByteArray()
        val blob = cipher.encrypt(plain)
        assertEquals(12 + plain.size + 16, blob.size)          // [IV][ct][tag]
        assertArrayEquals(plain, cipher.decrypt(blob))
        assertNotEquals(blob.toList(), cipher.encrypt(plain).toList()) // her seferinde yeni IV
    }

    @Test fun aesGcmDetectsTampering() {
        val blob = cipher.encrypt(ByteArray(64) { 7 })
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        try { cipher.decrypt(blob); fail("expected tag failure") } catch (_: AEADBadTagException) {}
    }

    @Test fun writesEncryptedChunkAndReadsItBack() {
        val store = LocalStore(tmp.root, cipher, 10_000_000)
        val c = chunk(0)
        val r = store.writeChunk(sidA, null, c)
        val f = File(tmp.root, "$sidA/000000.ndjson.gz.enc")
        assertTrue(f.exists())
        assertFalse("must not be stored in plaintext", f.readBytes().contentEquals(c.gzip))
        assertArrayEquals(c.gzip, store.readChunk(r.entry))
        assertEquals(c.sha256, r.entry.sha256)
        assertEquals(1, store.pendingCount())
    }

    @Test fun queuePersistsAcrossInstances() {
        LocalStore(tmp.root, cipher, 10_000_000).apply {
            writeChunk(sidA, null, chunk(0)); writeChunk(sidA, null, chunk(1))
        }
        val reopened = LocalStore(tmp.root, cipher, 10_000_000)
        assertEquals(listOf(0, 1), reopened.pending().map { it.seq })
        reopened.writeChunk(sidB, null, chunk(0))
        assertEquals(listOf(sidA, sidA, sidB), reopened.pending().map { it.localSessionId }) // sıra korunur
    }

    @Test fun corruptQueueIsRebuiltFromDisk() {
        LocalStore(tmp.root, cipher, 10_000_000).apply { writeChunk(sidA, null, chunk(0)) }
        File(tmp.root, "queue.json").writeText("{bozuk")
        val s = LocalStore(tmp.root, cipher, 10_000_000)
        assertEquals(1, s.pendingCount())
        assertEquals(chunk(0).sha256, s.pending()[0].sha256)
    }

    @Test fun uploadedChunkIsDeletedImmediately() {
        val store = LocalStore(tmp.root, cipher, 10_000_000)
        store.writeChunk(sidA, null, chunk(0))
        store.markUploaded(sidA, 0)
        assertFalse(File(tmp.root, "$sidA/000000.ndjson.gz.enc").exists())
        assertEquals(0, store.pendingCount())
    }

    @Test fun quotaDropsOldestPendingAndCountsThem() {
        val perChunk = 12 + 1000 + 16L
        val store = LocalStore(tmp.root, cipher, perChunk * 3)   // en fazla 3 parça
        store.writeSessionMeta(sidA, mapOf("local_session_id" to sidA, "dropped_chunks" to 0))
        store.writeSessionMeta(sidB, mapOf("local_session_id" to sidB))
        store.writeChunk(sidA, null, chunk(0))
        store.writeChunk(sidA, null, chunk(1))
        store.writeChunk(sidB, null, chunk(0))
        assertEquals(3, store.pendingCount())

        val r = store.writeChunk(sidB, null, chunk(1))
        assertEquals(listOf(sidA to 0), r.droppedEntries.map { it.localSessionId to it.seq })
        assertEquals(3, store.pendingCount())
        assertFalse(File(tmp.root, "$sidA/000000.ndjson.gz.enc").exists())
        assertEquals(1L, store.readSessionMeta(sidA)!!["dropped_chunks"])
        assertTrue(store.pendingBytes() <= perChunk * 3)
    }

    @Test fun moveAsideAndDeleteAll() {
        val store = LocalStore(tmp.root, cipher, 10_000_000)
        store.writeChunk(sidA, null, chunk(0)); store.writeChunk(sidA, null, chunk(1))
        store.moveAside(sidA, 0, "conflict")
        assertTrue(File(tmp.root, "conflict/$sidA/000000.ndjson.gz.enc").exists())
        assertEquals(listOf(1), store.pending().map { it.seq })

        store.deleteAll()
        assertEquals(0, store.pendingCount())
        assertEquals(listOf("queue.json"), tmp.root.list()!!.toList())
    }

    @Test fun serverSessionIdIsPropagated() {
        val store = LocalStore(tmp.root, cipher, 10_000_000)
        store.writeChunk(sidA, null, chunk(0))
        store.setServerSessionId(sidA, "a3d1-server")
        assertEquals("a3d1-server", LocalStore(tmp.root, cipher, 10_000_000).pending()[0].serverSessionId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathTraversalSessionIds() {
        LocalStore(tmp.root, cipher, 1000).sessionDir("../../etc")
    }
}
