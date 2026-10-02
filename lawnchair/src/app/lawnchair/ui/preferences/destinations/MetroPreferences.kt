package app.lawnchair.ui.preferences.destinations

import androidx.compose.runtime.Composable
import app.lawnchair.metro.theme.MetroTheme
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import app.lawnchair.ui.preferences.components.controls.SliderPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.ExpandAndShrink
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup

/** Metro Start screen settings, shown at the top of the Home screen settings page. */
@Composable
fun MetroPreferenceGroups() {
    val prefs = preferenceManager()
    val startEnabled = prefs.metroTiles.getAdapter()

    PreferenceGroup(heading = "Start") {
        SwitchPreference(
            adapter = startEnabled,
            label = "Metro Start screen",
            description = "Windows Phone-style tiles instead of the standard home screen",
        )
        ExpandAndShrink(visible = startEnabled.state.value) {
            SwitchPreference(
                adapter = prefs.metroShowMoreTiles.getAdapter(),
                label = "Show more tiles",
                description = "6 columns of small tiles instead of 4",
            )
        }
        ExpandAndShrink(visible = startEnabled.state.value) {
            ListPreference(
                adapter = prefs.metroBackground.getAdapter(),
                label = "Background",
                entries = listOf(
                    ListPreferenceEntry(MetroTheme.BG_BLACK) { "Black" },
                    ListPreferenceEntry(MetroTheme.BG_WALLPAPER) { "Wallpaper" },
                    ListPreferenceEntry(MetroTheme.BG_WINDOW) { "Wallpaper through tiles (8.1)" },
                ),
            )
        }
    }

    ExpandAndShrink(visible = startEnabled.state.value) {
        PreferenceGroup(heading = "Tiles & colors") {
            val style = prefs.metroTileStyle.getAdapter()
            ListPreference(
                adapter = style,
                label = "Tile style",
                entries = listOf(
                    ListPreferenceEntry("solid") { "Solid" },
                    ListPreferenceEntry("translucent") { "Semi-transparent" },
                ),
            )
            ExpandAndShrink(visible = style.state.value == "translucent") {
                SliderPreference(
                    label = "Tile opacity",
                    adapter = prefs.metroTileOpacity.getAdapter(),
                    valueRange = 10..100,
                    step = 5,
                    showUnit = "%",
                )
            }
            val colorMode = prefs.metroColorMode.getAdapter()
            ListPreference(
                adapter = colorMode,
                label = "Tile colors",
                entries = listOf(
                    ListPreferenceEntry(MetroTheme.MODE_MONET) { "Material You accent" },
                    ListPreferenceEntry(MetroTheme.MODE_MONET_TONAL) { "Material You mix" },
                    ListPreferenceEntry(MetroTheme.MODE_CLASSIC) { "Classic Windows Phone accent" },
                ),
            )
            ExpandAndShrink(visible = colorMode.state.value == MetroTheme.MODE_CLASSIC) {
                ListPreference(
                    adapter = prefs.metroClassicAccent.getAdapter(),
                    label = "Accent color",
                    entries = MetroTheme.CLASSIC_ACCENTS.keys.map { name ->
                        ListPreferenceEntry(name) { name.replaceFirstChar { it.uppercase() } }
                    },
                )
            }
        }
    }

    ExpandAndShrink(visible = startEnabled.state.value) {
        PreferenceGroup(heading = "Live tiles") {
            SwitchPreference(
                adapter = prefs.metroMessagePeek.getAdapter(),
                label = "Show message text on tiles",
                description = "Live tiles can show the sender and first line of new messages",
            )
        }
    }
}
