package com.achulkov.evomolior.presentation.screens.home

data class HomeUiState(
    val isMoreCreditsRequired: Boolean = false,
    val creditBalance: Int = 0
)

sealed class HomeUiEvent {
    data object OnClickToolbarCredits : HomeUiEvent()
    data object OnClickBuyCredits : HomeUiEvent()
    data object OnClickUseCredits : HomeUiEvent()
}