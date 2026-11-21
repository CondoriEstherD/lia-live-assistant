package com.lia.app.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lia.app.ui.theme.LiaDimensions
import com.lia.app.ui.theme.LiaTheme

@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    highlight: Boolean = false
) {
    AppCard(
        modifier = modifier,
        containerColor = if (highlight) {
            LiaTheme.colors.pinkSoftBg
        } else {
            LiaTheme.colors.surfaceElevated
        }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlight) {
                LiaTheme.colors.pinkStrong
            } else {
                LiaTheme.colors.textSecondary
            }
        )
        Spacer(modifier = Modifier.height(LiaDimensions.SpaceXS))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = if (highlight) {
                LiaTheme.colors.pinkStrong
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
        if (supportingText != null) {
            Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = LiaTheme.colors.textMuted
            )
        }
    }
}
