package app.lawnchair.metro.info

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.lawnchair.preferences.PreferenceManager
import com.android.launcher3.BuildConfig

/**
 * Photos the user picked for the Photos tile with the system photo picker.
 *
 * The Google Play build can't read the photo library (Play only allows that for apps whose
 * main job is photos), so there the tile shows picked photos instead of recent camera shots.
 * Access to each picked photo is kept across restarts (a persistable grant), so the slideshow,
 * its motion and face framing work just as with full access. The picker also offers cloud
 * photos (Google Photos) on most phones.
 */
object PhotoPicks {

    /** True in the Play build: no library access, the tile uses picked photos. */
    @JvmStatic
    val pickerOnly: Boolean get() = BuildConfig.PHOTO_PICKER_ONLY

    /** Most photos kept at once. Android caps persisted grants per app, so stay well below. */
    private const val MAX = 100

    fun uris(context: Context): List<Uri> =
        PreferenceManager.getInstance(context).metroPickedPhotos.get()
            .split('\n').filter { it.isNotBlank() }.map(Uri::parse)

    fun count(context: Context) = uris(context).size

    /** How many the picker may return in one go (the phone's limit, at most [MAX]). */
    fun pickLimit(): Int {
        val phone = if (Build.VERSION.SDK_INT >= 33) runCatching { MediaStore.getPickImagesMaxLimit() }.getOrDefault(MAX) else MAX
        return phone.coerceIn(2, MAX)
    }

    /** Adds [picked] (or replaces the set with it), keeping access to them after restarts. */
    fun save(context: Context, picked: List<Uri>, replace: Boolean) {
        if (picked.isEmpty()) return
        val resolver = context.contentResolver
        picked.forEach { uri ->
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val old = uris(context)
        // Newest picks first; the oldest drop off past the limit.
        val kept = (picked + if (replace) emptyList() else old).distinct().take(MAX)
        (old - kept.toSet()).forEach { uri ->
            runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        PreferenceManager.getInstance(context).metroPickedPhotos.set(kept.joinToString("\n"))
        InfoTiles.reloadPhotos()
    }

    /** Opens the photo picker. [replace]: the picked photos replace the current set. */
    fun open(context: Context, replace: Boolean) {
        context.startActivity(
            Intent(context, PhotoPickActivity::class.java)
                .putExtra(PhotoPickActivity.EXTRA_REPLACE, replace)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** An invisible screen that shows the system photo picker and saves what was picked. */
class PhotoPickActivity : ComponentActivity() {

    private val picker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(PhotoPicks.pickLimit()),
    ) { uris ->
        PhotoPicks.save(this, uris, intent.getBooleanExtra(EXTRA_REPLACE, false))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            runCatching {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }.onFailure { finish() }
        }
    }

    companion object {
        const val EXTRA_REPLACE = "replace"
    }
}
