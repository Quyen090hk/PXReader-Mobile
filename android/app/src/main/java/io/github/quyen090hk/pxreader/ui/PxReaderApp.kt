package io.github.quyen090hk.pxreader

import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    MaterialTheme(
        colorScheme = if (dark) pxDarkColors else pxLightColors,
        typography = pxTypography,
        shapes = pxShapes,
    ) {
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
    primary = Color(0xFF1D4ED8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF0B2A6F),
    secondary = Color(0xFF006B61),
    secondaryContainer = Color(0xFFC8F3EC),
    tertiary = Color(0xFF9A4D00),
    tertiaryContainer = Color(0xFFFFDCC2),
    background = Color(0xFFF8F9FC),
    surface = Color(0xFFFCF9F5),
    surfaceVariant = Color(0xFFEFF1F6),
    outline = Color(0xFF737783),
)

private val pxDarkColors = darkColorScheme(
    primary = Color(0xFFB6C8FF),
    onPrimary = Color(0xFF003A9F),
    primaryContainer = Color(0xFF17469D),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFF9DE4D8),
    secondaryContainer = Color(0xFF005047),
    tertiary = Color(0xFFFFB782),
    tertiaryContainer = Color(0xFF783900),
    background = Color(0xFF111318),
    surface = Color(0xFF191B20),
    surfaceVariant = Color(0xFF282B33),
    outline = Color(0xFF9A9EAA),
)

private val pxShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(34.dp),
)

private val pxTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
)
