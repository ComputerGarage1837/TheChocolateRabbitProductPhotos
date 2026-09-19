package com.chocolaterabbit.productphotos

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.chocolaterabbit.productphotos.ui.screens.BatchScreen
import com.chocolaterabbit.productphotos.ui.screens.CameraScreen
import com.chocolaterabbit.productphotos.ui.screens.EditScreen
import com.chocolaterabbit.productphotos.ui.screens.HomeScreen
import com.chocolaterabbit.productphotos.ui.screens.PhotoViewerScreen
import com.chocolaterabbit.productphotos.ui.screens.SettingsScreen
import com.chocolaterabbit.productphotos.ui.theme.ChocolateRabbitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = AppContainer(applicationContext)
        setContent {
            ChocolateRabbitTheme {
                AppNavigation(container)
            }
        }
    }
}

object Routes {
    const val HOME = "home"
    const val CAMERA = "camera"
    const val SETTINGS = "settings"
    const val BATCH = "batch"
    const val EDIT = "edit/{uri}?replace={replace}"
    const val VIEWER = "viewer/{path}"

    fun edit(uri: Uri, replacePath: String? = null) =
        "edit/${Uri.encode(uri.toString())}" + (replacePath?.let { "?replace=${Uri.encode(it)}" } ?: "")
    fun viewer(path: String) = "viewer/${Uri.encode(path)}"
}

@Composable
fun AppNavigation(container: AppContainer) {
    val nav = rememberNavController()
    val c = remember { container }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                photoStore = c.photos,
                modelInstaller = c.model,
                settings = c.settings,
                onTakePhoto = { nav.navigate(Routes.CAMERA) },
                onPhotoPicked = { uri -> nav.navigate(Routes.edit(uri)) },
                onBatchPicked = { uris -> c.pendingBatch = uris; nav.navigate(Routes.BATCH) },
                onOpenPhoto = { photo -> nav.navigate(Routes.viewer(photo.file.absolutePath)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.CAMERA) {
            CameraScreen(
                onCaptured = { uri ->
                    nav.navigate(Routes.edit(uri)) { popUpTo(Routes.HOME) }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.EDIT,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("replace") { type = NavType.StringType; nullable = true; defaultValue = null },
            )
        ) { entry ->
            val uri = Uri.parse(Uri.decode(entry.arguments?.getString("uri") ?: ""))
            val replace = entry.arguments?.getString("replace")?.let { Uri.decode(it) }
            EditScreen(
                photoUri = uri,
                replacePath = replace,
                container = c,
                onSaved = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onRetake = { nav.navigate(Routes.CAMERA) { popUpTo(Routes.HOME) } },
                onBack = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(
            Routes.VIEWER,
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { entry ->
            val path = Uri.decode(entry.arguments?.getString("path") ?: "")
            PhotoViewerScreen(
                path = path,
                photoStore = c.photos,
                onBack = { nav.popBackStack() },
                onEdit = { photo ->
                    val source = c.photos.originalFor(photo) ?: photo.file
                    nav.navigate(Routes.edit(Uri.fromFile(source), replacePath = photo.file.absolutePath))
                },
            )
        }
        composable(Routes.BATCH) {
            BatchScreen(
                uris = c.pendingBatch,
                container = c,
                onDone = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(container = c, onBack = { nav.popBackStack() })
        }
    }
}
