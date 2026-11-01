package ai.lily.app.memory

import java.net.URI
import java.security.MessageDigest

/** Where a source's text came from. */
enum class SourceKind { TEXT, WEB }

/** Who wrote a note. AI notes are labelled wherever they are shown. */
enum class NoteOrigin { HUMAN, AI, IMPORTED }

data class Notebook(
    val id: String,
    val name: String,
    val description: String,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A source without its text, so lists stay small. [characters] is the length of the text as the database counts it. */
data class SourceInfo(
    val id: String,
    val kind: SourceKind,
    val title: String,
    val url: String?,
    val contentHash: String,
    val characters: Long,
    val fetchedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Source(val info: SourceInfo, val content: String)

data class Note(
    val id: String,
    val notebookId: String,
    val origin: NoteOrigin,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * An AI-written note is never evidence by itself; only the sources it cites can be. Human and imported notes can be promoted
 * to a proven fact by the person, with a citation as the evidence.
 */
val Note.isEvidenceOnItsOwn: Boolean get() = origin != NoteOrigin.AI

object KnowledgeText {
    fun hash(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Number of characters the way SQLite's length() counts them (code points, not UTF-16 units). */
    fun characters(content: String): Long = content.codePointCount(0, content.length).toLong()

    /**
     * One spelling per web page, so saving the same page twice updates it instead of adding a copy: lower-case scheme and host,
     * no `#fragment`, and no trailing slash on a bare address. Addresses with a username or password are refused so that
     * credentials are never stored.
     */
    fun normaliseUrl(raw: String): String {
        val text = raw.trim()
        require(text.isNotEmpty()) { "The web address is empty." }
        val uri = try {
            URI(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("That is not a valid web address.")
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        require(scheme == "http" || scheme == "https") { "Only http and https addresses can be saved." }
        require(uri.rawUserInfo == null) { "Remove the username and password from the web address." }
        val host = uri.host?.lowercase().orEmpty()
        require(host.isNotEmpty()) { "The web address has no host." }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().let { if (it == "/") "" else it }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$host$port$path$query"
    }
}
