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

package app.lawnchair.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val base = Typography()

// Pane: Selawik throughout. Large text is light, as on the Start screen; body text is regular.
private val light = FontWeight.Light
private val semibold = FontWeight.SemiBold

val Typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Selawik, fontWeight = light),
    displayMedium = base.displayMedium.copy(fontFamily = Selawik, fontWeight = light),
    displaySmall = base.displaySmall.copy(fontFamily = Selawik, fontWeight = light),
    headlineLarge = base.headlineLarge.copy(fontFamily = Selawik, fontWeight = light),
    headlineMedium = base.headlineMedium.copy(fontFamily = Selawik, fontWeight = light),
    headlineSmall = base.headlineSmall.copy(fontFamily = Selawik, fontWeight = light),
    titleLarge = base.titleLarge.copy(fontFamily = Selawik, fontWeight = light),
    titleMedium = base.titleMedium.copy(fontFamily = Selawik),
    titleSmall = base.titleSmall.copy(fontFamily = Selawik),
    bodyLarge = base.bodyLarge.copy(fontFamily = Selawik, letterSpacing = 0.sp),
    bodyMedium = base.bodyMedium.copy(fontFamily = Selawik, letterSpacing = 0.1.sp),
    bodySmall = base.bodySmall.copy(fontFamily = Selawik),
    labelLarge = base.labelLarge.copy(fontFamily = Selawik),
    labelMedium = base.labelMedium.copy(fontFamily = Selawik),
    labelSmall = base.labelSmall.copy(fontFamily = Selawik),
    displayLargeEmphasized = base.displayLargeEmphasized.copy(fontFamily = Selawik),
    displayMediumEmphasized = base.displayMediumEmphasized.copy(fontFamily = Selawik),
    displaySmallEmphasized = base.displaySmallEmphasized.copy(fontFamily = Selawik),
    headlineLargeEmphasized = base.headlineLargeEmphasized.copy(fontFamily = Selawik),
    headlineMediumEmphasized = base.headlineMediumEmphasized.copy(fontFamily = Selawik),
    headlineSmallEmphasized = base.headlineSmallEmphasized.copy(fontFamily = Selawik),
    titleLargeEmphasized = base.titleLargeEmphasized.copy(fontFamily = Selawik),
    titleMediumEmphasized = base.titleMediumEmphasized.copy(fontFamily = Selawik, fontWeight = semibold),
    titleSmallEmphasized = base.titleSmallEmphasized.copy(fontFamily = Selawik, fontWeight = semibold),
    bodyLargeEmphasized = base.bodyLargeEmphasized.copy(fontFamily = Selawik, fontWeight = semibold),
    bodyMediumEmphasized = base.bodyMediumEmphasized.copy(fontFamily = Selawik, fontWeight = semibold),
    bodySmallEmphasized = base.bodySmallEmphasized.copy(fontFamily = Selawik, fontWeight = semibold),
    labelLargeEmphasized = base.labelLargeEmphasized.copy(fontFamily = Selawik, fontWeight = semibold),
    labelMediumEmphasized = base.labelMediumEmphasized.copy(fontFamily = Selawik, fontWeight = semibold),
    labelSmallEmphasized = base.labelSmallEmphasized.copy(fontFamily = Selawik, fontWeight = semibold),
)
