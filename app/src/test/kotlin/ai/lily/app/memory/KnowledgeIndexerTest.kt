package ai.lily.app.memory

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real SQL against an in-memory SQLite. Small chunk settings (20 tokens, 25 percent) make a few lines into several chunks. */
class KnowledgeIndexerTest {
    private var clock = 1_000L
    private var sourceCounter = 0
    private var chunkCounter = 0
    private lateinit var db: JdbcSqlDatabase
    private lateinit var repo: KnowledgeRepository
    private lateinit var indexer: KnowledgeIndexer

    private fun lines(n: Int) = (1..n).joinToString("\n") { "L${"%02d".format(it)} ".padEnd(19, 'x') }

    private fun indexer(target: Int = 20, overlap: Int = 25) =
        KnowledgeIndexer(db, now = { clock++ }, newId = { "c${++chunkCounter}" }, targetTokens = target, overlapPercent = overlap)

    @Before
    fun setUp() {
        clock = 1_000L
        sourceCounter = 0
        chunkCounter = 0
        db = JdbcSqlDatabase()
        repo = KnowledgeRepository(db, now = { clock++ }, newId = { "s${++sourceCounter}" })
        indexer = indexer()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun count(table: String) = db.execute("SELECT COUNT(*) FROM $table").rows[0][0]!!.toInt()

    private suspend fun chunkRows(sourceId: String) =
        db.execute("SELECT ordinal, start_line, end_line FROM chunks WHERE source_id = ? ORDER BY ordinal", listOf(sourceId)).rows

    @Test
    fun aSourceIsStoredAsChunksWithLineRanges() = runBlocking<Unit> {
        val s = repo.addTextSource("Ten lines", lines(10))

        val result = indexer.index(s.info.id)

        assertEquals(IndexResult(s.info.id, 3, changed = true), result)
        assertEquals(
            listOf(listOf("0", "1", "4"), listOf("1", "4", "7"), listOf("2", "7", "10")),
            chunkRows(s.info.id),
        )
        val state = db.execute("SELECT content_hash, settings, chunk_count FROM source_index WHERE source_id = ?", listOf(s.info.id)).rows.single()
        assertEquals(listOf(s.info.contentHash, "v1/20/25", "3"), state)
    }

    @Test
    fun indexingACurrentSourceAgainWritesNothing() = runBlocking<Unit> {
        val s = repo.addTextSource("Ten lines", lines(10))
        indexer.index(s.info.id)
        val ids = db.execute("SELECT id FROM chunks ORDER BY id").rows

        val again = indexer.index(s.info.id)

        assertEquals(IndexResult(s.info.id, 3, changed = false), again)
        assertEquals(ids, db.execute("SELECT id FROM chunks ORDER BY id").rows)
    }

    @Test
    fun forceRebuildsEvenWhenCurrent() = runBlocking<Unit> {
        val s = repo.addTextSource("Ten lines", lines(10))
        indexer.index(s.info.id)
        val before = db.execute("SELECT id FROM chunks ORDER BY ordinal").rows

        val result = indexer.index(s.info.id, force = true)

        assertTrue(result!!.changed)
        assertEquals(3, count("chunks"))
        assertNotEquals(before, db.execute("SELECT id FROM chunks ORDER BY ordinal").rows)
    }

    @Test
    fun changedTextIsRechunkedAndTheOldChunksAreGone() = runBlocking<Unit> {
        val first = repo.saveWebSource("https://example.com/p", "Page", lines(10))
        indexer.index(first.info.id)
        val oldIds = db.execute("SELECT id FROM chunks").rows.map { it[0] }.toSet()

        val updated = repo.saveWebSource("https://example.com/p", "Page", lines(5))
        assertEquals(first.info.id, updated.info.id)
        val result = indexer.index(updated.info.id)

        assertEquals(IndexResult(first.info.id, 2, changed = true), result)
        assertEquals(2, count("chunks"))
        assertTrue(db.execute("SELECT id FROM chunks").rows.map { it[0] }.none { it in oldIds })
        assertEquals(listOf(listOf("0", "1", "4"), listOf("1", "4", "5")), chunkRows(first.info.id))
    }

    @Test
    fun differentChunkSettingsRebuildTheChunks() = runBlocking<Unit> {
        val s = repo.addTextSource("Ten lines", lines(10))
        indexer.index(s.info.id)

        val result = indexer(target = 20, overlap = 0).index(s.info.id)

        assertEquals(IndexResult(s.info.id, 3, changed = true), result)
        assertEquals(listOf(listOf("0", "1", "4"), listOf("1", "5", "8"), listOf("2", "9", "10")), chunkRows(s.info.id))
    }

    @Test
    fun aMissingSourceIsNull() = runBlocking<Unit> {
        assertNull(indexer.index("missing"))
    }

    @Test
    fun indexStaleOnlyDoesTheWorkThatIsNeeded() = runBlocking<Unit> {
        val a = repo.addTextSource("A", lines(10))
        repo.addTextSource("B", lines(6))
        val web = repo.saveWebSource("https://example.com/c", "C", lines(8))
        indexer.index(a.info.id)

        assertEquals(2, indexer.indexStale())
        assertEquals(0, indexer.indexStale())

        repo.saveWebSource("https://example.com/c", "C", lines(9))
        assertEquals(1, indexer.indexStale())
        assertEquals(3, db.execute("SELECT COUNT(*) FROM source_index").rows[0][0]!!.toInt())
        assertEquals(listOf("0", "1", "4"), chunkRows(web.info.id).first())
    }

    @Test
    fun aLargeSourceIsWrittenInBatches() = runBlocking<Unit> {
        // 600 lines of 5 tokens, 4 lines to a chunk, no overlap: 150 chunks, written 50 rows to a statement.
        val s = repo.addTextSource("Long", lines(600))
        val noOverlap = indexer(target = 20, overlap = 0)

        val result = noOverlap.index(s.info.id)

        assertEquals(150, result!!.chunks)
        assertEquals(150, count("chunks"))
        assertEquals((0..149).map { it.toString() }, chunkRows(s.info.id).map { it[0] })
    }

    @Test
    fun deletingASourceRemovesItsChunksAndRecord() = runBlocking<Unit> {
        val gone = repo.addTextSource("Gone", lines(10))
        val kept = repo.addTextSource("Kept", lines(10))
        indexer.index(gone.info.id)
        indexer.index(kept.info.id)

        assertTrue(repo.deleteSource(gone.info.id))

        assertEquals(0, chunkRows(gone.info.id).size)
        assertEquals(3, chunkRows(kept.info.id).size)
        assertEquals(1, count("source_index"))
        assertFalse(repo.deleteSource(gone.info.id))
    }
}
