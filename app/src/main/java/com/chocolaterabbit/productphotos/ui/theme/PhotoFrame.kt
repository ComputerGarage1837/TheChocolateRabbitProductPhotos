package com.chocolaterabbit.productphotos.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Rounded corners plus a visible border for every photo shown in the app. The template is the
 * same cream as the app background, so without a border it is impossible to see where a photo
 * ends.
 */
@Composable
fun Modifier.photoFrame(corner: Dp = 10.dp): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .clip(shape)
        .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), shape)
}
