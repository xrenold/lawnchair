package app.lawnchair.ui.preferences.destinations

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.lawnchair.metro.live.LiveTileData
import app.lawnchair.metro.start.ParallaxBackgroundView
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
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
            val ctx = LocalContext.current
            ClickablePreference(
                label = "Auto layout",
                subtitle = "Hold the arrow at the end of Start to arrange it from your usage. " +
                    if (app.lawnchair.metro.data.MetroUsage.hasUsageAccess(ctx)) "Usage access is on." else "Tap to allow usage access for better results.",
                onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            )
        }
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

    ExpandAndShrink(visible = startEnabled.state.value && prefs.metroBackground.getAdapter().state.value != MetroTheme.BG_BLACK) {
        PreferenceGroup(heading = "Background photo") {
            val context = LocalContext.current
            val photoVersion = prefs.metroBackgroundPhoto.getAdapter()
            val hasPhoto = photoVersion.state.value > 0 && ParallaxBackgroundView.file(context).exists()
            // A picked photo opens a full-screen preview with your tiles over it; it's saved
            // (cropped to what you positioned) only when you tap Apply there.
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
                if (uri == null) return@rememberLauncherForActivityResult
                runCatching { app.lawnchair.metro.start.BackgroundPreviewActivity.start(context, uri) }
                    .onFailure { Toast.makeText(context, "Couldn't use that photo", Toast.LENGTH_SHORT).show() }
            }
            ClickablePreference(
                label = if (hasPhoto) "Change background photo" else "Choose background photo",
                subtitle = "Shown behind the tiles with the Windows Phone 8.1 parallax drift. " +
                    "Android doesn't let launchers move the system wallpaper this way.",
                onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            ExpandAndShrink(visible = hasPhoto) {
                ClickablePreference(
                    label = "Use system wallpaper instead",
                    onClick = {
                        ParallaxBackgroundView.file(context).delete()
                        photoVersion.onChange(0)
                    },
                )
            }
        }
    }

    ExpandAndShrink(visible = startEnabled.state.value) {
        PreferenceGroup(heading = "Tiles & colors") {
            SliderPreference(
                label = "Tile icon size",
                adapter = prefs.metroIconSize.getAdapter(),
                valueRange = 50..150,
                step = 5,
                showUnit = "%",
            )
            ListPreference(
                adapter = prefs.metroLegibility.getAdapter(),
                label = "Background dim",
                description = "Dims bright wallpapers and photos just enough for tiles and text to stay readable. Dark backgrounds aren't dimmed.",
                entries = listOf(
                    ListPreferenceEntry(0) { "Off" },
                    ListPreferenceEntry(1) { "Auto" },
                    ListPreferenceEntry(2) { "Auto + stronger" },
                ),
            )
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
            val context = LocalContext.current
            SwitchPreference(
                adapter = prefs.metroLiveTiles.getAdapter(),
                label = "Live tiles",
                description = "Tiles flip to show new messages and what's playing",
            )
            if (!LiveTileData.hasAccess()) {
                ClickablePreference(
                    label = "Allow notification access",
                    subtitle = "Needed for live tiles and counts. On Samsung, also turn on " +
                        "Settings › Notifications › App icon badges.",
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
            SwitchPreference(
                adapter = prefs.metroMessagePeek.getAdapter(),
                label = "Show message text on tiles",
                description = "Live tiles can show the sender and first line of new messages",
            )
        }
    }
}
