package app.lawnchair.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.android.launcher3.R

/** Pane's typeface (Microsoft Selawik, SIL Open Font License), bundled in the app. */
val Selawik = FontFamily(
    Font(R.font.selawik_light, FontWeight.Light),
    Font(R.font.selawik_semilight, FontWeight(350)),
    Font(R.font.selawik_regular, FontWeight.Normal),
    Font(R.font.selawik_semibold, FontWeight.SemiBold),
    Font(R.font.selawik_semibold, FontWeight.Medium),
    Font(R.font.selawik_bold, FontWeight.Bold),
)
