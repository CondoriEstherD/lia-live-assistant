package com.lia.app.data.model

import java.time.LocalDateTime

enum class GarmentStatus(val label: String) {
    IN_CATALOG("En catálogo"),
    SOLD("Vendida"),
    ARCHIVED("Archivada")
}

enum class SalesPeriod(val label: String) {
    TODAY("Hoy"),
    THIS_WEEK("Esta semana"),
    THIS_MONTH("Este mes")
}

enum class ThemeMode(val label: String) {
    LIGHT("Claro"),
    DARK("Oscuro")
}

data class SellerProfile(
    val id: String,
    val whatsapp: String,
    val name: String = "",
    val username: String = "",
    val socialLinks: String = ""
)

data class Garment(
    val id: String,
    val code: String,
    val sellingPrice: Double,
    val stock: Int = 1,
    val name: String = "",
    val photoUri: String? = null,
    val size: String = "",
    val color: String = "",
    val description: String = "",
    val status: GarmentStatus = GarmentStatus.IN_CATALOG
) {
    val displayTitle: String
        get() = name.ifBlank { code }
}

data class SaleRecord(
    val id: String,
    val garmentId: String,
    val garmentCode: String,
    val garmentName: String,
    val quantity: Int,
    val unitPriceAtSale: Double,
    val totalAmount: Double,
    val soldAt: LocalDateTime
) {
    val displayLabel: String
        get() = if (garmentName.isNotBlank()) "$garmentCode · $garmentName" else garmentCode
}

data class AppNotification(
    val id: String,
    val title: String,
    val body: String,
    val timestampLabel: String,
    val isUnread: Boolean = false
)

sealed interface UiLoadState {
    data object Ready : UiLoadState
    data object Loading : UiLoadState
    data class Error(val message: String) : UiLoadState
}
