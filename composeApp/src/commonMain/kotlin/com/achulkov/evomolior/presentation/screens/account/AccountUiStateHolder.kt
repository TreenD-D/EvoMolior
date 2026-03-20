package com.achulkov.evomolior.presentation.screens.account

import com.achulkov.evomolior.data.repository.SubscriptionRepository
import com.achulkov.evomolior.data.repository.UserRepository
import com.achulkov.evomolior.designsystem.components.SettingsItemUiState
import com.achulkov.evomolior.designsystem.generated.resources.ic_settings_item_logout
import com.achulkov.evomolior.designsystem.generated.resources.ic_settings_item_subscriptions
import com.achulkov.evomolior.designsystem.generated.resources.ic_settings_item_support_legal
import com.achulkov.evomolior.domain.model.isFree
import com.achulkov.evomolior.generated.resources.Res
import com.achulkov.evomolior.generated.resources.help_and_support
import com.achulkov.evomolior.generated.resources.logout
import com.achulkov.evomolior.generated.resources.subscriptions
import com.achulkov.evomolior.util.UiStateHolder
import com.achulkov.evomolior.util.uiStateHolderScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.achulkov.evomolior.designsystem.generated.resources.UiRes
import com.achulkov.evomolior.util.Constants

class AccountUiStateHolder(
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository
) : UiStateHolder() {

    private val allSettingsItemList: List<SettingsItemUiState> = buildList {
        add(
            SettingsItemUiState(
                startIcon = UiRes.drawable.ic_settings_item_subscriptions,
                textRes = Res.string.subscriptions
            )
        )

        add(
            SettingsItemUiState(
                startIcon = UiRes.drawable.ic_settings_item_support_legal,
                textRes = Res.string.help_and_support
            )
        )

        if (Constants.AUTH_SOCIAL_LOGIN_ENABLED) {
            add(
                SettingsItemUiState(
                    startIcon = UiRes.drawable.ic_settings_item_logout,
                    textRes = Res.string.logout,
                    showEndIcon = false,
                )
            )
        }
    }

    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> =
        combine(
            userRepository.currentUser,
            subscriptionRepository.currentSubscriptionFlow,
            _uiState
        ) { currentUser, currentSubscription, uiState ->
            val user = currentUser.getOrNull()
            uiState.copy(
                user = if (user?.isAnonymous == true && Constants.AUTH_SOCIAL_LOGIN_ENABLED) null else user,
                settingsItemList =
                    if (user != null) allSettingsItemList
                    else allSettingsItemList.subList(0, allSettingsItemList.size - 1),
                showUpgradePremiumBanner = currentSubscription.isFree
            )
        }.stateIn(uiStateHolderScope, WhileSubscribed(5000), _uiState.value)


    fun onUiEvent(event: AccountUiEvent) = uiStateHolderScope.launch {
        when (event) {

            AccountUiEvent.OnLogoutConfirmClick -> {
                userRepository.logOut()
                _uiState.update { it.copy(isLogoutDialogVisible = false) }
            }

            AccountUiEvent.OnLogoutDialogDismiss -> {
                _uiState.update { it.copy(isLogoutDialogVisible = false) }
            }

            is AccountUiEvent.OnSettingsItemClick -> {
                when (event.item.textRes) {
                    Res.string.logout -> {
                        _uiState.update { it.copy(isLogoutDialogVisible = true) }
                    }

                    else -> {}
                }
            }

            AccountUiEvent.OnClickUpgradePremium -> Unit
            AccountUiEvent.OnClickProfile -> Unit
            AccountUiEvent.OnClickSignIn -> Unit
        }

    }
}