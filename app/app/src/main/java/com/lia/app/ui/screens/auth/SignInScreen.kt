package com.lia.app.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.lia.app.data.auth.SocialAuthProvider
import com.lia.app.ui.components.AppButton
import com.lia.app.ui.components.AppButtonVariant
import com.lia.app.ui.components.AppCard
import com.lia.app.ui.components.AppTextField
import com.lia.app.ui.theme.LiaDimensions
import com.lia.app.ui.theme.LiaTheme

@Composable
fun SignInScreen(
    isBusy: Boolean,
    authError: String?,
    onBackClick: () -> Unit,
    onForgotPasswordClick: () -> Unit,
    onCreateAccountClick: () -> Unit,
    onSignInWithPassword: (identifier: String, password: String) -> Unit,
    onSignInWithSocial: (SocialAuthProvider) -> Unit,
    onClearError: () -> Unit
) {
    var identifier by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var identifierError by rememberSaveable { mutableStateOf<String?>(null) }
    var passwordError by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LiaDimensions.ScreenHorizontalPadding, vertical = LiaDimensions.SpaceMD)
    ) {
        IconButton(onClick = onBackClick, enabled = !isBusy) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Volver",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

        Text(
            text = "Iniciar sesión",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))
        Text(
            text = "Ingresa con tu correo, usuario o cuenta vinculada.",
            style = MaterialTheme.typography.bodyMedium,
            color = LiaTheme.colors.textSecondary
        )

        if (authError != null) {
            Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))
            AppCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text = authError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceLG))

        AppTextField(
            value = identifier,
            onValueChange = {
                identifier = it
                identifierError = null
                if (authError != null) onClearError()
            },
            label = "Correo o usuario",
            placeholder = "ejemplo@correo.com o usuario",
            errorText = identifierError,
            enabled = !isBusy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))

        AppTextField(
            value = password,
            onValueChange = {
                password = it
                passwordError = null
                if (authError != null) onClearError()
            },
            label = "Contraseña",
            errorText = passwordError,
            enabled = !isBusy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceXS))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            AppButton(
                text = "¿Olvidaste tu contraseña?",
                onClick = onForgotPasswordClick,
                variant = AppButtonVariant.TEXT,
                enabled = !isBusy,
                fullWidth = false
            )
        }

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))

        AppButton(
            text = "Entrar",
            loading = isBusy,
            onClick = {
                var valid = true
                if (identifier.isBlank()) {
                    identifierError = "Ingresa tu correo o nombre de usuario."
                    valid = false
                }
                if (password.isBlank()) {
                    passwordError = "Ingresa tu contraseña."
                    valid = false
                }
                if (valid) onSignInWithPassword(identifier, password)
            }
        )

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceLG))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceSM)
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = LiaTheme.colors.borderSubtle)
            Text(
                text = "o continúa con",
                style = MaterialTheme.typography.bodySmall,
                color = LiaTheme.colors.textMuted
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = LiaTheme.colors.borderSubtle)
        }

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceSM)
        ) {
            AppButton(
                text = "Google",
                onClick = { onSignInWithSocial(SocialAuthProvider.GOOGLE) },
                variant = AppButtonVariant.OUTLINE,
                enabled = !isBusy,
                modifier = Modifier.weight(1f)
            )
            AppButton(
                text = "TikTok",
                onClick = { onSignInWithSocial(SocialAuthProvider.TIKTOK) },
                variant = AppButtonVariant.OUTLINE,
                enabled = !isBusy,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceLG))

        AppButton(
            text = "¿No tienes cuenta? Crear cuenta",
            onClick = onCreateAccountClick,
            variant = AppButtonVariant.TEXT,
            enabled = !isBusy
        )
    }
}
