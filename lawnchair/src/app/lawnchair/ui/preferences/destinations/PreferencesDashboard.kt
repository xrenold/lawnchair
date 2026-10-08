/*
 * Copyright 2021, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.ui.preferences.destinations

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import app.lawnchair.LawnchairApp
import app.lawnchair.LawnchairLauncher
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.observeAsState
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.firstCached
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.OverflowMenuGrouped
import app.lawnchair.ui.preferences.components.controls.PreferenceCategory
import app.lawnchair.ui.preferences.components.controls.WarningPreference
import app.lawnchair.ui.preferences.components.layout.ClickableIcon
import app.lawnchair.ui.preferences.components.layout.ExpandAndShrink
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.components.layout.ProvideDescriptionTextStyle
import app.lawnchair.ui.preferences.navigation.About
import app.lawnchair.ui.preferences.navigation.AppDrawer
import app.lawnchair.ui.preferences.navigation.BackupAndRestore
import app.lawnchair.ui.preferences.navigation.DebugMenu
import app.lawnchair.ui.preferences.navigation.Dock
import app.lawnchair.ui.preferences.navigation.ExperimentalFeatures
import app.lawnchair.ui.preferences.navigation.Folders
import app.lawnchair.ui.preferences.navigation.General
import app.lawnchair.ui.preferences.navigation.Gestures
import app.lawnchair.ui.preferences.navigation.HomeScreen
import app.lawnchair.ui.preferences.navigation.PreferenceRootRoute
import app.lawnchair.ui.preferences.navigation.Quickstep
import app.lawnchair.ui.preferences.navigation.Search
import app.lawnchair.ui.preferences.navigation.Smartspace
import app.lawnchair.ui.util.addIf
import app.lawnchair.util.isDefaultLauncher
import app.lawnchair.util.restartLauncher
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken

@Composable
fun PreferencesDashboard(
    currentRoute: PreferenceRootRoute,
    onNavigate: (PreferenceRootRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    PreferenceLayout(
        label = "pane settings",
        modifier = modifier,
        verticalArrangement = Arrangement.Top,
        backArrowVisible = false,
        actions = { PreferencesOverflowMenu(currentRoute = currentRoute, onNavigate = onNavigate) },
    ) {
        if (!context.isDefaultLauncher()) {
            PreferencesSetDefaultLauncherWarning()
            Spacer(modifier = Modifier.height(8.dp))
        }
        MetroPreferenceGroups()
        PreferenceGroup(heading = "more") {
            PreferenceCategory(
                label = "backup & restore",
                description = "Save your Start layout and settings to a file, or restore one",
                iconResource = R.drawable.backup_restore,
                onNavigate = { onNavigate(BackupAndRestore) },
                isSelected = currentRoute is BackupAndRestore,
            )
        }
    }
}

@Composable
fun RowScope.PreferencesOverflowMenu(
    currentRoute: PreferenceRootRoute,
    onNavigate: (PreferenceRootRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
    val highlightShape = MaterialTheme.shapes.large

    val context = LocalContext.current

    OverflowMenuGrouped(
        modifier = modifier.addIf(
            false,
        ) {
            Modifier
                .clip(highlightShape)
                .background(highlightColor)
        },
    ) {
        DropdownMenuGroup(
            shapes = MenuDefaults.groupShape(0, 1),
        ) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_about),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    openAppInfo(context)
                    hideMenu()
                },
                text = {
                    Text(text = stringResource(id = R.string.app_info_drop_target_label))
                },
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    restartLauncher(context)
                    hideMenu()
                },
                text = {
                    Text(text = stringResource(id = R.string.debug_restart_launcher))
                },
            )
        }

        Spacer(Modifier.height(MenuDefaults.GroupSpacing))
    }
}

@Composable
fun PreferencesDebugWarning(
    modifier: Modifier = Modifier,
) {
    WarningPreference(
        // Don't move to strings.xml, no need to translate this warning
        text = "You are using a development build, which may contain bugs and broken features. Use at your own risk!",
        modifier = modifier.padding(horizontal = 16.dp),
        standalone = true,
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    )
}

@Composable
fun PreferencesSetDefaultLauncherWarning(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mMSDLPlayerWrapper = MSDLPlayerWrapper.INSTANCE.get(context)
    Surface(
        modifier = modifier.padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        PreferenceTemplate(
            modifier = Modifier,
            onClick = {
                mMSDLPlayerWrapper.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                Intent(Settings.ACTION_HOME_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .let { context.startActivity(it) }
                (context as? Activity)?.finish()
            },
            title = {
                ProvideDescriptionTextStyle {
                    Text(
                        text = stringResource(id = R.string.set_default_launcher_tip),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            startWidget = {
                Icon(
                    imageVector = Icons.Rounded.TipsAndUpdates,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = null,
                )
            },
        )
    }
}

fun openAppInfo(context: Context) {
    val launcherApps = context.getSystemService<LauncherApps>()
    val componentName = ComponentName(context, LawnchairLauncher::class.java)
    launcherApps?.startAppDetailsActivity(componentName, Process.myUserHandle(), null, null)
}
