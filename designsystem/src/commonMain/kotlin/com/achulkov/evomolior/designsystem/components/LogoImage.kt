package com.achulkov.evomolior.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.achulkov.evomolior.designsystem.generated.resources.UiRes
import com.achulkov.evomolior.designsystem.generated.resources.ic_logo
import com.achulkov.evomolior.designsystem.util.PreviewHelper
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun LogoImage(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(UiRes.drawable.ic_logo),
            contentDescription = null,
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .align(Alignment.Center)
        )
    }
}

@Composable
@Preview
internal fun LogoImagePreview() {
    PreviewHelper {
        LogoImage()
    }
}