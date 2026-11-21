package com.lia.app.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.lia.app.ui.components.AppButton
import com.lia.app.ui.components.AppCard
import com.lia.app.ui.components.AppTextField
import com.lia.app.ui.theme.LiaDimensions
import com.lia.app.ui.theme.LiaTheme

@Composable
fun RecoveryScreen(
    isBusy: Boolean,
    authError: String?,
    codeSent: Boolean,
    onBackClick: () -> Unit,
    onSendResetCode: (String) -> Unit,
    onConfirmReset: (code: String, newPassword: String) -> Unit,
    onClearError: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }

    var emailError by rememberSaveable { mutableStateOf<String?>(null) }
    var codeError by rememberSaveable { mutableStateOf<String?>(null) }
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
            text = "Recuperar cuenta",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))
        Text(
            text = if (codeSent) {
                "Ingresa el código enviado a $email y tu nueva contraseña."
            } else {
                "Te enviaremos un código de verificación a tu correo registrado."
            },
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

        if (!codeSent) {
            AppTextField(
                value = email,
                onValueChange = {
                    email = it
                    emailError = null
                    if (authError != null) onClearError()
                },
                label = "Correo electrónico",
                placeholder = "vendedora@correo.com",
                errorText = emailError,
                enabled = !isBusy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )

            Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))

            AppButton(
                text = "Enviar código",
                loading = isBusy,
                onClick = {
                    if (!email.contains("@") || !email.contains(".")) {
                        emailError = "Ingresa un correo electrónico válido."
                    } else {
                        onSendResetCode(email)
                    }
                }
            )
        } else {
            AppTextField(
                value = code,
                onValueChange = {
                    code = it.filter { ch -> ch.isDigit() }
                    codeError = null
                    if (authError != null) onClearError()
                },
                label = "Código de verificación",
                placeholder = "123456",
                errorText = codeError,
                enabled = !isBusy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

            AppTextField(
                value = newPassword,
                onValueChange = {
                    newPassword = it
                    passwordError = null
                    if (authError != null) onClearError()
                },
                label = "Nueva contraseña",
                errorText = passwordError,
                enabled = !isBusy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))

            AppButton(
                text = "Actualizar contraseña",
                loading = isBusy,
                onClick = {
                    var valid = true
                    if (code.length < 4) {
                        codeError = "Ingresa el código recibido."
                        valid = false
                    }
                    if (newPassword.length < 8) {
                        passwordError = "La contraseña debe tener al menos 8 caracteres."
                        valid = false
                    }
                    if (valid) onConfirmReset(code, newPassword)
                }
            )
        }
    }
}
