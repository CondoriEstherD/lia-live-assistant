package com.lia.app.data.profile

import com.lia.app.data.auth.AuthConfig
import com.lia.app.data.model.SellerProfile
import com.lia.app.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

interface ProfileRepository {
    fun emptyProfile(): SellerProfile
    fun sampleProfile(): SellerProfile
    suspend fun fetchCurrentSeller(clerkUserId: String): SellerProfile?
    suspend fun createSeller(
        clerkUserId: String,
        whatsapp: String,
        name: String,
        username: String,
        socialLinks: String
    ): SellerProfile
    suspend fun updateSeller(
        sellerId: String,
        whatsapp: String,
        name: String,
        username: String,
        socialLinks: String
    ): SellerProfile
}

@Serializable
private data class SellerRowDto(
    val id: String,
    @SerialName("clerk_user_id") val clerkUserId: String? = null,
    @SerialName("whatsapp_number") val whatsappNumber: String,
    val name: String? = null,
    val username: String? = null,
    @SerialName("social_links") val socialLinks: JsonObject = JsonObject(emptyMap())
)

/**
 * Matches column-level INSERT grant in 20261121000003_security.sql:
 * grant insert (clerk_user_id, whatsapp_number, name, username, social_links, reservation_minutes) on sellers
 */
@Serializable
private data class SellerInsertDto(
    @SerialName("clerk_user_id") val clerkUserId: String,
    @SerialName("whatsapp_number") val whatsappNumber: String,
    val name: String?,
    val username: String?,
    @SerialName("social_links") val socialLinks: JsonObject
)

/**
 * Matches column-level UPDATE grant in 20261121000003_security.sql:
 * grant update (whatsapp_number, name, username, social_links, reservation_minutes) on sellers
 */
@Serializable
private data class SellerUpdateDto(
    @SerialName("whatsapp_number") val whatsappNumber: String,
    val name: String?,
    val username: String?,
    @SerialName("social_links") val socialLinks: JsonObject
)

class SupabaseProfileRepository(
    private val tokenProvider: suspend () -> String?
) : ProfileRepository {

    override fun emptyProfile(): SellerProfile = SellerProfile(
        id = "",
        whatsapp = "",
        name = "",
        username = "",
        socialLinks = ""
    )

    override fun sampleProfile(): SellerProfile = SellerProfile(
        id = "seller-demo-1",
        whatsapp = "+591 70012345",
        name = "Camila Moda Live",
        username = "camilalive",
        socialLinks = "tiktok.com/@camilalive"
    )

    override suspend fun fetchCurrentSeller(clerkUserId: String): SellerProfile? {
        if (!AuthConfig.isSupabaseConfigured) return null
        val client = SupabaseClientProvider.getOrCreate(tokenProvider)
        val rows = client.postgrest["sellers"]
            .select {
                filter { eq("clerk_user_id", clerkUserId) }
                limit(1)
            }
            .decodeList<SellerRowDto>()
        return rows.firstOrNull()?.toDomain()
    }

    override suspend fun createSeller(
        clerkUserId: String,
        whatsapp: String,
        name: String,
        username: String,
        socialLinks: String
    ): SellerProfile {
        val client = SupabaseClientProvider.getOrCreate(tokenProvider)
        val payload = SellerInsertDto(
            clerkUserId = clerkUserId,
            whatsappNumber = whatsapp.trim(),
            name = name.trim().ifBlank { null },
            username = username.trim().ifBlank { null },
            socialLinks = encodeSocialLinks(socialLinks)
        )
        val inserted = client.postgrest["sellers"]
            .insert(payload) { select() }
            .decodeSingle<SellerRowDto>()
        return inserted.toDomain()
    }

    override suspend fun updateSeller(
        sellerId: String,
        whatsapp: String,
        name: String,
        username: String,
        socialLinks: String
    ): SellerProfile {
        val client = SupabaseClientProvider.getOrCreate(tokenProvider)
        val payload = SellerUpdateDto(
            whatsappNumber = whatsapp.trim(),
            name = name.trim().ifBlank { null },
            username = username.trim().ifBlank { null },
            socialLinks = encodeSocialLinks(socialLinks)
        )
        val updated = client.postgrest["sellers"]
            .update(payload) {
                filter { eq("id", sellerId) }
                select()
            }
            .decodeSingle<SellerRowDto>()
        return updated.toDomain()
    }

    private fun SellerRowDto.toDomain(): SellerProfile = SellerProfile(
        id = id,
        whatsapp = whatsappNumber,
        name = name.orEmpty(),
        username = username.orEmpty(),
        socialLinks = decodeSocialLinks(socialLinks)
    )

    private fun encodeSocialLinks(raw: String): JsonObject {
        val trimmed = raw.trim()
        return if (trimmed.isEmpty()) {
            JsonObject(emptyMap())
        } else {
            buildJsonObject { put("links", JsonPrimitive(trimmed)) }
        }
    }

    private fun decodeSocialLinks(obj: JsonObject): String {
        val direct = obj["links"]?.jsonPrimitive?.contentOrNull
        if (!direct.isNullOrBlank()) return direct
        return obj.values
            .mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }
}
