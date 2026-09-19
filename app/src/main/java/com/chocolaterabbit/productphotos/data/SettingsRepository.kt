package com.chocolaterabbit.productphotos.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** User-adjustable settings. Everything has a sensible default so the app works untouched. */
data class AppSettings(
    /** True when the user has picked their own template instead of the bundled one. */
    val useCustomTemplate: Boolean = false,
    /** Whether the edit screen starts with "Auto enhance" switched on. */
    val autoEnhanceByDefault: Boolean = false,
    /** Centre the product in the frame and scale it to a consistent size. */
    val autoFitProduct: Boolean = true,
    /** How much of the frame width the product should fill when auto-fit is on (percent). */
    val productFillPercent: Int = 80,
    /** Also copy saved photos into the phone's shared Pictures folder. */
    val saveToDeviceGallery: Boolean = true,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val USE_CUSTOM_TEMPLATE = booleanPreferencesKey("use_custom_template")
        val AUTO_ENHANCE = booleanPreferencesKey("auto_enhance_default")
        val AUTO_FIT = booleanPreferencesKey("auto_fit_product")
        val FILL_PERCENT = intPreferencesKey("product_fill_percent")
        val SAVE_TO_GALLERY = booleanPreferencesKey("save_to_device_gallery")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            useCustomTemplate = p[Keys.USE_CUSTOM_TEMPLATE] ?: false,
            autoEnhanceByDefault = p[Keys.AUTO_ENHANCE] ?: false,
            autoFitProduct = p[Keys.AUTO_FIT] ?: true,
            productFillPercent = p[Keys.FILL_PERCENT] ?: 80,
            saveToDeviceGallery = p[Keys.SAVE_TO_GALLERY] ?: true,
        )
    }

    suspend fun setUseCustomTemplate(value: Boolean) =
        context.dataStore.edit { it[Keys.USE_CUSTOM_TEMPLATE] = value }

    suspend fun setAutoEnhanceByDefault(value: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_ENHANCE] = value }

    suspend fun setAutoFitProduct(value: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_FIT] = value }

    suspend fun setProductFillPercent(value: Int) =
        context.dataStore.edit { it[Keys.FILL_PERCENT] = value.coerceIn(40, 100) }

    suspend fun setSaveToDeviceGallery(value: Boolean) =
        context.dataStore.edit { it[Keys.SAVE_TO_GALLERY] = value }
}
