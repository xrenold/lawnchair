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
import app.lawnchair.util.isDefaultLauncher
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

    SetupPreferenceGroup(visible = startEnabled.state.value)

    PreferenceGroup(heading = "Start") {
        SwitchPreference(
            adapter = startEnabled,
            label = "Pane Start screen",
            description = "Live tiles instead of the standard home screen",
        )
        ExpandAndShrink(visible = startEnabled.state.value) {
            val ctx = LocalContext.current
            ClickablePreference(
                label = "Auto layout",
                subtitle = "Hold the arrow at the end of Start to arrange it from your usage. " +
                    if (app.lawnchair.metro.data.MetroUsage.hasUsageAccess(ctx)) "Usage access is on." else "Works better with usage access (see Setup & permissions).",
                onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            )
        }
        ExpandAndShrink(visible = startEnabled.state.value) {
            SwitchPreference(
                adapter = prefs.metroLayoutLocked.getAdapter(),
                label = "Lock Start layout",
                description = "No moving, resizing, unpinning, pinning or auto layout. Tile settings still work.",
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
                    ListPreferenceEntry(MetroTheme.BG_WINDOW) { "Wallpaper through tiles" },
                ),
            )
        }
        ExpandAndShrink(visible = startEnabled.state.value && prefs.metroBackground.getAdapter().state.value == MetroTheme.BG_WINDOW) {
            SwitchPreference(
                adapter = prefs.metroAppListPhoto.getAdapter(),
                label = "Background behind the app list",
                description = "The app list sits over the background instead of black; app squares are solid",
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
                subtitle = "Shown behind the tiles, drifting gently as you scroll. " +
                    "Android doesn't let launchers move the system wallpaper this way.",
                onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            ClickablePreference(
                label = "Generate gradient",
                subtitle = "A soft random colour gradient, shuffled in a preview. While music plays, Start's " +
                    "background takes the album's colours, and goes back when the music stops.",
                onClick = { app.lawnchair.metro.start.BackgroundPreviewActivity.startGradient(context) },
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
            val iconCtx = LocalContext.current
            val packs = androidx.compose.runtime.remember {
                val pm = iconCtx.packageManager
                app.lawnchair.ui.preferences.iconPackIntents
                    .flatMap { runCatching { pm.queryIntentActivities(it, 0) }.getOrDefault(emptyList()) }
                    .associateBy { it.activityInfo.packageName }
                    .map { (pkg, info) -> pkg to info.loadLabel(pm).toString() }
                    .sortedBy { it.second.lowercase() }
            }
            ListPreference(
                adapter = prefs.metroIconPack.getAdapter(),
                label = "Icon pack",
                description = "Line packs like Arcticons suit tiles best. Apps the pack doesn't cover keep their usual icon.",
                entries = listOf(ListPreferenceEntry("") { "None (system icons)" }) +
                    packs.map { (pkg, name) -> ListPreferenceEntry(pkg) { name } },
            )
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
                    ListPreferenceEntry(MetroTheme.MODE_CLASSIC) { "Classic accents" },
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
        PreferenceGroup(heading = "Info tiles") {
            val ctx = LocalContext.current
            ClickablePreference(
                label = "Calendars shown",
                subtitle = "Choose which calendars appear on the calendar tile",
                onClick = {
                    val cals = app.lawnchair.metro.info.InfoTiles.calendars(ctx)
                    if (cals.isEmpty()) {
                        Toast.makeText(ctx, "Allow calendar access first", Toast.LENGTH_SHORT).show()
                    } else {
                        val hiddenPref = prefs.metroHiddenCalendars
                        val hidden = hiddenPref.get().split(',').mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
                        val checked = BooleanArray(cals.size) { cals[it].first !in hidden }
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("Calendars shown")
                            .setMultiChoiceItems(cals.map { it.second }.toTypedArray(), checked) { _, i, on ->
                                if (on) hidden -= cals[i].first else hidden += cals[i].first
                            }
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                hiddenPref.set(hidden.joinToString(","))
                                app.lawnchair.metro.info.InfoTiles.refreshCalendar()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                },
            )
            // Live tile apps: which app's tile carries each info tile.
            listOf(
                app.lawnchair.metro.info.InfoKind.CALENDAR to "Calendar tile app",
                app.lawnchair.metro.info.InfoKind.PHOTOS to "Photos tile app",
                app.lawnchair.metro.info.InfoKind.WEATHER to "Weather tile app",
                app.lawnchair.metro.info.InfoKind.CLOCK to "Clock tile app",
            ).forEach { (kind, title) ->
                val pref = app.lawnchair.metro.info.InfoTiles.appPref(ctx, kind)
                val chosen = pref.getAdapter().state.value
                val chosenLabel = if (chosen.isEmpty()) {
                    "Automatic"
                } else {
                    runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(chosen, 0)).toString() }.getOrDefault(chosen)
                }
                ClickablePreference(
                    label = title,
                    subtitle = chosenLabel,
                    onClick = { MetroAppPicker.show(ctx, title, chosen) { pkg -> app.lawnchair.metro.info.InfoTiles.setApp(ctx, kind, pkg) } },
                )
            }
            SwitchPreference(
                adapter = prefs.metroAllDayEvents.getAdapter(),
                label = "Show all-day events",
                description = "On the calendar tile and agenda",
            )
            SwitchPreference(
                adapter = prefs.metroPhotoSlideshow.getAdapter(),
                label = "Photo slideshow",
                description = if (app.lawnchair.metro.info.PhotoPicks.pickerOnly) {
                    "The Photos tile shows the photos you chose"
                } else {
                    "The Photos tile shows camera photos from the last 30 days"
                },
            )
        }
    }

    ExpandAndShrink(visible = startEnabled.state.value) {
        PreferenceGroup(heading = "Troubleshooting") {
            val ctx = LocalContext.current
            ClickablePreference(
                label = "Crash reports",
                subtitle = "Saved on this phone only. Tap to see them and share one.",
                onClick = {
                    val reports = app.lawnchair.metro.CrashLog.reports(ctx)
                    if (reports.isEmpty()) {
                        Toast.makeText(ctx, "No crashes so far", Toast.LENGTH_SHORT).show()
                    } else {
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("Crash reports")
                            .setItems(reports.map { "${it.second}\n${it.third}" }.toTypedArray()) { _, i ->
                                app.lawnchair.metro.CrashLog.share(ctx, reports[i].first)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                },
            )
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
            SwitchPreference(
                adapter = prefs.metroMessagePeek.getAdapter(),
                label = "Show message text on tiles",
                description = "Live tiles can show the sender and first line of new messages",
            )
        }
    }

    ExpandAndShrink(visible = startEnabled.state.value) {
        PreferenceGroup(heading = "About Pane") {
            val ctx = LocalContext.current
            ClickablePreference(
                label = "Version",
                subtitle = com.android.launcher3.BuildConfig.VERSION_NAME,
                onClick = {},
            )
            ClickablePreference(
                label = "Privacy",
                subtitle = "Nothing you see in Pane leaves your phone, apart from a weather lookup for your approximate area.",
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
            ClickablePreference(
                label = "Credits and licences",
                subtitle = "Pane is open source (GPL-3.0), built on Lawnchair and Android's Launcher3. Font: Selawik.",
                onClick = {
                    val text = runCatching { ctx.assets.open("pane_licenses.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle("Credits and licences")
                        .setMessage(text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                },
            )
        }
    }
}

private const val PRIVACY_URL = "https://github.com/xrenold/lawnchair/blob/metro/PRIVACY.md"

/** A simple list of launchable apps, with "Automatic" at the top. */
private object MetroAppPicker {
    fun show(context: android.content.Context, title: String, current: String, onPick: (String) -> Unit) {
        val la = context.getSystemService(android.content.pm.LauncherApps::class.java)
        val collator = java.text.Collator.getInstance()
        val apps = la?.getActivityList(null, android.os.Process.myUserHandle()).orEmpty()
            .distinctBy { it.componentName.packageName }
            .filter { it.componentName.packageName != context.packageName }
            .sortedWith { a, b -> collator.compare(a.label.toString(), b.label.toString()) }
        val labels = listOf("Automatic") + apps.map { it.label.toString() }
        val values = listOf("") + apps.map { it.componentName.packageName }
        android.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), values.indexOf(current).coerceAtLeast(0)) { d, i ->
                onPick(values[i])
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

/**
 * Setup & permissions: everything Pane can be allowed to do, each with its state, in one place.
 * Tapping an item opens the matching Android screen; "Run setup again" replays the first-run
 * story. States are re-read whenever this screen comes back into view.
 */
@Composable
private fun SetupPreferenceGroup(visible: Boolean) {
    ExpandAndShrink(visible = visible) {
        val ctx = LocalContext.current
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        val tick = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
        androidx.compose.runtime.DisposableEffect(lifecycle) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick.intValue++
            }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }
        tick.intValue // read, so the states below refresh on resume
        val infoTiles = app.lawnchair.metro.info.InfoTiles
        val picker = app.lawnchair.metro.info.PhotoPicks.pickerOnly
        val infoNeeded = listOfNotNull(
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            infoTiles.permissionFor(app.lawnchair.metro.info.InfoKind.PHOTOS),
        )
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick.intValue++ }
        fun open(intent: Intent) = runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        fun state(on: Boolean, offText: String) = if (on) "On" else offText

        PreferenceGroup(heading = "Setup & permissions") {
            ClickablePreference(
                label = "Home app",
                subtitle = if (ctx.isDefaultLauncher()) "Pane is your home app" else "Tap to make Pane your home app",
                onClick = { open(Intent(Settings.ACTION_HOME_SETTINGS)) },
            )
            ClickablePreference(
                label = "Notification access",
                subtitle = state(
                    androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName),
                    "Off: live tiles and counts need it. On Samsung, also turn on Settings › Notifications › App icon badges.",
                ),
                onClick = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            )
            val missing = infoNeeded.filter { !infoTiles.hasPermission(ctx, it) }
            ClickablePreference(
                label = if (picker) "Calendar and weather" else "Calendar, weather and photos",
                subtitle = state(missing.isEmpty(), "Some are off. Tap to allow, so those tiles come alive. Weather uses approximate location."),
                onClick = { if (missing.isNotEmpty()) ask.launch(missing.toTypedArray()) else open(appDetails(ctx)) },
            )
            if (picker) {
                val count = app.lawnchair.metro.info.PhotoPicks.count(ctx)
                ClickablePreference(
                    label = "Photos for the Photos tile",
                    subtitle = if (count == 0) "None chosen yet. Tap to choose some." else "$count chosen. Tap to choose a new set; add more from the tile's menu.",
                    onClick = { app.lawnchair.metro.info.PhotoPicks.open(ctx, replace = true) },
                )
            }
            ClickablePreference(
                label = "Usage access",
                subtitle = state(app.lawnchair.metro.data.MetroUsage.hasUsageAccess(ctx), "Off: auto layout works better with it"),
                onClick = { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            )
            ClickablePreference(
                label = "Run setup again",
                subtitle = "The welcome screens from the first run",
                onClick = { app.lawnchair.metro.setup.PaneSetupActivity.start(ctx) },
            )
        }
    }
}

private fun appDetails(ctx: android.content.Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", ctx.packageName, null))
