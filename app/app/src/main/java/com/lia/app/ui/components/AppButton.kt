package com.lia.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lia.app.ui.theme.LiaDimensions
import com.lia.app.ui.theme.LiaTheme

enum class AppButtonVariant {
    PRIMARY,
    SECONDARY,
    OUTLINE,
    TEXT
}

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    fullWidth: Boolean = true
) {
    val sizeModifier = if (fullWidth) {
        modifier
            .fillMaxWidth()
            .height(LiaDimensions.ButtonHeight)
    } else {
        modifier.height(LiaDimensions.CompactButtonHeight)
    }

    val shape = MaterialTheme.shapes.small

    when (variant) {
        AppButtonVariant.PRIMARY -> {
            Button(
                onClick = onClick,
                modifier = sizeModifier,
                enabled = enabled && !loading,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = LiaDimensions.SpaceMD)
            ) {
                ButtonContent(text = text, icon = icon, loading = loading)
            }
        }

        AppButtonVariant.SECONDARY -> {
            Button(
                onClick = onClick,
                modifier = sizeModifier,
                enabled = enabled && !loading,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                contentPadding = PaddingValues(horizontal = LiaDimensions.SpaceMD)
            ) {
                ButtonContent(text = text, icon = icon, loading = loading)
            }
        }

        AppButtonVariant.OUTLINE -> {
            OutlinedButton(
                onClick = onClick,
                modifier = sizeModifier,
                enabled = enabled && !loading,
                shape = shape,
                border = BorderStroke(1.dp, LiaTheme.colors.borderStrong),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                contentPadding = PaddingValues(horizontal = LiaDimensions.SpaceMD)
            ) {
                ButtonContent(text = text, icon = icon, loading = loading)
            }
        }

        AppButtonVariant.TEXT -> {
            TextButton(
                onClick = onClick,
                modifier = if (fullWidth) modifier.fillMaxWidth() else modifier,
                enabled = enabled && !loading,
                shape = shape,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                ButtonContent(text = text, icon = icon, loading = loading)
            }
        }
    }
}

@Composable
private fun ButtonContent(
    text: String,
    icon: ImageVector?,
    loading: Boolean
) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp
        )
        Spacer(modifier = Modifier.width(LiaDimensions.SpaceXS))
    } else if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(LiaDimensions.SpaceXS))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge
    )
}
