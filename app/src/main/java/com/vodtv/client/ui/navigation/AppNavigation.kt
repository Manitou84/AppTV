package com.vodtv.client.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vodtv.client.VodTvApp
import com.vodtv.client.data.model.ContentType
import com.vodtv.client.ui.catalog.CatalogScreen
import com.vodtv.client.ui.components.NavTab
import com.vodtv.client.ui.detail.DetailScreen
import com.vodtv.client.ui.favorites.FavoritesScreen
import com.vodtv.client.ui.home.HomeScreen
import com.vodtv.client.ui.motors.MotorsScreen
import com.vodtv.client.ui.player.PlayerScreen
import com.vodtv.client.ui.profile.LocalActiveProfile
import com.vodtv.client.ui.profile.ProfilePickerScreen
import com.vodtv.client.ui.settings.SettingsScreen

object Routes {
    const val PROFILES = "profiles?startup={startup}"
    const val HOME = "home"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val FAVORITES = "favorites"
    const val MOTORS = "motors"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{slug}"
    const val PLAYER = "player"

    fun detail(slug: String) = "detail/${Uri.encode(slug)}"
    fun profiles(startup: Boolean) = "profiles?startup=$startup"

    fun forTab(tab: NavTab) = when (tab) {
        NavTab.HOME -> HOME
        NavTab.MOVIES -> MOVIES
        NavTab.SERIES -> SERIES
        NavTab.FAVORITES -> FAVORITES
        NavTab.MOTORS -> MOTORS
        NavTab.SETTINGS -> SETTINGS
        NavTab.PROFILES -> profiles(startup = false)
    }
}

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val container = (LocalContext.current.applicationContext as VodTvApp).container
    val activeProfile by container.profileRepository.active.collectAsStateWithLifecycle()

    val selectTab: (NavTab) -> Unit = { tab -> nav.navigateToTab(tab) }
    val openDetail: (String) -> Unit = { slug -> nav.navigate(Routes.detail(slug)) }
    val openPlayer: () -> Unit = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } }
    // Cambio profilo: si riparte dalla Home con uno stack pulito (ViewModel del nuovo profilo)
    val restartAtHome: () -> Unit = {
        nav.navigate(Routes.HOME) { popUpTo(nav.graph.id) { inclusive = true } }
    }

    CompositionLocalProvider(LocalActiveProfile provides activeProfile) {
        NavHost(navController = nav, startDestination = Routes.profiles(startup = true)) {
            composable(
                route = Routes.PROFILES,
                arguments = listOf(navArgument("startup") { type = NavType.BoolType; defaultValue = false }),
            ) { entry ->
                ProfilePickerScreen(
                    startup = entry.arguments?.getBoolean("startup") ?: false,
                    onDone = restartAtHome,
                )
            }
            composable(Routes.HOME) {
                HomeScreen(onSelectTab = selectTab, onOpenDetail = openDetail, onPlay = openPlayer)
            }
            composable(Routes.MOVIES) {
                CatalogScreen(target = ContentType.MOVIE, onSelectTab = selectTab, onOpenDetail = openDetail)
            }
            composable(Routes.SERIES) {
                CatalogScreen(target = ContentType.SERIES, onSelectTab = selectTab, onOpenDetail = openDetail)
            }
            composable(Routes.FAVORITES) {
                FavoritesScreen(onSelectTab = selectTab, onOpenDetail = openDetail)
            }
            composable(Routes.MOTORS) {
                MotorsScreen(onSelectTab = selectTab, onPlay = openPlayer)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onSelectTab = selectTab, onProfileSwitched = restartAtHome)
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("slug") { type = NavType.StringType }),
            ) {
                DetailScreen(onPlay = openPlayer)
            }
            composable(Routes.PLAYER) {
                PlayerScreen(onExit = { nav.popBackStack() })
            }
        }
    }
}

/** Cambio sezione dalla barra in alto: una sola copia per sezione, stato conservato. */
private fun NavHostController.navigateToTab(tab: NavTab) {
    if (tab == NavTab.PROFILES) {
        navigate(Routes.forTab(tab)) { launchSingleTop = true }
        return
    }
    navigate(Routes.forTab(tab)) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
