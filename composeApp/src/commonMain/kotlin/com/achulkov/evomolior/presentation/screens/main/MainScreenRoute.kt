package com.achulkov.evomolior.presentation.screens.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.achulkov.evomolior.designsystem.components.bottomnav.BottomNavItem
import com.achulkov.evomolior.designsystem.generated.resources.UiRes
import com.achulkov.evomolior.designsystem.generated.resources.ic_favorite
import com.achulkov.evomolior.designsystem.generated.resources.ic_home
import com.achulkov.evomolior.designsystem.generated.resources.ic_profile
import com.achulkov.evomolior.generated.resources.bottom_nav_label_favorites
import com.achulkov.evomolior.generated.resources.bottom_nav_label_home
import com.achulkov.evomolior.generated.resources.bottom_nav_label_profile
import com.achulkov.evomolior.presentation.screens.account.AccountScreenRoute
import com.achulkov.evomolior.presentation.screens.favorite.FavoriteScreenRoute
import com.achulkov.evomolior.presentation.screens.home.HomeScreenRoute
import com.achulkov.evomolior.root.LocalNavigator
import com.achulkov.evomolior.util.ScreenRoute
import kotlinx.serialization.Serializable

@Serializable
class MainScreenRoute : ScreenRoute {


    @Composable
    override fun Content() {
        val navController = rememberNavController()
        CompositionLocalProvider(LocalNavigator provides navController) {
            val bottomNavItemsWithDestinations = remember { getBottomNavItemsWithDestination() }
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            val bottomNavVisibleScreens =
                remember(bottomNavItemsWithDestinations) { bottomNavItemsWithDestinations.map { it.first.route } }
            val isBottomNavVisible = bottomNavVisibleScreens.any {
                currentDestination?.route?.contains(it) == true
            }

            val selectedBottomNavIndex =
                bottomNavItemsWithDestinations.getPositionByRouteId(currentDestination?.route)
            val mainScreenUiState = MainScreenUiState(
                bottomNavUiState = BottomNavUiState(
                    items = bottomNavItemsWithDestinations,
                    isVisible = isBottomNavVisible,
                    selectedBottomNavIndex = selectedBottomNavIndex,
                )
            )

            MainScreen(
                mainScreenUiState = mainScreenUiState,
                onUiEvent = { event ->
                    when (event) {
                        is MainScreenUiEvent.OnBottomNavItemClick -> {
                            val screenRoute =
                                bottomNavItemsWithDestinations.getScreenRouteByRouteId(event.bottomNavItem.route)
                            navController.navigate(screenRoute) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }

                        MainScreenUiEvent.OnToolbarNavItemClick -> {
                            navController.navigateUp()
                        }
                    }
                },

                content = {
                    MainNavigation(navController)
                }
            )

        }
    }

    private fun getBottomNavItemsWithDestination(): List<Pair<BottomNavItem, ScreenRoute>> {

        //MAKE SURE route strings are same as ScreenRoute class names. Ex: HomeScreenRoute.
        return listOf(
            BottomNavItem(
                label = com.achulkov.evomolior.generated.resources.Res.string.bottom_nav_label_home,
                icon = UiRes.drawable.ic_home,
                route = "HomeScreenRoute"
            ) to HomeScreenRoute(),
            BottomNavItem(
                label = com.achulkov.evomolior.generated.resources.Res.string.bottom_nav_label_favorites,
                icon = UiRes.drawable.ic_favorite,
                route = "FavoriteScreenRoute"
            ) to FavoriteScreenRoute(),
            BottomNavItem(
                label = com.achulkov.evomolior.generated.resources.Res.string.bottom_nav_label_profile,
                icon = UiRes.drawable.ic_profile,
                route = "AccountScreenRoute"
            ) to AccountScreenRoute()
        )
    }

    private fun List<Pair<BottomNavItem, ScreenRoute>>.getPositionByRouteId(route: String?): Int {
        val index = indexOfFirst { route?.contains(it.first.route) == true }
        return if (index >= 0) index else 0
    }

    private fun List<Pair<BottomNavItem, ScreenRoute>>.getScreenRouteByRouteId(route: String?): ScreenRoute {
        return this.firstOrNull { it.first.route == route }?.second
            ?: HomeScreenRoute() //Default to Home Screen
    }
}