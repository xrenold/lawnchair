package app.lawnchair.metro.start

import android.view.View
import android.widget.Toast
import app.lawnchair.LawnchairLauncher
import app.lawnchair.metro.MetroMode
import app.lawnchair.metro.data.MetroTileStore
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.popup.SystemShortcut

/** "Pin to Start" in an app's long-press menu in the app list. */
class PinToStartShortcut(
    private val launcher: LawnchairLauncher,
    itemInfo: ItemInfo,
    originalView: View,
) : SystemShortcut<LawnchairLauncher>(R.drawable.ic_pin, R.string.metro_pin_to_start, launcher, itemInfo, originalView) {

    override fun onClick(view: View) {
        val component = mItemInfo.targetComponent ?: return
        dismissTaskMenuView()
        if (MetroTileStore.get(launcher).isPinned(component)) {
            Toast.makeText(launcher, R.string.metro_already_pinned, Toast.LENGTH_SHORT).show()
            return
        }
        app.lawnchair.metro.data.LayoutLock.guard(launcher) {
            MetroTileStore.get(launcher).pin(component)
            // Go back to Start so the new tile is visible, as Windows Phone does.
            launcher.stateManager.goToState(LauncherState.NORMAL)
        }
    }

    companion object {
        @JvmField
        val FACTORY = SystemShortcut.Factory<LawnchairLauncher> { launcher, itemInfo, originalView ->
            if (!MetroMode.isStartEnabled(launcher)) return@Factory null
            if (itemInfo.itemType != ITEM_TYPE_APPLICATION || itemInfo.targetComponent == null) return@Factory null
            PinToStartShortcut(launcher, itemInfo, originalView)
        }
    }
}
