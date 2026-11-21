package com.lia.app.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.clerk.ui.userbutton.UserButton
import com.lia.app.data.model.AppNotification
import com.lia.app.data.model.SellerProfile
import com.lia.app.data.model.ThemeMode
import com.lia.app.ui.components.AppButton
import com.lia.app.ui.components.AppButtonVariant
import com.lia.app.ui.components.AppCard
import com.lia.app.ui.components.AppTextField
import com.lia.app.ui.components.ConfirmationDialog
import com.lia.app.ui.components.EmptyStateView
import com.lia.app.ui.screens.auth.isValidWhatsapp
import com.lia.app.ui.theme.LiaDimensions
import com.lia.app.ui.theme.LiaTheme

@Composable
fun ProfileScreen(
    profile: SellerProfile,
    isSavingProfile: Boolean,
    themeMode: ThemeMode,
    notificationsEnabled: Boolean,
    notifications: List<AppNotification>,
    onUpdateProfile: (whatsapp: String, name: String, username: String, socialLinks: String) -> Unit,
    onSelectTheme: (ThemeMode) -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onMarkNotificationRead: (String) -> Unit,
    onMarkAllNotificationsRead: () -> Unit,
    onSignOut: () -> Unit
) {
    var whatsapp by rememberSaveable(profile.whatsapp) { mutableStateOf(profile.whatsapp) }
    var name by rememberSaveable(profile.name) { mutableStateOf(profile.name) }
    var username by rememberSaveable(profile.username) { mutableStateOf(profile.username) }
    var socialLinks by rememberSaveable(profile.socialLinks) { mutableStateOf(profile.socialLinks) }
    var whatsappError by rememberSaveable { mutableStateOf<String?>(null) }
    var showSignOutDialog by remember { mutableStateOf(false) }

    val unreadCount = remember(notifications) { notifications.count { it.isUnread } }

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
                        text = "Perfil",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))
                    Text(
                        text = "Datos de tu cuenta, apariencia y notificaciones",
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
                    text = "Datos de la vendedora",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
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
                    label = "Nombre o tienda (opcional)"
                )

                Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

                AppTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = "Nombre de usuario (opcional)"
                )

                Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

                AppTextField(
                    value = socialLinks,
                    onValueChange = { socialLinks = it },
                    label = "Links de redes sociales (opcional)"
                )

                Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))

                AppButton(
                    text = "Guardar datos",
                    loading = isSavingProfile,
                    onClick = {
                        if (!isValidWhatsapp(whatsapp)) {
                            whatsappError = "Ingresa tu WhatsApp con código de país (ej. +591 70012345)."
                        } else {
                            onUpdateProfile(whatsapp, name, username, socialLinks)
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
            AppCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceSM),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Notificaciones en la app",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (notificationsEnabled) {
                                    "Mostrando avisos de ventas y pagos"
                                } else {
                                    "Avisos ocultos temporalmente"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = LiaTheme.colors.textMuted
                            )
                        }
                    }

                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = onToggleNotifications,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        if (notificationsEnabled) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (unreadCount > 0) {
                            "Notificaciones ($unreadCount sin leer)"
                        } else {
                            "Notificaciones"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (unreadCount > 0) {
                        AppButton(
                            text = "Marcar leídas",
                            onClick = onMarkAllNotificationsRead,
                            variant = AppButtonVariant.TEXT,
                            fullWidth = false
                        )
                    }
                }
            }

            if (notifications.isEmpty()) {
                item {
                    EmptyStateView(
                        title = "Sin notificaciones",
                        description = "Aquí aparecerán tus avisos de ventas confirmadas y novedades de tu catálogo.",
                        icon = Icons.Outlined.NotificationsNone
                    )
                }
            } else {
                items(notifications, key = { it.id }) { item ->
                    AppCard(
                        onClick = if (item.isUnread) {
                            { onMarkNotificationRead(item.id) }
                        } else {
                            null
                        },
                        containerColor = if (item.isUnread) {
                            LiaTheme.colors.pinkSoftBg
                        } else {
                            LiaTheme.colors.surfaceElevated
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (item.isUnread) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                    Spacer(modifier = Modifier.width(LiaDimensions.SpaceXS))
                                }
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = item.timestampLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = LiaTheme.colors.textMuted
                            )
                        }
                        Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))
                        Text(
                            text = item.body,
                            style = MaterialTheme.typography.bodySmall,
                            color = LiaTheme.colors.textSecondary
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
