/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pixelos.ota.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.debug.UiModePreviews
import com.android.settingslib.spa.framework.theme.SettingsTheme
import net.pixelos.ota.R

private val AnimationSize = 260.dp

@Composable
fun UpdateCheckAnimation(modifier: Modifier = Modifier) {
    Lottie(
        resId = R.raw.loading_shapes_expressive,
        modifier = modifier.size(AnimationSize),
    )
}

@UiModePreviews
@Composable
private fun UpdateCheckAnimationPreview() {
    SettingsTheme {
        UpdateCheckAnimation()
    }
}
