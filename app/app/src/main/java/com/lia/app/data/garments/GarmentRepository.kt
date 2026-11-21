package com.lia.app.data.garments

import com.lia.app.data.auth.AuthConfig
import com.lia.app.data.model.Garment
import com.lia.app.data.model.GarmentStatus
import com.lia.app.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

sealed interface GarmentRpcResult {
    data object Success : GarmentRpcResult
    data class Error(val message: String) : GarmentRpcResult
}

interface GarmentRepository {
    fun initialGarments(): List<Garment>
    fun isActiveCodeTaken(
        garments: List<Garment>,
        code: String,
        excludingGarmentId: String? = null
    ): Boolean

    suspend fun fetchGarments(): List<Garment>
    suspend fun createGarment(
        sellerId: String,
        code: String,
        sellingPrice: Double,
        stock: Int,
        name: String,
        photoUrl: String?,
        size: String,
        color: String,
        description: String
    ): Garment

    suspend fun updateGarmentDetails(
        garmentId: String,
        code: String,
        sellingPrice: Double,
        name: String,
        photoUrl: String?,
        size: String,
        color: String,
        description: String
    ): Garment

    suspend fun setGarmentStock(garmentId: String, stock: Int): GarmentRpcResult
    suspend fun archiveGarment(garmentId: String): GarmentRpcResult
    suspend fun uploadGarmentPhoto(clerkUserId: String, bytes: ByteArray, extension: String): String
    suspend fun deleteGarmentPhoto(photoUrl: String)
}

@Serializable
private data class GarmentRowDto(
    val id: String,
    @SerialName("seller_id") val sellerId: String,
    val code: String,
    val name: String? = null,
    val description: String? = null,
    val size: String? = null,
    val color: String? = null,
    val price: Double,
    val stock: Int,
    @SerialName("photo_url") val photoUrl: String? = null,
    val status: String
)

/**
 * Matches column-level INSERT grant in 20261121000003_security.sql:
 * grant insert (seller_id, code, name, description, size, color, price, stock, photo_url) on garments
 */
@Serializable
private data class GarmentInsertDto(
    @SerialName("seller_id") val sellerId: String,
    val code: String,
    val name: String?,
    val description: String?,
    val size: String?,
    val color: String?,
    val price: Double,
    val stock: Int,
    @SerialName("photo_url") val photoUrl: String?
)

/**
 * Matches column-level UPDATE grant in 20261121000003_security.sql:
 * grant update (code, name, description, size, color, price, photo_url) on garments
 */
@Serializable
private data class GarmentUpdateDto(
    val code: String,
    val name: String?,
    val description: String?,
    val size: String?,
    val color: String?,
    val price: Double,
    @SerialName("photo_url") val photoUrl: String?
)

@Serializable
private data class SetGarmentStockParams(
    @SerialName("p_garment_id") val garmentId: String,
    @SerialName("p_stock") val stock: Int
)

@Serializable
private data class ArchiveGarmentParams(
    @SerialName("p_garment_id") val garmentId: String
)

class SupabaseGarmentRepository(
    private val tokenProvider: suspend () -> String?
) : GarmentRepository {

    companion object {
        const val PHOTO_BUCKET = "garment-photos"
    }

    override fun initialGarments(): List<Garment> = emptyList()

    /**
     * Business rule: code is unique per seller only among IN_CATALOG garments.
     * Once a garment is SOLD or ARCHIVED, the seller may reuse the code.
     */
    override fun isActiveCodeTaken(
        garments: List<Garment>,
        code: String,
        excludingGarmentId: String?
    ): Boolean {
        val normalized = code.trim().lowercase()
        if (normalized.isEmpty()) return false
        return garments.any { garment ->
            garment.id != excludingGarmentId &&
                garment.status == GarmentStatus.IN_CATALOG &&
                garment.code.trim().lowercase() == normalized
        }
    }

    override suspend fun fetchGarments(): List<Garment> {
        if (!AuthConfig.isSupabaseConfigured) return emptyList()
        val client = SupabaseClientProvider.getOrCreate(tokenProvider)
        val rows = client.postgrest["garments"]
            .select {
                order("created_at", Order.DESCENDING)
            }
            .decodeList<GarmentRowDto>()
        return rows.map { it.toDomain() }
    }

    override suspend fun createGarment(
        sellerId: String,
        code: String,
        sellingPrice: Double,
        stock: Int,
        name: String,
        photoUrl: String?,
        size: String,
        color: String,
        description: String
    ): Garment {
        val client = SupabaseClientProvider.getOrCreate(tokenProvider)
        val payload = GarmentInsertDto(
            sellerId = sellerId,
            code = code.trim().uppercase(),
            name = name.trim().ifBlank { null },
            description = description.trim().ifBlank { null },
            size = size.trim().ifBlank { null },
            color = color.trim().ifBlank { null },
            price = sellingPrice,
            stock = stock.coerceAtLeast(1),
            photoUrl = photoUrl?.trim()?.ifBlank { null }
        )
        val inserted = client.postgrest["garments"]
            .insert(payload) { select() }
            .decodeSingle<GarmentRowDto>()
        return inserted.toDomain()
    }

    override suspend fun updateGarmentDetails(
        garmentId: String,
        code: String,
        sellingPrice: Double,
        name: String,
        photoUrl: String?,
        size: String,
        color: String,
        description: String
    ): Garment {
        val client = SupabaseClientProvider.getOrCreate(tokenProvider)
        val payload = GarmentUpdateDto(
            code = code.trim().uppercase(),
            name = name.trim().ifBlank { null },
            description = description.trim().ifBlank { null },
            size = size.trim().ifBlank { null },
            color = color.trim().ifBlank { null },
            price = sellingPrice,
            photoUrl = photoUrl?.trim()?.ifBlank { null }
        )
        val updated = client.postgrest["garments"]
            .update(payload) {
                filter { eq("id", garmentId) }
                select()
            }
            .decodeSingle<GarmentRowDto>()
        return updated.toDomain()
    }

    override suspend fun setGarmentStock(garmentId: String, stock: Int): GarmentRpcResult {
        val client = SupabaseClientProvider.getOrCreate(tokenProvider)
        val params = buildJsonObject {
            put("p_garment_id", garmentId)
            put("p_stock", stock)
        }
        val response = client.postgrest
            .rpc("set_garment_stock", params)
            .decodeAs<JsonObject>()
        return when (response["status"]?.jsonPrimitive?.contentOrNull) {
            "updated" -> GarmentRpcResult.Success
            "below_reserved" -> {
                val reserved = response["reserved"]?.jsonPrimitive?.intOrNull ?: 1
                GarmentRpcResult.Error("Hay $reserved unidad(es) reservada(s) en curso. No puedes bajar el stock por debajo de $reserved.")
            }
            "invalid_stock" -> GarmentRpcResult.Error("El stock ingresado no es válido.")
            "not_found" -> GarmentRpcResult.Error("No se encontró la prenda o ya está archivada.")
            else -> GarmentRpcResult.Error("No se pudo actualizar el stock.")
        }
    }

    override suspend fun archiveGarment(garmentId: String): GarmentRpcResult {
        val client = SupabaseClientProvider.getOrCreate(tokenProvider)
        val params = buildJsonObject {
            put("p_garment_id", garmentId)
        }
        val response = client.postgrest
            .rpc("archive_garment", params)
            .decodeAs<JsonObject>()
        return when (response["status"]?.jsonPrimitive?.contentOrNull) {
            "archived" -> GarmentRpcResult.Success
            "has_active_reservation" -> GarmentRpcResult.Error("No puedes archivar esta prenda mientras una clienta tiene una reserva activa.")
            "not_found" -> GarmentRpcResult.Error("No se encontró la prenda o ya está archivada.")
            else -> GarmentRpcResult.Error("No se pudo archivar la prenda.")
        }
    }

    override suspend fun uploadGarmentPhoto(
        clerkUserId: String,
        bytes: ByteArray,
        extension: String
    ): String {
        val client = SupabaseClientProvider.getOrCreate(tokenProvider)
        val cleanExt = extension.lowercase().replace(".", "").ifBlank { "jpg" }
        val path = "$clerkUserId/${UUID.randomUUID()}.$cleanExt"
        val bucket = client.storage.from(PHOTO_BUCKET)
        bucket.upload(path = path, data = bytes) {
            upsert = true
        }
        return bucket.publicUrl(path)
    }

    override suspend fun deleteGarmentPhoto(photoUrl: String) {
        val marker = "/object/public/$PHOTO_BUCKET/"
        val index = photoUrl.indexOf(marker)
        if (index == -1) return
        val path = photoUrl.substring(index + marker.length)
        if (path.isBlank()) return
        runCatching {
            val client = SupabaseClientProvider.getOrCreate(tokenProvider)
            client.storage.from(PHOTO_BUCKET).delete(path)
        }
    }

    private fun GarmentRowDto.toDomain(): Garment = Garment(
        id = id,
        code = code,
        sellingPrice = price,
        stock = stock,
        name = name.orEmpty(),
        photoUri = photoUrl,
        size = size.orEmpty(),
        color = color.orEmpty(),
        description = description.orEmpty(),
        status = when (status.lowercase()) {
            "sold" -> GarmentStatus.SOLD
            "archived" -> GarmentStatus.ARCHIVED
            else -> GarmentStatus.IN_CATALOG
        }
    )
}

class SampleGarmentRepository : GarmentRepository {
    override fun initialGarments(): List<Garment> = listOf(
        Garment(
            id = "g-1",
            code = "A12",
            sellingPrice = 150.0,
            stock = 3,
            name = "Vestido satinado rosa",
            size = "M",
            color = "Rosa pastel",
            description = "Ideal para noche, tela liviana.",
            status = GarmentStatus.IN_CATALOG
        ),
        Garment(
            id = "g-2",
            code = "B04",
            sellingPrice = 95.0,
            stock = 1,
            name = "Blusa manga globo",
            size = "S",
            color = "Blanco",
            status = GarmentStatus.IN_CATALOG
        ),
        Garment(
            id = "g-3",
            code = "C19",
            sellingPrice = 210.0,
            stock = 2,
            name = "",
            size = "L",
            color = "Negro",
            status = GarmentStatus.IN_CATALOG
        ),
        Garment(
            id = "g-4",
            code = "A08",
            sellingPrice = 180.0,
            stock = 0,
            name = "Conjunto lino verano",
            size = "M",
            color = "Beige",
            status = GarmentStatus.SOLD
        ),
        Garment(
            id = "g-5",
            code = "D02",
            sellingPrice = 120.0,
            stock = 1,
            name = "Falda plisada",
            size = "S",
            color = "Bordó",
            status = GarmentStatus.ARCHIVED
        )
    )

    override fun isActiveCodeTaken(
        garments: List<Garment>,
        code: String,
        excludingGarmentId: String?
    ): Boolean {
        val normalized = code.trim().lowercase()
        if (normalized.isEmpty()) return false
        return garments.any { garment ->
            garment.id != excludingGarmentId &&
                garment.status == GarmentStatus.IN_CATALOG &&
                garment.code.trim().lowercase() == normalized
        }
    }

    override suspend fun fetchGarments(): List<Garment> = initialGarments()

    override suspend fun createGarment(
        sellerId: String,
        code: String,
        sellingPrice: Double,
        stock: Int,
        name: String,
        photoUrl: String?,
        size: String,
        color: String,
        description: String
    ): Garment = Garment(
        id = "g-${UUID.randomUUID().toString().take(8)}",
        code = code.trim().uppercase(),
        sellingPrice = sellingPrice,
        stock = stock.coerceAtLeast(1),
        name = name.trim(),
        photoUri = photoUrl,
        size = size.trim(),
        color = color.trim(),
        description = description.trim(),
        status = GarmentStatus.IN_CATALOG
    )

    override suspend fun updateGarmentDetails(
        garmentId: String,
        code: String,
        sellingPrice: Double,
        name: String,
        photoUrl: String?,
        size: String,
        color: String,
        description: String
    ): Garment = Garment(
        id = garmentId,
        code = code.trim().uppercase(),
        sellingPrice = sellingPrice,
        stock = 1,
        name = name.trim(),
        photoUri = photoUrl,
        size = size.trim(),
        color = color.trim(),
        description = description.trim(),
        status = GarmentStatus.IN_CATALOG
    )

    override suspend fun setGarmentStock(garmentId: String, stock: Int): GarmentRpcResult =
        GarmentRpcResult.Success

    override suspend fun archiveGarment(garmentId: String): GarmentRpcResult =
        GarmentRpcResult.Success

    override suspend fun uploadGarmentPhoto(clerkUserId: String, bytes: ByteArray, extension: String): String = ""
    override suspend fun deleteGarmentPhoto(photoUrl: String) = Unit
}
