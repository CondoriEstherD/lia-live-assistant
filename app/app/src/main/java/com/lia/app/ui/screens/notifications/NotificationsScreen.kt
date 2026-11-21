package com.lia.app.ui.screens.notifications

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lia.app.data.model.AppNotification
import com.lia.app.data.model.UiLoadState
import com.lia.app.ui.components.AppButton
import com.lia.app.ui.components.AppButtonVariant
import com.lia.app.ui.components.AppCard
import com.lia.app.ui.components.EmptyStateView
import com.lia.app.ui.components.ErrorStateView
import com.lia.app.ui.components.LoadingStateView
import com.lia.app.ui.theme.LiaDimensions
import com.lia.app.ui.theme.LiaTheme

@Composable
fun NotificationsScreen(
    notificationsEnabled: Boolean,
    notifications: List<AppNotification>,
    loadState: UiLoadState,
    onToggleNotifications: (Boolean) -> Unit,
    onMarkNotificationRead: (String) -> Unit,
    onMarkAllNotificationsRead: () -> Unit,
    onRetry: () -> Unit
) {
    val unreadCount = remember(notifications) { notifications.count { it.isUnread } }

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
                        text = "Notificaciones",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))
                    Text(
                        text = if (unreadCount > 0) {
                            "Tienes $unreadCount sin leer"
                        } else {
                            "Avisos de ventas y novedades de tu catálogo"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = LiaTheme.colors.textSecondary
                    )
                }

                if (notificationsEnabled && unreadCount > 0) {
                    AppButton(
                        text = "Marcar leídas",
                        onClick = onMarkAllNotificationsRead,
                        variant = AppButtonVariant.TEXT,
                        fullWidth = false
                    )
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
                                text = "Mostrar avisos en la app",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (notificationsEnabled) "Activos" else "Ocultos temporalmente",
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

        if (!notificationsEnabled) {
            item {
                EmptyStateView(
                    title = "Notificaciones ocultas",
                    description = "Activa el interruptor de arriba cuando quieras volver a ver tus avisos.",
                    icon = Icons.Outlined.NotificationsNone
                )
            }
        } else {
            when (loadState) {
                UiLoadState.Loading -> {
                    item { LoadingStateView(label = "Cargando notificaciones...") }
                }

                is UiLoadState.Error -> {
                    item { ErrorStateView(message = loadState.message, onRetry = onRetry) }
                }

                UiLoadState.Ready -> {
                    if (notifications.isEmpty()) {
                        item {
                            EmptyStateView(
                                title = "Sin notificaciones",
                                description = "Aquí aparecerán tus avisos de ventas confirmadas, pagos tardíos y novedades.",
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
            }
        }
    }
}
