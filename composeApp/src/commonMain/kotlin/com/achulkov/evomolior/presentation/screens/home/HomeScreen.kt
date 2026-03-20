package com.achulkov.evomolior.presentation.screens.home

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.achulkov.evomolior.designsystem.components.AppButton
import com.achulkov.evomolior.designsystem.components.ButtonStyle
import com.achulkov.evomolior.designsystem.components.Chip
import com.achulkov.evomolior.designsystem.components.ChipSize
import com.achulkov.evomolior.designsystem.components.ChipStyle
import com.achulkov.evomolior.designsystem.components.ScreenWithToolbar
import com.achulkov.evomolior.designsystem.generated.resources.UiRes
import com.achulkov.evomolior.designsystem.generated.resources.ic_coin_credits
import com.achulkov.evomolior.designsystem.theme.AppTheme
import com.achulkov.evomolior.generated.resources.Res
import com.achulkov.evomolior.generated.resources.title_screen_home
import org.jetbrains.compose.resources.stringResource

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    uiStateHolder: HomeUiStateHolder,
    onMoreCreditsNeeded: () -> Unit,
) {
    val uiState by uiStateHolder.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.isMoreCreditsRequired) {
        if (uiState.isMoreCreditsRequired) {
            onMoreCreditsNeeded()
            uiStateHolder.onMoreCreditsRequiredHandled()
        }
    }

    HomeScreen(
        modifier = modifier.fillMaxSize(),
        uiState = uiState,
        onUiEvent = uiStateHolder::onUiEvent
    )
}

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    uiState: HomeUiState,
    onUiEvent: (HomeUiEvent) -> Unit
) {
    val focusManager = LocalFocusManager.current

    ScreenWithToolbar(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { focusManager.clearFocus() })
        },
        isScrollableContent = false,
        title = stringResource(Res.string.title_screen_home),
        includeBottomInsets = false,// Set to true if bottom nav is not visible
        toolbarExtraContent = {
            Chip(
                text = "${uiState.creditBalance}",
                style = ChipStyle.FILLED_ALPHA,
                size = ChipSize.SMALL,
                startIconRes = UiRes.drawable.ic_coin_credits,
                onClick = {
                    onUiEvent(HomeUiEvent.OnClickToolbarCredits)
                }
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sectionSpacing)) {
            Text("HomeScreen")

            AppButton(text = "Purchase 5 credits") {

                onUiEvent(HomeUiEvent.OnClickBuyCredits)
            }

            AppButton(text = "Use 1 credit", style = ButtonStyle.ALTERNATIVE) {
                onUiEvent(HomeUiEvent.OnClickUseCredits)
            }
        }
    }
}