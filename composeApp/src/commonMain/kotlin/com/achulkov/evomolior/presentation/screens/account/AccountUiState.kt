package com.achulkov.evomolior.presentation.screens.account

import com.achulkov.evomolior.designsystem.components.SettingsItemUiState
import com.achulkov.evomolior.designsystem.generated.resources.ic_settings_item_logout
import com.achulkov.evomolior.designsystem.generated.resources.ic_settings_item_subscriptions
import com.achulkov.evomolior.designsystem.generated.resources.ic_settings_item_support_legal
import com.achulkov.evomolior.domain.model.User
import com.achulkov.evomolior.generated.resources.Res
import com.achulkov.evomolior.generated.resources.help_and_support
import com.achulkov.evomolior.generated.resources.logout
import com.achulkov.evomolior.generated.resources.subscriptions
import com.achulkov.evomolior.designsystem.generated.resources.UiRes

data class AccountUiState(
    val settingsItemList: List<SettingsItemUiState> = listOf(
        SettingsItemUiState(
            startIcon = UiRes.drawable.ic_settings_item_subscriptions,
            textRes = Res.string.subscriptions
        ),

        SettingsItemUiState(
            startIcon = UiRes.drawable.ic_settings_item_support_legal,
            textRes = Res.string.help_and_support
        ),

        SettingsItemUiState(
            startIcon = UiRes.drawable.ic_settings_item_logout,
            textRes = Res.string.logout,
            showEndIcon = false,
        ),
    ),
    val user: User? = null,
    val isLogoutDialogVisible: Boolean = false,
    val showUpgradePremiumBanner: Boolean = false,
)

sealed class AccountUiEvent {
    data class OnSettingsItemClick(val item: SettingsItemUiState) : AccountUiEvent()
    data object OnLogoutConfirmClick : AccountUiEvent()
    data object OnLogoutDialogDismiss : AccountUiEvent()
    data object OnClickUpgradePremium : AccountUiEvent()
    data object OnClickSignIn : AccountUiEvent()
    data object OnClickProfile : AccountUiEvent()
}