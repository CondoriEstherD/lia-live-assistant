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
import com.lia.app.data.sample.PendingRegistrationProfile
import com.lia.app.ui.components.AppButton
import com.lia.app.ui.components.AppButtonVariant
import com.lia.app.ui.components.AppCard
import com.lia.app.ui.components.AppTextField
import com.lia.app.ui.theme.LiaDimensions
import com.lia.app.ui.theme.LiaTheme

@Composable
fun CreateAccountScreen(
    isBusy: Boolean,
    authError: String?,
    awaitingEmailVerification: Boolean,
    onBackClick: () -> Unit,
    onSignInClick: () -> Unit,
    onRegisterWithEmail: (
        email: String,
        password: String,
        whatsapp: String,
        name: String,
        username: String,
        socialLinks: String
    ) -> Unit,
    onVerifyEmailCode: (String) -> Unit,
    onRegisterWithSocial: (SocialAuthProvider, PendingRegistrationProfile) -> Unit,
    onClearError: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var whatsapp by rememberSaveable { mutableStateOf("+591 ") }
    var name by rememberSaveable { mutableStateOf("") }
    var socialLinks by rememberSaveable { mutableStateOf("") }
    var verificationCode by rememberSaveable { mutableStateOf("") }

    var emailError by rememberSaveable { mutableStateOf<String?>(null) }
    var passwordError by rememberSaveable { mutableStateOf<String?>(null) }
    var whatsappError by rememberSaveable { mutableStateOf<String?>(null) }
    var codeError by rememberSaveable { mutableStateOf<String?>(null) }

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

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceXS))

        Text(
            text = if (awaitingEmailVerification) "Verifica tu correo" else "Crear cuenta",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))
        Text(
            text = if (awaitingEmailVerification) {
                "Ingresa el código de 6 dígitos que enviamos a $email."
            } else {
                "Completa tus datos de acceso y tu WhatsApp de ventas."
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

        if (awaitingEmailVerification) {
            AppTextField(
                value = verificationCode,
                onValueChange = {
                    verificationCode = it.filter { ch -> ch.isDigit() }
                    codeError = null
                    if (authError != null) onClearError()
                },
                label = "Código de verificación",
                placeholder = "123456",
                errorText = codeError,
                enabled = !isBusy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))

            AppButton(
                text = "Verificar y entrar",
                loading = isBusy,
                onClick = {
                    if (verificationCode.length < 4) {
                        codeError = "Ingresa el código que recibiste en tu correo."
                    } else {
                        onVerifyEmailCode(verificationCode)
                    }
                }
            )
        } else {
            AppTextField(
                value = whatsapp,
                onValueChange = {
                    whatsapp = it
                    whatsappError = null
                    if (authError != null) onClearError()
                },
                label = "WhatsApp con código de país *",
                placeholder = "+591 70012345",
                supportingText = "Obligatorio. Incluye el código de país (+591).",
                errorText = whatsappError,
                enabled = !isBusy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
            )

            Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceSM)
            ) {
                AppButton(
                    text = "Google",
                    onClick = {
                        if (isValidWhatsapp(whatsapp)) {
                            onRegisterWithSocial(
                                SocialAuthProvider.GOOGLE,
                                PendingRegistrationProfile(whatsapp, name, username, socialLinks)
                            )
                        } else {
                            whatsappError = "Primero ingresa tu WhatsApp con código de país (ej. +591 70012345)."
                        }
                    },
                    variant = AppButtonVariant.OUTLINE,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                )
                AppButton(
                    text = "TikTok",
                    onClick = {
                        if (isValidWhatsapp(whatsapp)) {
                            onRegisterWithSocial(
                                SocialAuthProvider.TIKTOK,
                                PendingRegistrationProfile(whatsapp, name, username, socialLinks)
                            )
                        } else {
                            whatsappError = "Primero ingresa tu WhatsApp con código de país (ej. +591 70012345)."
                        }
                    },
                    variant = AppButtonVariant.OUTLINE,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceSM)
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = LiaTheme.colors.borderSubtle)
                Text(
                    text = "o regístrate con correo",
                    style = MaterialTheme.typography.bodySmall,
                    color = LiaTheme.colors.textMuted
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = LiaTheme.colors.borderSubtle)
            }

            Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))

            AppTextField(
                value = email,
                onValueChange = {
                    email = it
                    emailError = null
                    if (authError != null) onClearError()
                },
                label = "Correo electrónico *",
                placeholder = "vendedora@correo.com",
                errorText = emailError,
                enabled = !isBusy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )

            Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

            AppTextField(
                value = password,
                onValueChange = {
                    password = it
                    passwordError = null
                    if (authError != null) onClearError()
                },
                label = "Contraseña *",
                errorText = passwordError,
                enabled = !isBusy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

            AppTextField(
                value = username,
                onValueChange = { username = it },
                label = "Nombre de usuario (opcional)",
                placeholder = "camilalive",
                enabled = !isBusy
            )

            Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

            AppTextField(
                value = name,
                onValueChange = { name = it },
                label = "Nombre o tienda (opcional)",
                placeholder = "Camila Moda Live",
                enabled = !isBusy
            )

            Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

            AppTextField(
                value = socialLinks,
                onValueChange = { socialLinks = it },
                label = "Redes sociales (opcional)",
                placeholder = "tiktok.com/@camilalive",
                enabled = !isBusy
            )

            Spacer(modifier = Modifier.height(LiaDimensions.SpaceLG))

            AppButton(
                text = "Crear cuenta",
                loading = isBusy,
                onClick = {
                    var valid = true
                    if (!email.contains("@") || !email.contains(".")) {
                        emailError = "Ingresa un correo electrónico válido."
                        valid = false
                    }
                    if (password.length < 8) {
                        passwordError = "La contraseña debe tener al menos 8 caracteres."
                        valid = false
                    }
                    if (!isValidWhatsapp(whatsapp)) {
                        whatsappError = "Ingresa tu WhatsApp con código de país (ej. +591 70012345)."
                        valid = false
                    }
                    if (valid) {
                        onRegisterWithEmail(email, password, whatsapp, name, username, socialLinks)
                    }
                }
            )

            Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

            AppButton(
                text = "¿Ya tienes cuenta? Iniciar sesión",
                onClick = onSignInClick,
                variant = AppButtonVariant.TEXT,
                enabled = !isBusy
            )
        }

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceLG))
    }
}

internal fun isValidWhatsapp(value: String): Boolean {
    val cleaned = value.trim().replace(" ", "").replace("-", "")
    return cleaned.startsWith("+") && cleaned.drop(1).length in 8..15 && cleaned.drop(1).all { it.isDigit() }
}
