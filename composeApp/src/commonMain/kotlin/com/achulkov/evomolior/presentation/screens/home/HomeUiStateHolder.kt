package com.achulkov.evomolior.presentation.screens.home

import com.achulkov.evomolior.data.repository.CreditRepository
import com.achulkov.evomolior.domain.exceptions.CreditRequiredException
import com.achulkov.evomolior.util.UiStateHolder
import com.achulkov.evomolior.util.uiStateHolderScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeUiStateHolder(
    private val creditRepository: CreditRepository,
) : UiStateHolder() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        observeCreditBalance()
    }

    fun onMoreCreditsRequiredHandled() {
        _uiState.update { it.copy(isMoreCreditsRequired = false) }
    }

    fun onUiEvent(event: HomeUiEvent) {
        when (event) {
            HomeUiEvent.OnClickToolbarCredits -> {
                _uiState.update { it.copy(isMoreCreditsRequired = true) }
            }

            HomeUiEvent.OnClickBuyCredits -> {
                uiStateHolderScope.launch {
                    creditRepository.addCredits(5)
                }

            }

            HomeUiEvent.OnClickUseCredits -> {
                useCredit()
            }
        }
    }

    private fun observeCreditBalance() = uiStateHolderScope.launch {
        creditRepository.balance.collectLatest { creditBalance ->
            _uiState.update { it.copy(creditBalance = creditBalance) }
        }
    }

    private fun useCredit() = uiStateHolderScope.launch {
        try {
            creditRepository.useCredits(1, "Used for demo generation")
        } catch (e: Exception) {
            if (e is CreditRequiredException) {
                _uiState.update { it.copy(isMoreCreditsRequired = true) }
            }
            coroutineContext.ensureActive()
        }
    }
}