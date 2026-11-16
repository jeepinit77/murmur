package app.murmur.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import app.murmur.R

/** One variable font file covers every weight. */
@OptIn(ExperimentalTextApi::class)
private fun figtree(weight: FontWeight) =
    Font(R.font.figtree, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

/** The interface font, Figtree. Article text in the reader sets its own style. */
val UiFont: FontFamily = FontFamily(
    figtree(FontWeight.Normal),
    figtree(FontWeight.Medium),
    figtree(FontWeight.SemiBold),
    figtree(FontWeight.Bold),
)

private fun TextStyle.ui(weight: FontWeight? = null) = copy(fontFamily = UiFont, fontWeight = weight ?: fontWeight)

private val base = Typography()

val MurmurTypography = Typography(
    displayLarge = base.displayLarge.ui(),
    displayMedium = base.displayMedium.ui(),
    displaySmall = base.displaySmall.ui(),
    headlineLarge = base.headlineLarge.ui(FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.ui(FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.ui(FontWeight.SemiBold),
    titleLarge = base.titleLarge.ui(FontWeight.SemiBold),
    titleMedium = base.titleMedium.ui(FontWeight.SemiBold),
    titleSmall = base.titleSmall.ui(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.ui(),
    bodyMedium = base.bodyMedium.ui(),
    bodySmall = base.bodySmall.ui(),
    labelLarge = base.labelLarge.ui(FontWeight.SemiBold),
    labelMedium = base.labelMedium.ui(),
    labelSmall = base.labelSmall.ui(),
)
