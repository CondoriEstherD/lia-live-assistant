package com.lia.app.data.sales

import com.lia.app.data.auth.AuthConfig
import com.lia.app.data.model.Garment
import com.lia.app.data.model.SaleRecord
import com.lia.app.data.model.SalesPeriod
import com.lia.app.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

sealed interface SaleRpcResult {
    data object Success : SaleRpcResult
    data class Error(val message: String) : SaleRpcResult
}

interface SalesRepository {
    fun initialSales(): List<SaleRecord>
    fun filterByPeriod(
        sales: List<SaleRecord>,
        period: SalesPeriod,
        now: LocalDateTime = LocalDateTime.now()
    ): List<SaleRecord>

    suspend fun fetchSales(garmentsById: Map<String, Garment>): List<SaleRecord>
    suspend fun recordManualSale(garmentId: String, quantity: Int): SaleRpcResult
    suspend fun voidSale(saleId: String): SaleRpcResult
}

@Serializable
private data class SaleRowDto(
    val id: String,
    @SerialName("garment_id") val garmentId: String,
    val quantity: Int,
    @SerialName("unit_price") val unitPrice: Double,
    val amount: Double,
    @SerialName("sold_at") val soldAt: String,
    @SerialName("voided_at") val voidedAt: String? = null
)

@Serializable
private data class RecordManualSaleParams(
    @SerialName("p_garment_id") val garmentId: String,
    @SerialName("p_quantity") val quantity: Int
)

@Serializable
private data class VoidSaleParams(
    @SerialName("p_sale_id") val saleId: String
)

class SupabaseSalesRepository(
    private val tokenProvider: suspend () -> String?
) : SalesRepository {

    override fun initialSales(): List<SaleRecord> = emptyList()

    override fun filterByPeriod(
        sales: List<SaleRecord>,
        period: SalesPeriod,
        now: LocalDateTime
    ): List<SaleRecord> {
        val startOfDay = now.toLocalDate().atStartOfDay()
        val threshold = when (period) {
            SalesPeriod.TODAY -> startOfDay
            SalesPeriod.THIS_WEEK -> startOfDay.minusDays(6)
            SalesPeriod.THIS_MONTH -> startOfDay.withDayOfMonth(1)
        }
        return sales
            .filter { !it.soldAt.isBefore(threshold) }
            .sortedByDescending { it.soldAt }
    }

    override suspend fun fetchSales(garmentsById: Map<String, Garment>): List<SaleRecord> {
        if (!AuthConfig.isSupabaseConfigured) return emptyList()
        val client = SupabaseClientProvider.getOrCreate(tokenProvider)
        val rows = client.postgrest["sales"]
            .select {
                filter { exact("voided_at", null) }
                order("sold_at", Order.DESCENDING)
            }
            .decodeList<SaleRowDto>()

        return rows.map { dto ->
            val garment = garmentsById[dto.garmentId]
            val localTime = runCatching {
                OffsetDateTime.parse(dto.soldAt)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime()
            }.getOrElse { LocalDateTime.now() }

            SaleRecord(
                id = dto.id,
                garmentId = dto.garmentId,
                garmentCode = garment?.code ?: "PRENDA",
                garmentName = garment?.name.orEmpty(),
                quantity = dto.quantity,
                unitPriceAtSale = dto.unitPrice,
                totalAmount = dto.amount,
                soldAt = localTime
            )
        }
    }

    override suspend fun recordManualSale(garmentId: String, quantity: Int): SaleRpcResult {
        val client = SupabaseClientProvider.getOrCreate(tokenProvider)
        val params = buildJsonObject {
            put("p_garment_id", garmentId)
            put("p_quantity", quantity)
        }
        val response = client.postgrest
            .rpc("record_manual_sale", params)
            .decodeAs<JsonObject>()
        return when (response["status"]?.jsonPrimitive?.contentOrNull) {
            "sold" -> SaleRpcResult.Success
            "not_enough_available" -> {
                val available = response["available"]?.jsonPrimitive?.intOrNull ?: 0
                SaleRpcResult.Error("Solo hay $available unidad(es) disponible(s) sin reserva activa.")
            }
            "invalid_quantity" -> SaleRpcResult.Error("La cantidad debe ser mayor a 0.")
            "not_found" -> SaleRpcResult.Error("La prenda no está disponible para la venta.")
            else -> SaleRpcResult.Error("No se pudo registrar la venta.")
        }
    }

    override suspend fun voidSale(saleId: String): SaleRpcResult {
        val client = SupabaseClientProvider.getOrCreate(tokenProvider)
        val params = buildJsonObject {
            put("p_sale_id", saleId)
        }
        val response = client.postgrest
            .rpc("void_sale", params)
            .decodeAs<JsonObject>()
        return when (response["status"]?.jsonPrimitive?.contentOrNull) {
            "voided" -> SaleRpcResult.Success
            "already_voided" -> SaleRpcResult.Error("Esta venta ya fue corregida anteriormente.")
            "not_found" -> SaleRpcResult.Error("No se encontró la venta.")
            else -> SaleRpcResult.Error("No se pudo corregir la venta.")
        }
    }
}

class SampleSalesRepository : SalesRepository {
    override fun initialSales(): List<SaleRecord> {
        val now = LocalDateTime.now()
        return listOf(
            SaleRecord(
                id = "s-1",
                garmentId = "g-1",
                garmentCode = "A12",
                garmentName = "Vestido satinado rosa",
                quantity = 1,
                unitPriceAtSale = 150.0,
                totalAmount = 150.0,
                soldAt = now.minusMinutes(25)
            ),
            SaleRecord(
                id = "s-2",
                garmentId = "g-4",
                garmentCode = "A08",
                garmentName = "Conjunto lino verano",
                quantity = 2,
                unitPriceAtSale = 180.0,
                totalAmount = 360.0,
                soldAt = now.minusHours(2)
            )
        )
    }

    override fun filterByPeriod(
        sales: List<SaleRecord>,
        period: SalesPeriod,
        now: LocalDateTime
    ): List<SaleRecord> {
        val startOfDay = now.toLocalDate().atStartOfDay()
        val threshold = when (period) {
            SalesPeriod.TODAY -> startOfDay
            SalesPeriod.THIS_WEEK -> startOfDay.minusDays(6)
            SalesPeriod.THIS_MONTH -> startOfDay.withDayOfMonth(1)
        }
        return sales
            .filter { !it.soldAt.isBefore(threshold) }
            .sortedByDescending { it.soldAt }
    }

    override suspend fun fetchSales(garmentsById: Map<String, Garment>): List<SaleRecord> = initialSales()
    override suspend fun recordManualSale(garmentId: String, quantity: Int): SaleRpcResult = SaleRpcResult.Success
    override suspend fun voidSale(saleId: String): SaleRpcResult = SaleRpcResult.Success
}
