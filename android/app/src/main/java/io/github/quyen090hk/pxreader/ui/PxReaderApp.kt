package io.github.quyen090hk.pxreader

import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.quyen090hk.pxreader.settings.ThemeMode
import io.github.quyen090hk.pxreader.ui.screens.AnnotationRoute
import io.github.quyen090hk.pxreader.ui.screens.LibraryRoute
import io.github.quyen090hk.pxreader.ui.screens.ReaderRoute
import io.github.quyen090hk.pxreader.ui.screens.SettingsRoute
import kotlinx.coroutines.flow.StateFlow

private object Route {
    const val Library = "library"
    const val Settings = "settings"
    const val Reader = "reader/{documentId}?chapter={chapter}&start={start}&end={end}"
    const val Annotations = "annotations/{documentId}"
    fun reader(id: String, locator: io.github.quyen090hk.pxreader.data.TextLocator? = null): String =
        if (locator == null) "reader/$id" else "reader/$id?chapter=${locator.chapterIndex}&start=${locator.charStart}&end=${locator.charEnd}"
    fun annotations(id: String) = "annotations/$id"
}

@Composable
fun PxReaderApp(
    container: AppContainer,
    incoming: StateFlow<List<Uri>>,
    onIncomingConsumed: () -> Unit,
) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = io.github.quyen090hk.pxreader.settings.ReaderSettings())
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(colorScheme = if (dark) pxDarkColors else pxLightColors) {
        val navController = rememberNavController()
        NavHost(navController = navController, startDestination = Route.Library) {
            composable(Route.Library) {
                LibraryRoute(
                    repository = container.readerRepository,
                    importer = container.importer,
                    backupExporter = container.backupExporter,
                    incoming = incoming,
                    onIncomingConsumed = onIncomingConsumed,
                    onOpenDocument = { navController.navigate(Route.reader(it)) },
                    onSettings = { navController.navigate(Route.Settings) },
                )
            }
            composable(Route.Settings) {
                SettingsRoute(settings = container.settings, onBack = navController::navigateUp)
            }
            composable(
                route = Route.Reader,
                arguments = listOf(
                    navArgument("documentId") { type = NavType.StringType },
                    navArgument("chapter") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("start") { type = NavType.IntType; defaultValue = 0 },
                    navArgument("end") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { entry ->
                ReaderRoute(
                    documentId = requireNotNull(entry.arguments?.getString("documentId")),
                    repository = container.readerRepository,
                    settings = settings,
                    initialLocator = entry.arguments?.getInt("chapter")?.takeIf { it >= 0 }?.let { chapter ->
                        io.github.quyen090hk.pxreader.data.TextLocator(chapter, null, entry.arguments?.getInt("start") ?: 0, entry.arguments?.getInt("end") ?: 0, 0f)
                    },
                    onBack = navController::navigateUp,
                    onAnnotations = { navController.navigate(Route.annotations(it)) },
                    onSettings = { navController.navigate(Route.Settings) },
                )
            }
            composable(
                route = Route.Annotations,
                arguments = listOf(navArgument("documentId") { type = NavType.StringType }),
            ) { entry ->
                AnnotationRoute(
                    documentId = requireNotNull(entry.arguments?.getString("documentId")),
                    repository = container.readerRepository,
                    backupExporter = container.backupExporter,
                    onBack = navController::navigateUp,
                    onJump = { id, locator ->
                        navController.popBackStack()
                        navController.navigate(Route.reader(id, locator))
                    },
                )
            }
        }
    }
}

private val pxLightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260),
)

private val pxDarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8),
)
