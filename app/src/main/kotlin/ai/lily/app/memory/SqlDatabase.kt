package ai.lily.app.memory

/** One SQL statement with its arguments. */
data class Stmt(val sql: String, val args: List<Any?> = emptyList())

/**
 * What the memory and knowledge code needs from a database. [TursoClient] is the real one; tests use an in-memory fake.
 */
interface SqlDatabase {
    suspend fun execute(sql: String, args: List<Any?> = emptyList()): TursoResult

    /**
     * Runs all statements as one transaction: either every one is applied or none is. Returns one result per statement, in order.
     * Throws if any statement fails (after rolling back).
     */
    suspend fun transaction(statements: List<Stmt>): List<TursoResult>
}
