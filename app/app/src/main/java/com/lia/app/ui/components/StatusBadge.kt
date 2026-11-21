package com.lia.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lia.app.data.model.GarmentStatus
import com.lia.app.ui.theme.LiaTheme

@Composable
fun StatusBadge(
    status: GarmentStatus,
    modifier: Modifier = Modifier
) {
    val colors = LiaTheme.colors
    val (bg, fg) = when (status) {
        GarmentStatus.IN_CATALOG -> colors.statusInCatalogBg to colors.statusInCatalogText
        GarmentStatus.SOLD -> colors.statusSoldBg to colors.statusSoldText
        GarmentStatus.ARCHIVED -> colors.statusArchivedBg to colors.statusArchivedText
    }

    Text(
        text = status.label,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
