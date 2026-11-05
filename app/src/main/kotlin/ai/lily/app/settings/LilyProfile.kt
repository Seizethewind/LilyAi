package ai.lily.app.settings

/**
 * How Lily herself is set up. [router] is 0 or 1 and picks the router her planning goes to; [model] is the model she plans with
 * (blank keeps the planner's own choice). Workers keep their own routers and models. [instructions] are added to every model
 * call after the caller's own rules. [photoPath] is a file in the app's private storage, or blank for the default mark.
 */
data class LilyProfile(
    val name: String = DEFAULT_NAME,
    val instructions: String = "",
    val router: Int = 0,
    val model: String = "",
    val photoPath: String = "",
) {
    companion object {
        const val DEFAULT_NAME = "Lily"

        /** Reads a profile through [get], which returns "" for a key that was never saved, so settings from before this existed load as the defaults. */
        fun read(get: (String) -> String) = LilyProfile(
            name = get("lily_name").trim().ifBlank { DEFAULT_NAME },
            instructions = get("lily_instructions"),
            router = if (get("lily_router").trim() == "1") 1 else 0,
            model = get("lily_model").trim(),
            photoPath = get("lily_photo"),
        )
    }
}
