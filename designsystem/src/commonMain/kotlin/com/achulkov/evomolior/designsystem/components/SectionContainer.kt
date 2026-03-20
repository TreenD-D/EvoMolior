package com.achulkov.evomolior.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.achulkov.evomolior.designsystem.theme.AppTheme
import com.achulkov.evomolior.designsystem.util.PreviewHelper
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun SectionContainer(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onClickSubtitle: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {

    Column(modifier = modifier) {
        if (title != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle(text = title)
                subtitle?.let {
                    Text(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                onClickSubtitle()
                            },
                        text = it,
                        color = AppTheme.colors.primary,
                        style = AppTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppTheme.spacing.sectionHeaderSpacing))
        }
        content()
    }
}

@Composable
@Preview
internal fun SectionContainerPreview() {
    PreviewHelper {
        SectionContainer(title = "Section Title", subtitle = "Section Subtitle") {
            Text(text = "Section Content")
        }
    }
}