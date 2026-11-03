package ai.lily.app.services

import ai.lily.app.memory.ScriptedGateway
import ai.lily.app.memory.TursoConfig
import ai.lily.app.sandbox.PandaStackConfig
import ai.lily.app.settings.AppSettings
import ai.lily.app.settings.EmbeddingServer
import ai.lily.app.settings.EmbeddingSettings
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ServiceBuilderTest {
    private val knowledge = TursoConfig("libsql://knowledge.example", "t")

    @Test
    fun withoutAKnowledgeDatabaseTheLayerIsNotConfigured() {
        val status = ServiceBuilder.knowledge(AppSettings(), ScriptedGateway())
        assertTrue(status is KnowledgeStatus.NotConfigured)
    }

    @Test
    fun theKnowledgeDatabaseMustNotBeTheFactsDatabase() {
        val same = TursoConfig("libsql://same.example", "t")
        val status = ServiceBuilder.knowledge(AppSettings(knowledgeDb = same, factsDb = same), ScriptedGateway())
        assertTrue(status is KnowledgeStatus.Problem)
        assertTrue((status as KnowledgeStatus.Problem).message.isNotBlank())
    }

    @Test
    fun anUnusableEmbeddingSettingIsReportedNotThrown() {
        val settings = AppSettings(
            knowledgeDb = knowledge,
            embedding = EmbeddingSettings(model = "auto", primary = EmbeddingServer("http://h/v1")),
        )
        val status = ServiceBuilder.knowledge(settings, ScriptedGateway())
        assertTrue(status is KnowledgeStatus.Problem)
        assertTrue((status as KnowledgeStatus.Problem).message.contains("auto"))
    }

    @Test
    fun theVerifierNeedsAPandastackKey() {
        val root = Files.createTempDirectory("lily-verify").toFile()
        try {
            assertNull(ServiceBuilder.verifier(AppSettings(), root))
            assertNotNull(ServiceBuilder.verifier(AppSettings(pandastack = PandaStackConfig(apiKey = "k")), root))
        } finally {
            root.deleteRecursively()
        }
    }
}
