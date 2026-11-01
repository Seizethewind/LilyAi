package ai.lily.app.memory

import java.sql.Connection
import java.sql.DriverManager

/**
 * A real SQLite database in memory, for tests that need actual SQL rather than a fake.
 * [transaction] commits all statements or none, like [TursoClient]; a failure throws [IllegalStateException] with the database's message.
 * Values come back as text, as they do from Turso.
 */
class JdbcSqlDatabase : SqlDatabase, AutoCloseable {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    override suspend fun execute(sql: String, args: List<Any?>): TursoResult = exec(sql, args)

    override suspend fun transaction(statements: List<Stmt>): List<TursoResult> {
        conn.autoCommit = false
        try {
            val results = statements.map { exec(it.sql, it.args) }
            conn.commit()
            return results
        } catch (e: Exception) {
            conn.rollback()
            throw IllegalStateException(e.message, e)
        } finally {
            conn.autoCommit = true
        }
    }

    private fun exec(sql: String, args: List<Any?>): TursoResult {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            if (ps.execute()) {
                val rs = ps.resultSet
                val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                val rows = mutableListOf<List<String?>>()
                while (rs.next()) rows += columns.indices.map { rs.getString(it + 1) }
                return TursoResult(columns, rows, 0, null)
            }
            return TursoResult(emptyList(), emptyList(), ps.updateCount.toLong(), null)
        }
    }

    override fun close() = conn.close()
}
