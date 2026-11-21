package com.lia.app.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.lia.app.ui.components.AppButton
import com.lia.app.ui.components.AppButtonVariant
import com.lia.app.ui.components.AppCard
import com.lia.app.ui.components.AppTextField
import com.lia.app.ui.theme.LiaDimensions
import com.lia.app.ui.theme.LiaTheme

@Composable
fun CompleteProfileScreen(
    initialName: String,
    initialUsername: String,
    isSaving: Boolean,
    errorMessage: String?,
    onCompleteProfile: (whatsapp: String, name: String, username: String, socialLinks: String) -> Unit,
    onSignOutClick: () -> Unit
) {
    var whatsapp by rememberSaveable { mutableStateOf("+591 ") }
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var username by rememberSaveable(initialUsername) { mutableStateOf(initialUsername) }
    var socialLinks by rememberSaveable { mutableStateOf("") }
    var whatsappError by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LiaDimensions.ScreenHorizontalPadding, vertical = LiaDimensions.SpaceLG)
    ) {
        Text(
            text = "Completa tu perfil",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))
        Text(
            text = "Solo necesitamos tu WhatsApp de ventas para activar tu cuenta de vendedora.",
            style = MaterialTheme.typography.bodyMedium,
            color = LiaTheme.colors.textSecondary
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))
            AppCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceLG))

        AppTextField(
            value = whatsapp,
            onValueChange = {
                whatsapp = it
                whatsappError = null
            },
            label = "WhatsApp con código de país *",
            placeholder = "+591 70012345",
            supportingText = "Obligatorio. Incluye el código de país (+591).",
            errorText = whatsappError,
            enabled = !isSaving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

        AppTextField(
            value = name,
            onValueChange = { name = it },
            label = "Nombre o tienda (opcional)",
            placeholder = "Camila Moda Live",
            enabled = !isSaving
        )

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

        AppTextField(
            value = username,
            onValueChange = { username = it },
            label = "Nombre de usuario (opcional)",
            placeholder = "camilalive",
            enabled = !isSaving
        )

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

        AppTextField(
            value = socialLinks,
            onValueChange = { socialLinks = it },
            label = "Redes sociales (opcional)",
            placeholder = "tiktok.com/@camilalive",
            enabled = !isSaving
        )

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceLG))

        AppButton(
            text = "Guardar y continuar",
            loading = isSaving,
            onClick = {
                if (!isValidWhatsapp(whatsapp)) {
                    whatsappError = "Ingresa tu WhatsApp con código de país (ej. +591 70012345)."
                } else {
                    onCompleteProfile(whatsapp, name, username, socialLinks)
                }
            }
        )

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

        AppButton(
            text = "Usar otra cuenta (Cerrar sesión)",
            onClick = onSignOutClick,
            variant = AppButtonVariant.TEXT,
            enabled = !isSaving
        )
    }
}
