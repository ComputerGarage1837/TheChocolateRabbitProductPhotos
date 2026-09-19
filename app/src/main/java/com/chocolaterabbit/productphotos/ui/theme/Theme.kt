package com.chocolaterabbit.productphotos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Cream = Color(0xFFFDF5E8)
val Cocoa = Color(0xFF4A2C1A)
val MilkChocolate = Color(0xFF7B4B2A)
val Caramel = Color(0xFFC98A4B)

private val ColorScheme = lightColorScheme(
    primary = Cocoa,
    onPrimary = Cream,
    secondary = MilkChocolate,
    onSecondary = Cream,
    tertiary = Caramel,
    background = Cream,
    onBackground = Cocoa,
    surface = Cream,
    onSurface = Cocoa,
    surfaceVariant = Color(0xFFF3E6D2),
    onSurfaceVariant = MilkChocolate,
)

@Composable
fun ChocolateRabbitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, content = content)
}
