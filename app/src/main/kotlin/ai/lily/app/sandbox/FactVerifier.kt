package ai.lily.app.sandbox

import ai.lily.app.core.EvidenceSource
import ai.lily.app.core.ProvenFacts

/** Promotes a statement to Proven facts only if a sandbox run proves it (exit code 0). */
class FactVerifier(
    private val sandbox: PandaStackClient,
    private val facts: ProvenFacts,
) {
    suspend fun verifyAndPromote(statement: String, command: String, journalId: Long? = null): Boolean {
        val result = sandbox.runOnce(command)
        if (result.exitCode != 0) return false
        facts.promote(
            statement = statement,
            evidence = "Command exited 0 in Pandastack: $command\nOutput: ${result.stdout.take(1000)}",
            source = EvidenceSource.SANDBOX_RUN,
            journalId = journalId,
        )
        return true
    }
}
