package ai.lily.app.editor

import ai.lily.app.plugins.PluginServices
import ai.lily.app.ui.ButtonKind
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
fun PluginScreenHost(screen: String, services: PluginServices, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    when (screen) {
        "discord" -> DiscordScreen(services, onBack, modifier)
        else -> Column(modifier.fillMaxWidth()) {
            ScreenHeader(
                "Unknown screen",
                subtitle = "This plugin names a screen this version of the app does not have.",
                actions = { LilyButton("Back", onBack, kind = ButtonKind.Quiet) },
            )
        }
    }
}
