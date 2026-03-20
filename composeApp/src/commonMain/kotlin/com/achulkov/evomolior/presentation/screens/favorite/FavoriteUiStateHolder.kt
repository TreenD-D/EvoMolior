package com.achulkov.evomolior.presentation.screens.favorite

import com.achulkov.evomolior.util.UiStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FavoriteUiStateHolder() : UiStateHolder() {
    private val _uiState = MutableStateFlow(FavoriteUiState())
    val uiState: StateFlow<FavoriteUiState> = _uiState.asStateFlow()

    fun onUiEvent(event: FavoriteUiEvent) {
        when (event) {
            FavoriteUiEvent.OnClickGetPremium -> {
                _uiState.update { it.copy(isPaymentRequired = true) }
            }
        }
    }

    fun onPaymentRequiredHandled() {
        _uiState.update { it.copy(isPaymentRequired = false) }
    }
}