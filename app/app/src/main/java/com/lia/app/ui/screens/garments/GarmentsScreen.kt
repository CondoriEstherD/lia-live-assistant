package com.lia.app.ui.screens.garments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lia.app.data.model.Garment
import com.lia.app.data.model.GarmentStatus
import com.lia.app.data.model.UiLoadState
import com.lia.app.data.sample.LiaFormatters
import com.lia.app.ui.components.AppTextField
import com.lia.app.ui.components.ConfirmationDialog
import com.lia.app.ui.components.EmptyStateView
import com.lia.app.ui.components.ErrorStateView
import com.lia.app.ui.components.GarmentCard
import com.lia.app.ui.components.LoadingStateView
import com.lia.app.ui.theme.LiaDimensions
import com.lia.app.ui.theme.LiaTheme

@Composable
fun GarmentsScreen(
    garments: List<Garment>,
    selectedStatus: GarmentStatus,
    searchQuery: String,
    loadState: UiLoadState,
    onSearchQueryChange: (String) -> Unit,
    onSelectStatus: (GarmentStatus) -> Unit,
    onEditGarment: (String) -> Unit,
    onRecordSale: (garmentId: String, quantity: Int) -> Unit,
    onArchiveGarment: (String) -> Unit,
    onRetry: () -> Unit
) {
    var garmentToSell by remember { mutableStateOf<Garment?>(null) }
    var garmentToArchive by remember { mutableStateOf<Garment?>(null) }

    garmentToSell?.let { garment ->
        RecordSaleDialog(
            garment = garment,
            onConfirm = { qty ->
                onRecordSale(garment.id, qty)
                garmentToSell = null
            },
            onDismiss = { garmentToSell = null }
        )
    }

    garmentToArchive?.let { garment ->
        ConfirmationDialog(
            title = "Archivar prenda",
            message = "¿Deseas archivar ${garment.code}? Se ocultará del catálogo sin borrar su historial de ventas y podrás reutilizar el código.",
            confirmText = "Archivar",
            onConfirm = {
                onArchiveGarment(garment.id)
                garmentToArchive = null
            },
            onDismiss = { garmentToArchive = null }
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
        verticalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceSM)
    ) {
        item {
            Column {
                Text(
                    text = "Prendas",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(LiaDimensions.SpaceXXS))
                Text(
                    text = "Toca una prenda para editar o registra una venta rápida",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LiaTheme.colors.textSecondary
                )
            }
        }

        item {
            AppTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = "Buscar por código o nombre",
                placeholder = "Ej. A12 o Vestido",
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = LiaTheme.colors.textMuted
                    )
                },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Limpiar búsqueda"
                            )
                        }
                    }
                } else {
                    null
                }
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceXS)
            ) {
                GarmentStatus.entries.forEach { status ->
                    val selected = status == selectedStatus
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectStatus(status) },
                        label = {
                            Text(
                                text = status.label,
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
                item { LoadingStateView(label = "Cargando inventario...") }
            }

            is UiLoadState.Error -> {
                item { ErrorStateView(message = loadState.message, onRetry = onRetry) }
            }

            UiLoadState.Ready -> {
                if (garments.isEmpty()) {
                    item {
                        EmptyStateView(
                            title = if (searchQuery.isNotBlank()) {
                                "Sin resultados para \"$searchQuery\""
                            } else {
                                "No hay prendas en ${selectedStatus.label}"
                            },
                            description = if (selectedStatus == GarmentStatus.IN_CATALOG && searchQuery.isBlank()) {
                                "Usa el botón + de abajo a la derecha para agregar tu primera prenda."
                            } else {
                                "Prueba con otro filtro o búsqueda."
                            },
                            icon = Icons.Outlined.Checkroom
                        )
                    }
                } else {
                    items(garments, key = { it.id }) { garment ->
                        GarmentCard(
                            garment = garment,
                            onEditClick = { onEditGarment(garment.id) },
                            onSellClick = { garmentToSell = garment },
                            onArchiveClick = { garmentToArchive = garment }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordSaleDialog(
    garment: Garment,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var quantity by remember(garment.id) { mutableIntStateOf(1) }
    val total = garment.sellingPrice * quantity

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LiaTheme.colors.surfaceElevated,
        title = {
            Text(
                text = "Registrar venta · ${garment.code}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceSM)) {
                if (garment.name.isNotBlank()) {
                    Text(
                        text = garment.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LiaTheme.colors.textSecondary
                    )
                }
                Text(
                    text = "Disponible: ${garment.stock} · Precio: ${LiaFormatters.formatBs(garment.sellingPrice)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = LiaTheme.colors.textMuted
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Cantidad",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { if (quantity > 1) quantity-- },
                            enabled = quantity > 1
                        ) {
                            Icon(Icons.Rounded.Remove, contentDescription = "Restar")
                        }
                        Text(
                            text = quantity.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(
                            onClick = { if (quantity < garment.stock) quantity++ },
                            enabled = quantity < garment.stock
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Sumar")
                        }
                    }
                }

                Text(
                    text = "Total: ${LiaFormatters.formatBs(total)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(quantity) }) {
                Text(
                    text = "Confirmar venta",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancelar",
                    style = MaterialTheme.typography.labelLarge,
                    color = LiaTheme.colors.textMuted
                )
            }
        }
    )
}
