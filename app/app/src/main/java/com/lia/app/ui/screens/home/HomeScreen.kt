package com.lia.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lia.app.data.model.SaleRecord
import com.lia.app.data.model.SalesPeriod
import com.lia.app.data.model.UiLoadState
import com.lia.app.data.sample.LiaFormatters
import com.lia.app.ui.components.AppCard
import com.lia.app.ui.components.ConfirmationDialog
import com.lia.app.ui.components.EmptyStateView
import com.lia.app.ui.components.ErrorStateView
import com.lia.app.ui.components.LoadingStateView
import com.lia.app.ui.components.StatCard
import com.lia.app.ui.theme.LiaDimensions
import com.lia.app.ui.theme.LiaTheme

@Composable
fun HomeScreen(
    sellerName: String,
    selectedPeriod: SalesPeriod,
    sales: List<SaleRecord>,
    loadState: UiLoadState,
    onSelectPeriod: (SalesPeriod) -> Unit,
    onRevertSale: (String) -> Unit,
    onRetry: () -> Unit
) {
    var saleToRevert by remember { mutableStateOf<SaleRecord?>(null) }

    val totalRevenue = remember(sales) { sales.sumOf { it.totalAmount } }
    val totalUnits = remember(sales) { sales.sumOf { it.quantity } }

    saleToRevert?.let { sale ->
        ConfirmationDialog(
            title = "Corregir venta",
            message = "¿Deseas anular la venta de ${sale.displayLabel} (x${sale.quantity} · ${LiaFormatters.formatBs(sale.totalAmount)})? El stock volverá al inventario.",
            confirmText = "Corregir venta",
            isDestructive = true,
            onConfirm = {
                onRevertSale(sale.id)
                saleToRevert = null
            },
            onDismiss = { saleToRevert = null }
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
            Column {
                Text(
                    text = if (sellerName.isNotBlank()) "Hola, $sellerName" else "Inicio",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))
                Text(
                    text = "Resumen de ventas e historial",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LiaTheme.colors.textSecondary
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceXS)
            ) {
                SalesPeriod.entries.forEach { period ->
                    val selected = period == selectedPeriod
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectPeriod(period) },
                        label = {
                            Text(
                                text = period.label,
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = LiaTheme.colors.pinkSoftBg,
                            selectedLabelColor = LiaTheme.colors.pinkStrong,
                            containerColor = MaterialTheme.colorScheme.surface,
                            labelColor = LiaTheme.colors.textSecondary
                        )
                    )
                }
            }
        }

        when (loadState) {
            UiLoadState.Loading -> {
                item { LoadingStateView(label = "Cargando resumen...") }
            }

            is UiLoadState.Error -> {
                item { ErrorStateView(message = loadState.message, onRetry = onRetry) }
            }

            UiLoadState.Ready -> {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceSM)
                    ) {
                        StatCard(
                            label = "Ingresos",
                            value = LiaFormatters.formatBs(totalRevenue),
                            supportingText = selectedPeriod.label,
                            highlight = true,
                            modifier = Modifier.weight(1f)
                        )
                        StatCard(
                            label = "Unidades vendidas",
                            value = totalUnits.toString(),
                            supportingText = if (sales.size == 1) "1 venta" else "${sales.size} ventas",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    Text(
                        text = "Historial de ventas",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = LiaDimensions.SpaceXS)
                    )
                }

                if (sales.isEmpty()) {
                    item {
                        EmptyStateView(
                            title = "Sin ventas en este período",
                            description = "Cuando registres una venta desde Prendas, aparecerá aquí automáticamente.",
                            icon = Icons.Outlined.ReceiptLong
                        )
                    }
                } else {
                    items(sales, key = { it.id }) { sale ->
                        SaleHistoryRow(
                            sale = sale,
                            onRevertClick = { saleToRevert = sale }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SaleHistoryRow(
    sale: SaleRecord,
    onRevertClick: () -> Unit
) {
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sale.displayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))
                Text(
                    text = "${sale.quantity} u. · ${LiaFormatters.formatDateTime(sale.soldAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = LiaTheme.colors.textMuted
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = LiaFormatters.formatBs(sale.totalAmount),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = onRevertClick) {
                    Icon(
                        imageVector = Icons.Outlined.Undo,
                        contentDescription = "Corregir venta",
                        tint = LiaTheme.colors.textMuted
                    )
                }
            }
        }
    }
}
