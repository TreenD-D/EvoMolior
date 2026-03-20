package com.achulkov.evomolior.presentation.screens.creditbalance

import com.achulkov.evomolior.domain.model.credit.CreditTransaction
import com.achulkov.evomolior.domain.model.credit.RecurringCredit

data class CreditBalanceUiState(
    val creditBalance: Int = 0,
    val isPremiumUser: Boolean = false,
    val isPremiumRequired: Boolean = false,
    val isMoreCreditRequired: Boolean = false,
    val lastTransactions: List<CreditTransaction> = emptyList(),
    val recurringCredits: List<RecurringCredit> = emptyList(),
)

sealed class CreditBalanceUiEvent {
    data object UpgradeToPremium : CreditBalanceUiEvent()
    data object BuyCreditPack : CreditBalanceUiEvent()
}