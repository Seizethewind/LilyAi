package ai.lily.app.plugins

/** When a plugin that ships inside the app and is already installed should be replaced by the copy the app ships. */
object BundledUpdate {
    /**
     * True when [bundled] is a higher version than [installed]. Both must look like 1.2.3; anything else counts as not newer,
     * so a strange version never replaces a plugin.
     */
    fun isNewer(bundled: String, installed: String): Boolean {
        val a = parts(bundled) ?: return false
        val b = parts(installed) ?: return false
        for (i in 0 until 3) {
            if (a[i] != b[i]) return a[i] > b[i]
        }
        return false
    }

    private fun parts(version: String): List<Int>? {
        val pieces = version.split('.')
        if (pieces.size != 3) return null
        val numbers = ArrayList<Int>(3)
        for (piece in pieces) numbers += piece.toIntOrNull() ?: return null
        return numbers
    }
}
