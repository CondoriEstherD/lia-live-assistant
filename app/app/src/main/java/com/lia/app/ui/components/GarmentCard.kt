package com.lia.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lia.app.data.model.Garment
import com.lia.app.data.model.GarmentStatus
import com.lia.app.data.sample.LiaFormatters
import com.lia.app.ui.theme.LiaDimensions
import com.lia.app.ui.theme.LiaTheme

@Composable
fun GarmentCard(
    garment: Garment,
    onEditClick: () -> Unit,
    onSellClick: () -> Unit,
    onArchiveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier,
        onClick = onEditClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Consistent thumbnail container with or without photo
            Box(
                modifier = Modifier
                    .size(LiaDimensions.GarmentThumbnailSize)
                    .clip(MaterialTheme.shapes.small)
                    .background(LiaTheme.colors.pinkSoftBg),
                contentAlignment = Alignment.Center
            ) {
                if (garment.photoUri != null) {
                    AsyncImage(
                        model = garment.photoUri,
                        contentDescription = garment.displayTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Checkroom,
                        contentDescription = null,
                        tint = LiaTheme.colors.pinkStrong,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(LiaDimensions.SpaceMD))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = garment.code,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    StatusBadge(status = garment.status)
                }

                Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))

                Text(
                    text = garment.name.ifBlank { "Sin nombre" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (garment.name.isBlank()) {
                        LiaTheme.colors.textMuted
                    } else {
                        LiaTheme.colors.textSecondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(LiaDimensions.SpaceXS))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = LiaFormatters.formatBs(garment.sellingPrice),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    val stockLabel = if (garment.stock == 1) "1 unidad" else "${garment.stock} unidades"
                    Text(
                        text = "Stock: $stockLabel",
                        style = MaterialTheme.typography.labelMedium,
                        color = LiaTheme.colors.textMuted
                    )
                }
            }
        }

        if (garment.status == GarmentStatus.IN_CATALOG) {
            Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))
            HorizontalDivider(color = LiaTheme.colors.borderSubtle)
            Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceXS)
            ) {
                AppButton(
                    text = "Registrar venta",
                    onClick = onSellClick,
                    variant = AppButtonVariant.SECONDARY,
                    icon = Icons.Outlined.PointOfSale,
                    fullWidth = false,
                    modifier = Modifier.weight(1f)
                )
                AppButton(
                    text = "Archivar",
                    onClick = onArchiveClick,
                    variant = AppButtonVariant.OUTLINE,
                    icon = Icons.Outlined.Archive,
                    fullWidth = false
                )
            }
        }
    }
}
