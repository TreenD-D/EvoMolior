package com.achulkov.evomolior.presentation.screens.onboarding

import com.achulkov.evomolior.data.repository.SubscriptionRepository
import com.achulkov.evomolior.data.source.preferences.UserPreferences
import com.achulkov.evomolior.util.Constants
import com.achulkov.evomolior.util.UiStateHolder
import com.achulkov.evomolior.util.uiStateHolderScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


class OnBoardingUiStateHolder(
    private val userPreferences: UserPreferences,
    private val subscriptionRepository: SubscriptionRepository
) : UiStateHolder() {
    private val _uiState = MutableStateFlow(OnBoardingUiState(isLoading = true))
    val uiState: StateFlow<OnBoardingUiState> = _uiState.asStateFlow()

    init {
        checkIfOnBoardIsShown()
    }

    private fun checkIfOnBoardIsShown() = uiStateHolderScope.launch {
        _uiState.update { it.copy(isLoading = true) }
        if (userPreferences.getBoolean(UserPreferences.KEY_IS_ONBOARD_SHOWN)) {
            _uiState.update { it.copy(onBoardIsShown = true) }
        } else
            _uiState.update { it.copy(onBoardIsShown = false, isLoading = false) }

    }


    fun onUiEvent(event: OnBoardingUiEvent) = uiStateHolderScope.launch {
        when (event) {
            OnBoardingUiEvent.OnClickStart -> {
                onBoardShown()
            }

            OnBoardingUiEvent.OnClickGetPremiumAccess -> {
                subscriptionRepository.currentPlacementId =
                    Constants.PAYWALL_PLACEMENT_ONBOARDING
                _uiState.update { it.copy(isPremiumRequired = true) }
            }
        }
    }

    fun onPaywallEventHandled() = uiStateHolderScope.launch {
        _uiState.update { it.copy(isPremiumRequired = false) }
        onBoardShown()
    }

    private suspend fun onBoardShown() {
        _uiState.update { it.copy(isLoading = true) }
        userPreferences.putBoolean(UserPreferences.KEY_IS_ONBOARD_SHOWN, true)
        _uiState.update { it.copy(onBoardIsShown = true, isLoading = false) }
    }

}

