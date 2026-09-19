package com.chocolaterabbit.productphotos

import android.content.Context
import com.chocolaterabbit.productphotos.data.PhotoStore
import com.chocolaterabbit.productphotos.data.SettingsRepository
import com.chocolaterabbit.productphotos.data.TemplateStore
import com.chocolaterabbit.productphotos.processing.ModelInstaller
import com.chocolaterabbit.productphotos.processing.ProductPhotoPipeline

/** Simple hand-rolled dependency container; small app, no DI framework needed. */
class AppContainer(context: Context) {
    val settings = SettingsRepository(context)
    val templates = TemplateStore(context)
    val photos = PhotoStore(context)
    val pipeline = ProductPhotoPipeline(context, templates)
    val model = ModelInstaller(context).also { it.ensureInstalled() }
}
