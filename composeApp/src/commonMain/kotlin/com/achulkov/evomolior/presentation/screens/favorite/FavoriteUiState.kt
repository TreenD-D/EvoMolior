package com.achulkov.evomolior.presentation.screens.favorite

data class FavoriteUiState(
    val isPaymentRequired: Boolean = false
)

sealed class FavoriteUiEvent {
    data object OnClickGetPremium : FavoriteUiEvent()
}