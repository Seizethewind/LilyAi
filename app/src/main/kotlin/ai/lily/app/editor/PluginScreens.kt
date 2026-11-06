package ai.lily.app.editor

import ai.lily.app.ui.ButtonKind
import ai.lily.app.ui.EmptyState
import ai.lily.app.ui.LilyButton
import ai.lily.app.ui.ScreenHeader
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shows the screen a plugin's Open button names. The names are the ones in [ai.lily.app.plugins.ManifestParser.SCREENS]: a
 * manifest can only point at a screen this app has built, so there is nothing here that a plugin supplies.
 */
@Composable
fun PluginScreenHost(screen: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    when (screen) {
        "discord" -> DiscordScreen(onBack, modifier)
        else -> Column(modifier.fillMaxWidth()) {
            ScreenHeader(
                "Unknown screen",
                subtitle = "This plugin names a screen this version of the app does not have.",
                actions = { LilyButton("Back", onBack, kind = ButtonKind.Quiet) },
            )
        }
    }
}

/** A placeholder: the channel viewer is built in the next steps. */
@Composable
private fun DiscordScreen(onBack: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        ScreenHeader(
            "Discord",
            subtitle = "Your server's channels, through your own bot.",
            actions = { LilyButton("Back", onBack, kind = ButtonKind.Quiet) },
        )
        EmptyState(
            title = "Not built yet",
            message = "The channel viewer is the next step. This is the screen the Open button will show it on.",
        )
    }
}
