package com.achulkov.evomolior.presentation.screens.main

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.achulkov.evomolior.generated.resources.Res
import com.achulkov.evomolior.generated.resources.help_and_support
import com.achulkov.evomolior.generated.resources.subscriptions
import com.achulkov.evomolior.generated.resources.title_screen_account
import com.achulkov.evomolior.generated.resources.title_screen_credits
import com.achulkov.evomolior.generated.resources.title_screen_favorites
import com.achulkov.evomolior.generated.resources.title_screen_home
import com.achulkov.evomolior.generated.resources.title_screen_profile
import com.achulkov.evomolior.generated.resources.title_sign_in
import com.achulkov.evomolior.presentation.screens.account.AccountScreenRoute
import com.achulkov.evomolior.presentation.screens.creditbalance.CreditBalanceScreenRoute
import com.achulkov.evomolior.presentation.screens.favorite.FavoriteScreenRoute
import com.achulkov.evomolior.presentation.screens.helpandsupport.HelpAndSupportScreenRoute
import com.achulkov.evomolior.presentation.screens.home.HomeScreenRoute
import com.achulkov.evomolior.presentation.screens.paywall.PaywallScreenRoute
import com.achulkov.evomolior.presentation.screens.profile.ProfileScreenRoute
import com.achulkov.evomolior.presentation.screens.signin.SignInScreenRoute
import com.achulkov.evomolior.presentation.screens.subscriptions.SubscriptionsScreenRoute
import org.jetbrains.compose.resources.stringResource

@Composable
fun MainNavigation(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = HomeScreenRoute()
    ) {
        composable<HomeScreenRoute> { navBackStackEntry ->
            navBackStackEntry.destination.label =
                stringResource(Res.string.title_screen_home)
            navBackStackEntry.toRoute<HomeScreenRoute>().Content()
        }
        composable<ProfileScreenRoute> { navBackStackEntry ->
            navBackStackEntry.destination.label =
                stringResource(Res.string.title_screen_profile)
            navBackStackEntry.toRoute<ProfileScreenRoute>().Content()
        }
        composable<SignInScreenRoute> { navBackStackEntry ->
            navBackStackEntry.destination.label =
                stringResource(Res.string.title_sign_in)
            navBackStackEntry.toRoute<SignInScreenRoute>().Content()
        }
        composable<AccountScreenRoute> { navBackStackEntry ->
            navBackStackEntry.destination.label =
                stringResource(Res.string.title_screen_account)
            navBackStackEntry.toRoute<AccountScreenRoute>().Content()
        }
        composable<FavoriteScreenRoute> { navBackStackEntry ->
            navBackStackEntry.destination.label =
                stringResource(Res.string.title_screen_favorites)
            navBackStackEntry.toRoute<FavoriteScreenRoute>().Content()
        }
        composable<PaywallScreenRoute> { navBackStackEntry ->
            navBackStackEntry.toRoute<PaywallScreenRoute>().Content()
        }
        composable<HelpAndSupportScreenRoute> { navBackStackEntry ->
            navBackStackEntry.destination.label =
                stringResource(Res.string.help_and_support)
            navBackStackEntry.toRoute<HelpAndSupportScreenRoute>().Content()
        }
        composable<SubscriptionsScreenRoute> { navBackStackEntry ->
            navBackStackEntry.destination.label =
                stringResource(Res.string.subscriptions)
            navBackStackEntry.toRoute<SubscriptionsScreenRoute>().Content()
        }
        composable<CreditBalanceScreenRoute> { navBackStackEntry ->
            navBackStackEntry.destination.label =
                stringResource(Res.string.title_screen_credits)
            navBackStackEntry.toRoute<CreditBalanceScreenRoute>().Content()
        }
    }
}