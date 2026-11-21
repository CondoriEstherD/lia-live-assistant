package com.lia.app.ui.screens.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.clerk.ui.userbutton.UserButton
import com.lia.app.data.model.SellerProfile
import com.lia.app.data.model.ThemeMode
import com.lia.app.ui.components.AppButton
import com.lia.app.ui.components.AppButtonVariant
import com.lia.app.ui.components.AppCard
import com.lia.app.ui.components.AppTextField
import com.lia.app.ui.components.ConfirmationDialog
import com.lia.app.ui.screens.auth.isValidWhatsapp
import com.lia.app.ui.theme.LiaDimensions
import com.lia.app.ui.theme.LiaTheme

@Composable
fun SellerDataScreen(
    profile: SellerProfile,
    isSaving: Boolean,
    themeMode: ThemeMode,
    onUpdateData: (whatsapp: String, name: String, username: String, socialLinks: String) -> Unit,
    onSelectTheme: (ThemeMode) -> Unit,
    onSignOut: () -> Unit
) {
    var whatsapp by rememberSaveable(profile.whatsapp) { mutableStateOf(profile.whatsapp) }
    var name by rememberSaveable(profile.name) { mutableStateOf(profile.name) }
    var socialLinks by rememberSaveable(profile.socialLinks) { mutableStateOf(profile.socialLinks) }
    var whatsappError by rememberSaveable { mutableStateOf<String?>(null) }
    var showSignOutDialog by remember { mutableStateOf(false) }

    if (showSignOutDialog) {
        ConfirmationDialog(
            title = "Cerrar sesión",
            message = "¿Seguro que quieres cerrar sesión en esta cuenta?",
            confirmText = "Cerrar sesión",
            isDestructive = true,
            onConfirm = {
                showSignOutDialog = false
                onSignOut()
            },
            onDismiss = { showSignOutDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = LiaDimensions.ScreenHorizontalPadding,
            end = LiaDimensions.ScreenHorizontalPadding,
            top = LiaDimensions.SpaceMD,
            bottom = LiaDimensions.ListBottomClearance
        ),
        verticalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceMD)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Datos",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))
                    Text(
                        text = "Información de tu tienda y apariencia",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LiaTheme.colors.textSecondary
                    )
                }
                UserButton()
            }
        }

        item {
            AppCard {
                Text(
                    text = "Datos de ventas",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))
                Text(
                    text = "Datos de tu negocio que no se administran desde Clerk.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LiaTheme.colors.textMuted
                )
                Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))

                AppTextField(
                    value = whatsapp,
                    onValueChange = {
                        whatsapp = it
                        whatsappError = null
                    },
                    label = "WhatsApp con código de país *",
                    placeholder = "+591 70012345",
                    errorText = whatsappError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )

                Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Nombre de tienda (opcional)",
                    placeholder = "Ej. Camila Moda Live"
                )

                Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

                AppTextField(
                    value = socialLinks,
                    onValueChange = { socialLinks = it },
                    label = "Links de redes sociales (opcional)",
                    placeholder = "tiktok.com/@tutienda"
                )

                Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))

                AppButton(
                    text = "Guardar datos",
                    loading = isSaving,
                    onClick = {
                        if (!isValidWhatsapp(whatsapp)) {
                            whatsappError = "Ingresa tu WhatsApp con código de país (ej. +591 70012345)."
                        } else {
                            onUpdateData(whatsapp, name, profile.username, socialLinks)
                        }
                    }
                )
            }
        }

        item {
            AppCard {
                Text(
                    text = "Apariencia",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(LiaDimensions.SpaceXS))
                Text(
                    text = "Tu elección se guarda automáticamente al reiniciar la app",
                    style = MaterialTheme.typography.bodySmall,
                    color = LiaTheme.colors.textMuted
                )
                Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceSM)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = mode == themeMode
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectTheme(mode) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (mode == ThemeMode.LIGHT) {
                                        Icons.Outlined.LightMode
                                    } else {
                                        Icons.Outlined.DarkMode
                                    },
                                    contentDescription = null
                                )
                            },
                            label = { Text(mode.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LiaTheme.colors.pinkSoftBg,
                                selectedLabelColor = LiaTheme.colors.pinkStrong,
                                selectedLeadingIconColor = LiaTheme.colors.pinkStrong
                            )
                        )
                    }
                }
            }
        }

        item {
            AppButton(
                text = "Cerrar sesión",
                onClick = { showSignOutDialog = true },
                variant = AppButtonVariant.OUTLINE,
                icon = Icons.AutoMirrored.Outlined.Logout
            )
        }
    }
}
