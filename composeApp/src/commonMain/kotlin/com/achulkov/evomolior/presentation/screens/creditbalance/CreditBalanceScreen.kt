@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.presentation.screens.creditbalance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.achulkov.evomolior.designsystem.components.*
import com.achulkov.evomolior.designsystem.generated.resources.UiRes
import com.achulkov.evomolior.designsystem.generated.resources.ic_back
import com.achulkov.evomolior.designsystem.theme.AppTheme
import com.achulkov.evomolior.domain.model.credit.CreditTransaction
import com.achulkov.evomolior.generated.resources.Res
import com.achulkov.evomolior.generated.resources.btn_get_premium
import com.achulkov.evomolior.generated.resources.title_screen_credits
import com.achulkov.evomolior.presentation.components.credit.CreditTransactionsList
import com.achulkov.evomolior.presentation.components.credit.RecurringCreditsStatusBox
import org.jetbrains.compose.resources.stringResource


@Composable
fun CreditBalanceScreen(
    modifier: Modifier = Modifier,
    uiStateHolder: CreditBalanceUiStateHolder,
    onPurchaseRequired: () -> Unit,
    onClickBack: () -> Unit
) {
    val uiState by uiStateHolder.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.isPremiumRequired) {
        if (uiState.isPremiumRequired) {
            onPurchaseRequired()
            uiStateHolder.onPremiumRequiredHandled()
        }
    }

    LaunchedEffect(uiState.isMoreCreditRequired) {
        if (uiState.isMoreCreditRequired) {
            onPurchaseRequired()
            uiStateHolder.onMoreCreditRequiredHandled()
        }
    }

    ScreenWithToolbar(
        modifier = modifier.fillMaxSize().background(AppTheme.colors.background),
        title = stringResource(Res.string.title_screen_credits),
        navigationIcon = UiRes.drawable.ic_back,
        includeBottomInsets = true,
        isScrollableContent = true,
        onNavigationIconClick = onClickBack,
    ) {
        CreditBalanceScreen(
            modifier = Modifier.fillMaxSize(),
            uiState = uiState,
            onUiEvent = uiStateHolder::onUiEvent
        )
    }
}

@Composable
private fun CreditBalanceScreen(
    modifier: Modifier = Modifier,
    uiState: CreditBalanceUiState,
    onUiEvent: (CreditBalanceUiEvent) -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sectionSpacing)
    ) {
        var isTransactionListExpanded by rememberSaveable { mutableStateOf(false) }

        // Hero Section - Credit Balance Display
        CreditBalanceHeroSection(
            creditBalance = uiState.creditBalance,
            isPremiumUser = uiState.isPremiumUser
        )

        // Main Actions Section
        CreditActionsSection(
            isPremiumUser = uiState.isPremiumUser,
            onUpgradeToPremium = { onUiEvent(CreditBalanceUiEvent.UpgradeToPremium) },
            onBuyCreditPack = { onUiEvent(CreditBalanceUiEvent.BuyCreditPack) }
        )

        // Recurring Credits Info
        RecurringCreditsStatusBox(recurringCredits = uiState.recurringCredits)

        // Cost Information Box
        CreditCostInfoSection()

        // TODO Check Later Quick Actions for Earning Credits (Like Referral, Watch Ads etc)

        // Transaction History Section
        TransactionHistorySection(
            transactions = uiState.lastTransactions,
            isExpanded = isTransactionListExpanded,
            onToggleExpanded = { isTransactionListExpanded = !isTransactionListExpanded }
        )
    }
}

@Composable
private fun CreditBalanceHeroSection(
    creditBalance: Int,
    isPremiumUser: Boolean
) {
    AppCardContainer(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.groupedVerticalElementSpacing)
        ) {
            // Credit Balance Display
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.defaultSpacing)
            ) {
                Text(
                    text = "Available Credits",
                    style = AppTheme.typography.bodyMedium,
                    color = AppTheme.colors.text.secondary,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = creditBalance.toString(),
                    style = AppTheme.typography.h1,
                    fontWeight = FontWeight.Bold,
                    color = if (creditBalance > 0) AppTheme.colors.text.primary else AppTheme.colors.status.error,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = if (isPremiumUser) "Enjoying Premium" else "On Free Plan",
                    style = AppTheme.typography.bodySmall,
                    color = if (isPremiumUser) AppTheme.colors.primary else AppTheme.colors.text.secondary,
                    textAlign = TextAlign.Center
                )
            }

            // Motivational Message
            Text(
                text = getMotivationalMessage(creditBalance),
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colors.text.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = AppTheme.spacing.defaultSpacing)
            )
        }
    }
}

@Composable
private fun CreditActionsSection(
    isPremiumUser: Boolean,
    onUpgradeToPremium: () -> Unit,
    onBuyCreditPack: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.groupedVerticalElementSpacing)
    ) {
        // Primary Action: Buy Credits
        AppCardContainer(modifier = Modifier.fillMaxWidth()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.groupedVerticalElementSpacing)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.defaultSpacing)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(AppTheme.colors.primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⚡",
                            style = AppTheme.typography.h4
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Need More Credits?",
                            style = AppTheme.typography.h5,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.text.primary
                        )
                        Text(
                            text = "Get instant credits to continue creating amazing content",
                            style = AppTheme.typography.bodyMedium,
                            color = AppTheme.colors.text.secondary
                        )
                    }
                }

                AppButton(
                    text = "Buy Credits",
                    style = ButtonStyle.PRIMARY,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onBuyCreditPack
                )
            }
        }

        // Secondary Action: Premium Upgrade (if not premium user)
        if (!isPremiumUser) {
            AppCardContainer(modifier = Modifier.fillMaxWidth()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.groupedVerticalElementSpacing)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.defaultSpacing)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(AppTheme.colors.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "👑",
                                style = AppTheme.typography.h4
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Upgrade to Premium",
                                style = AppTheme.typography.h5,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.text.primary
                            )
                            Text(
                                text = "Get more credits + premium features",
                                style = AppTheme.typography.bodyMedium,
                                color = AppTheme.colors.text.secondary
                            )
                        }
                    }

                    AppButton(
                        text = stringResource(Res.string.btn_get_premium),
                        style = ButtonStyle.ALTERNATIVE,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onUpgradeToPremium
                    )
                }
            }
        }
    }
}

@Composable
private fun CreditCostInfoSection() {
    AppCardContainer(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.groupedVerticalElementSpacing)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.defaultSpacing)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "💡",
                        style = AppTheme.typography.h4
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "How Credits Work",
                        style = AppTheme.typography.h5,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.text.primary
                    )
                    Text(
                        text = "Understanding your credit usage",
                        style = AppTheme.typography.bodyMedium,
                        color = AppTheme.colors.text.secondary
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.defaultSpacing)
            ) {
                CreditCostItem(
                    icon = "🎨",
                    action = "AI Generation",
                    cost = "1 Credit"
                )

                //TODO Check Ads later
//                CreditCostItem(
//                    icon = "📺",
//                    action = "Watch Video Ad",
//                    cost = "+1 Credit"
//                )


            }
        }
    }
}

@Composable
private fun CreditCostItem(
    icon: String,
    action: String,
    cost: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.defaultSpacing)
        ) {
            Text(
                text = icon,
                style = AppTheme.typography.bodyLarge
            )
            Text(
                text = action,
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colors.text.primary
            )
        }

        Text(
            text = cost,
            style = AppTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (cost.startsWith("+")) AppTheme.colors.status.success else AppTheme.colors.primary
        )
    }
}

@Composable
private fun TransactionHistorySection(
    transactions: List<CreditTransaction>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    AppCardContainer(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.groupedVerticalElementSpacing)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Transaction History",
                    style = AppTheme.typography.h5,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.text.primary
                )

                AppButton(
                    text = if (isExpanded) "Hide" else "Show",
                    style = ButtonStyle.TEXT,
                    size = ButtonSize.SMALL,
                    //This 12 dp extra padding comes from Material Design so we remove that
                    modifier = Modifier.offset(x = 12.dp),
                    onClick = onToggleExpanded
                )
            }

            AnimatedVisibility(isExpanded) {
                CreditTransactionsList(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    transactions = transactions
                )
            }
        }
    }
}


private fun getMotivationalMessage(credits: Int): String {
    return when {
        credits == 0 -> "You're out of credits, but not out of ideas! You can top up and create right away."
        credits == 1 -> "One last credit left — make it count!"
        credits <= 2 -> "Running low on credits. Top up and keep the creativity flowing."
        credits <= 5 -> "Nice streak! Grab more credits to keep up the momentum."
        credits <= 10 -> "You're on a roll! More credits mean more chances to create something awesome."
        else -> "You've got plenty of credits to play with. Let's make something amazing!"
    }
}