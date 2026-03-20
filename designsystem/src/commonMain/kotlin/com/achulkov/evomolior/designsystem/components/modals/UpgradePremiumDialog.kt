package com.achulkov.evomolior.designsystem.components.modals

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.achulkov.evomolior.designsystem.components.AppButton
import com.achulkov.evomolior.designsystem.generated.resources.UiRes
import com.achulkov.evomolior.designsystem.generated.resources.ic_premium_feature_crown
import com.achulkov.evomolior.designsystem.util.PreviewHelper
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun UpgradePremiumDialog(
    onConfirm: () -> Unit = {},
    onDismiss: () -> Unit = {}
) {

    AppDialog(
        title = "Premium Features!",
        text = "Upgrade plan to unlock premium features and content!",
        image = {
            Image(
                painter = painterResource(UiRes.drawable.ic_premium_feature_crown),
                contentDescription = null,
            )
        },
        btnConfirmText = "Upgrade Plan",
        btnDismissText = "Maybe Later",
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

@Preview
@Composable
internal fun UpgradePremiumDialogPreview() {
    var isDialogVisible by remember { mutableStateOf(false) }
    AppButton("Show Premium Dialog", onClick = { isDialogVisible = true })
    PreviewHelper {
        if (isDialogVisible) {
            UpgradePremiumDialog(
                onConfirm = { isDialogVisible = false },
                onDismiss = { isDialogVisible = false }
            )
        }

    }
}