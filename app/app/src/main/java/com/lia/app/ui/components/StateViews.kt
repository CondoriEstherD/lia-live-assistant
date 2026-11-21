package com.lia.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lia.app.ui.theme.LiaDimensions
import com.lia.app.ui.theme.LiaTheme

@Composable
fun EmptyStateView(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Inventory2,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = LiaDimensions.SpaceXXL, horizontal = LiaDimensions.SpaceLG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = LiaTheme.colors.textMuted,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))
            AppButton(
                text = actionLabel,
                onClick = onActionClick,
                variant = AppButtonVariant.SECONDARY,
                fullWidth = false
            )
        }
    }
}

@Composable
fun LoadingStateView(
    label: String = "Cargando...",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = LiaDimensions.SpaceXXL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp
        )
        Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = LiaTheme.colors.textMuted
        )
    }
}

@Composable
fun ErrorStateView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = LiaDimensions.SpaceXXL, horizontal = LiaDimensions.SpaceLG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))
        Text(
            text = "Ocurrió un problema",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = LiaTheme.colors.textMuted,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))
        AppButton(
            text = "Reintentar",
            onClick = onRetry,
            variant = AppButtonVariant.PRIMARY,
            fullWidth = false
        )
    }
}
