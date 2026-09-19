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
    const val EDIT = "edit/{uri}"
    const val VIEWER = "viewer/{path}"

    fun edit(uri: Uri) = "edit/${Uri.encode(uri.toString())}"
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
                onTakePhoto = { nav.navigate(Routes.CAMERA) },
                onPhotoPicked = { uri -> nav.navigate(Routes.edit(uri)) },
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
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { entry ->
            val uri = Uri.parse(Uri.decode(entry.arguments?.getString("uri") ?: ""))
            EditScreen(
                photoUri = uri,
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
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(container = c, onBack = { nav.popBackStack() })
        }
    }
}
