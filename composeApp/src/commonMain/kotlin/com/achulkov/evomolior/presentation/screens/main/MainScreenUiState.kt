package com.achulkov.evomolior.presentation.screens.main

import com.achulkov.evomolior.designsystem.components.bottomnav.BottomNavItem
import com.achulkov.evomolior.util.ScreenRoute


data class MainScreenUiState(
    val bottomNavUiState: BottomNavUiState = BottomNavUiState()
)

data class BottomNavUiState(
    val items: List<Pair<BottomNavItem, ScreenRoute>> = emptyList(),
    val isVisible: Boolean = true,
    val selectedBottomNavIndex: Int = 0
)

sealed interface MainScreenUiEvent {
    data class OnBottomNavItemClick(val bottomNavItem: BottomNavItem) : MainScreenUiEvent
    data object OnToolbarNavItemClick : MainScreenUiEvent
}