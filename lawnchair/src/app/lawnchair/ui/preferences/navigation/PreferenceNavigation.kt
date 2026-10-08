package app.lawnchair.ui.preferences.navigation

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import app.lawnchair.backup.ui.CreateBackupScreen
import app.lawnchair.backup.ui.restoreBackupGraph
import app.lawnchair.preferences.BasePreferenceManager
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.about.About
import app.lawnchair.ui.preferences.components.colorpreference.ColorPreferenceModelList
import app.lawnchair.ui.preferences.components.colorpreference.ColorSelection
import app.lawnchair.ui.preferences.components.search.SearchProviderId
import app.lawnchair.ui.preferences.components.search.SearchProviderPreferenceScreen
import app.lawnchair.ui.preferences.destinations.BackupAndRestorePreference
import app.lawnchair.ui.preferences.destinations.CustomIconShapePreference
import app.lawnchair.ui.preferences.destinations.DummyPreference
import app.lawnchair.ui.preferences.destinations.FeatureFlagsPreference
import app.lawnchair.ui.preferences.destinations.IconPackPreferences
import app.lawnchair.ui.preferences.destinations.PreferencesDashboard
import app.lawnchair.ui.preferences.destinations.SearchPreferences
import app.lawnchair.ui.preferences.destinations.SearchProviderPreferences
import app.lawnchair.ui.preferences.destinations.SelectIconPreference
import app.lawnchair.ui.preferences.destinations.ShapePreference
import com.android.launcher3.util.ComponentKey
import soup.compose.material.motion.animation.materialSharedAxisXIn
import soup.compose.material.motion.animation.materialSharedAxisXOut
import soup.compose.material.motion.animation.rememberSlideDistance

inline fun <reified T> getDeepLink(route: T) where T : PreferenceRoute, T : PreferenceDeepLink = listOf(navDeepLink<T>(basePath = route.deepLink))

@Composable
fun PreferenceNavigation(
    navController: NavHostController,
    startDestination: PreferenceRoute,
    intent: Intent? = null,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val slideDistance = rememberSlideDistance()

    LaunchedEffect(intent) {
        intent?.let { navController.handleDeepLink(it) }
    }

    // TODO: navigate to nav3: https://developer.android.com/guide/navigation/navigation-3
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { materialSharedAxisXIn(!isRtl, slideDistance) },
        exitTransition = { materialSharedAxisXOut(!isRtl, slideDistance) },
        popEnterTransition = { materialSharedAxisXIn(isRtl, slideDistance) },
        popExitTransition = { materialSharedAxisXOut(isRtl, slideDistance) },
        predictivePopEnterTransition = { materialSharedAxisXIn(isRtl, slideDistance) },
        predictivePopExitTransition = { materialSharedAxisXOut(isRtl, slideDistance) },
    ) {
        composable<Root> {
            // Pane: settings are Pane's own page plus backup & restore; Lawnchair's pages are gone.
            PreferencesDashboard(
                currentRoute = Root,
                onNavigate = {
                    navController.navigate(it)
                },
            )
        }
        composable<Dummy> {
            DummyPreference()
        }

        composable<BackupAndRestore>(
            deepLinks = getDeepLink(BackupAndRestore),
        ) { BackupAndRestorePreference() }

        composable<CreateBackup>(
            deepLinks = getDeepLink(CreateBackup),
        ) { CreateBackupScreen(viewModel()) }

        restoreBackupGraph()
    }
}
