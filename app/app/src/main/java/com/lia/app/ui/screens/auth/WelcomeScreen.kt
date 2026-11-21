package com.lia.app.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lia.app.ui.components.AppButton
import com.lia.app.ui.components.AppButtonVariant
import com.lia.app.ui.theme.LiaDimensions
import com.lia.app.ui.theme.LiaTheme

@Composable
fun WelcomeScreen(
    onSignInClick: () -> Unit,
    onCreateAccountClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = LiaDimensions.SpaceXL, vertical = LiaDimensions.SpaceXL),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(LiaDimensions.SpaceLG))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(LiaTheme.colors.pinkSoftBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Storefront,
                    contentDescription = "Lia",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(LiaDimensions.SpaceLG))

            Text(
                text = "Lia",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(LiaDimensions.SpaceXS))

            Text(
                text = "Tu asistente para vender en vivo sin perder el control de tu inventario ni tus ventas.",
                style = MaterialTheme.typography.bodyLarge,
                color = LiaTheme.colors.textSecondary,
                textAlign = TextAlign.Center
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceSM)
        ) {
            AppButton(
                text = "Crear cuenta",
                onClick = onCreateAccountClick,
                variant = AppButtonVariant.PRIMARY
            )
            AppButton(
                text = "Iniciar sesión",
                onClick = onSignInClick,
                variant = AppButtonVariant.OUTLINE
            )
        }
    }
}
