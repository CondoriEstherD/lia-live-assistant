package com.lia.app.data.notifications

import com.lia.app.data.auth.AuthConfig
import com.lia.app.data.model.AppNotification
import com.lia.app.data.sample.LiaFormatters
import com.lia.app.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

interface NotificationRepository {
    fun sampleNotifications(): List<AppNotification>
    suspend fun fetchSellerNotifications(): List<AppNotification>
    suspend fun markAsRead(notificationId: String)
}

@Serializable
private data class NotificationRowDto(
    val id: String,
    val kind: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String? = null
)

/**
 * Matches column-level UPDATE grant in 20261121000003_security.sql:
 * grant update (read_at) on notifications to authenticated;
 */
@Serializable
private data class NotificationMarkReadDto(
    @SerialName("read_at") val readAt: String
)

class SupabaseNotificationRepository(
    private val tokenProvider: suspend () -> String?
) : NotificationRepository {

    override fun sampleNotifications(): List<AppNotification> = emptyList()

    override suspend fun fetchSellerNotifications(): List<AppNotification> {
        if (!AuthConfig.isSupabaseConfigured) return emptyList()
        val client = SupabaseClientProvider.getOrCreate(tokenProvider)
        val rows = client.postgrest["notifications"]
            .select {
                order("created_at", Order.DESCENDING)
                limit(30)
            }
            .decodeList<NotificationRowDto>()

        return rows.map { dto ->
            val garmentObj = runCatching { dto.payload["garment"]?.jsonObject }.getOrNull()
            val code = garmentObj?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
            val amount = dto.payload["amount"]?.jsonPrimitive?.doubleOrNull
            val customerName = dto.payload["customer_name"]?.jsonPrimitive?.contentOrNull

            val (title, body) = when (dto.kind) {
                "garment_sold" -> {
                    val t = if (code.isNotBlank()) "Prenda vendida · $code" else "Prenda vendida"
                    val buyer = customerName?.takeIf { it.isNotBlank() }?.let { " a $it" }.orEmpty()
                    val amt = amount?.let { " por ${LiaFormatters.formatBs(it)}" }.orEmpty()
                    t to "Se confirmó el pago$buyer$amt."
                }
                "late_payment" -> {
                    val t = if (code.isNotBlank()) "Pago tardío · $code" else "Pago tardío recibido"
                    val amt = amount?.let { " (${LiaFormatters.formatBs(it)})" }.orEmpty()
                    t to "Llegó un pago fuera de plazo$amt. Revísalo."
                }
                else -> {
                    dto.kind.replace("_", " ").replaceFirstChar { it.uppercase() } to
                        (if (code.isNotBlank()) "Actualización sobre la prenda $code." else "Nueva notificación de Lia.")
                }
            }

            val timeLabel = runCatching {
                val local = OffsetDateTime.parse(dto.createdAt)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime()
                LiaFormatters.formatDateTime(local)
            }.getOrDefault("")

            AppNotification(
                id = dto.id,
                title = title,
                body = body,
                timestampLabel = timeLabel,
                isUnread = dto.readAt == null
            )
        }
    }

    override suspend fun markAsRead(notificationId: String) {
        if (!AuthConfig.isSupabaseConfigured) return
        val client = SupabaseClientProvider.getOrCreate(tokenProvider)
        client.postgrest["notifications"]
            .update(NotificationMarkReadDto(readAt = Instant.now().toString())) {
                filter { eq("id", notificationId) }
            }
    }
}

class SampleNotificationRepository : NotificationRepository {
    override fun sampleNotifications(): List<AppNotification> = emptyList()
    override suspend fun fetchSellerNotifications(): List<AppNotification> = emptyList()
    override suspend fun markAsRead(notificationId: String) = Unit
}
