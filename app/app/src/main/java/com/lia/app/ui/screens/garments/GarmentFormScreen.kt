package com.lia.app.ui.screens.garments

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.lia.app.data.model.Garment
import com.lia.app.data.sample.PhotoUploadPayload
import com.lia.app.ui.components.AppButton
import com.lia.app.ui.components.AppButtonVariant
import com.lia.app.ui.components.AppCard
import com.lia.app.ui.components.AppTextField
import com.lia.app.ui.components.PhotoPicker
import com.lia.app.ui.theme.LiaDimensions

@Composable
fun GarmentFormScreen(
    existingGarment: Garment?,
    isSaving: Boolean,
    onCancel: () -> Unit,
    onSave: (
        existingId: String?,
        code: String,
        sellingPrice: Double,
        stock: Int,
        name: String,
        existingRemotePhotoUrl: String?,
        newPhotoUpload: PhotoUploadPayload?,
        size: String,
        color: String,
        description: String,
        onResult: (errorMessage: String?) -> Unit
    ) -> Unit
) {
    val context = LocalContext.current
    val isEditing = existingGarment != null

    var code by rememberSaveable(existingGarment?.id) { mutableStateOf(existingGarment?.code.orEmpty()) }
    var priceText by rememberSaveable(existingGarment?.id) {
        mutableStateOf(
            existingGarment?.sellingPrice?.let {
                if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
            }.orEmpty()
        )
    }
    var stockText by rememberSaveable(existingGarment?.id) {
        mutableStateOf((existingGarment?.stock ?: 1).toString())
    }
    var name by rememberSaveable(existingGarment?.id) { mutableStateOf(existingGarment?.name.orEmpty()) }
    var photoUri by rememberSaveable(existingGarment?.id) { mutableStateOf(existingGarment?.photoUri) }
    var size by rememberSaveable(existingGarment?.id) { mutableStateOf(existingGarment?.size.orEmpty()) }
    var color by rememberSaveable(existingGarment?.id) { mutableStateOf(existingGarment?.color.orEmpty()) }
    var description by rememberSaveable(existingGarment?.id) { mutableStateOf(existingGarment?.description.orEmpty()) }

    var codeError by rememberSaveable { mutableStateOf<String?>(null) }
    var priceError by rememberSaveable { mutableStateOf<String?>(null) }
    var stockError by rememberSaveable { mutableStateOf<String?>(null) }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LiaDimensions.ScreenHorizontalPadding, vertical = LiaDimensions.SpaceMD)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel, enabled = !isSaving) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Cancelar",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = if (isEditing) "Editar prenda" else "Nueva prenda",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        if (formError != null) {
            Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))
            AppCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text = formError!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))

        PhotoPicker(
            photoUri = photoUri,
            onPhotoSelected = {
                photoUri = it
                formError = null
            }
        )

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceMD))

        AppTextField(
            value = code,
            onValueChange = {
                code = it
                codeError = null
                formError = null
            },
            label = "Código *",
            placeholder = "Ej. A12",
            supportingText = "Único mientras esté en catálogo.",
            errorText = codeError,
            enabled = !isSaving,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
        )

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceSM)
        ) {
            AppTextField(
                value = priceText,
                onValueChange = {
                    priceText = it
                    priceError = null
                    formError = null
                },
                label = "Precio (Bs) *",
                placeholder = "150",
                errorText = priceError,
                enabled = !isSaving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1.4f)
            )

            AppTextField(
                value = stockText,
                onValueChange = {
                    stockText = it.filter { ch -> ch.isDigit() }
                    stockError = null
                    formError = null
                },
                label = "Stock",
                placeholder = "1",
                errorText = stockError,
                enabled = !isSaving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

        AppTextField(
            value = name,
            onValueChange = { name = it },
            label = "Nombre (opcional)",
            placeholder = "Ej. Vestido satinado rosa",
            enabled = !isSaving
        )

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceSM)
        ) {
            AppTextField(
                value = size,
                onValueChange = { size = it },
                label = "Talla (opcional)",
                placeholder = "S, M, L",
                enabled = !isSaving,
                modifier = Modifier.weight(1f)
            )
            AppTextField(
                value = color,
                onValueChange = { color = it },
                label = "Color (opcional)",
                placeholder = "Rosa",
                enabled = !isSaving,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceSM))

        AppTextField(
            value = description,
            onValueChange = { description = it },
            label = "Descripción (opcional)",
            placeholder = "Detalles de tela, corte o medidas",
            singleLine = false,
            minLines = 3,
            enabled = !isSaving
        )

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceLG))

        AppButton(
            text = if (isEditing) "Guardar cambios" else "Guardar prenda",
            loading = isSaving,
            onClick = {
                var valid = true
                formError = null

                if (code.isBlank()) {
                    codeError = "El código es obligatorio."
                    valid = false
                }
                val parsedPrice = priceText.replace(",", ".").toDoubleOrNull()
                if (parsedPrice == null || parsedPrice <= 0.0) {
                    priceError = "Ingresa un precio mayor a 0."
                    valid = false
                }
                val parsedStock = if (stockText.isBlank()) 1 else stockText.toIntOrNull()
                val minStock = if (isEditing) 0 else 1
                if (parsedStock == null || parsedStock < minStock) {
                    stockError = if (isEditing) "Stock inválido." else "Mínimo 1."
                    valid = false
                }

                if (valid && parsedPrice != null && parsedStock != null) {
                    val currentPhoto = photoUri
                    val isLocalSelection = currentPhoto != null &&
                        !currentPhoto.startsWith("http://") &&
                        !currentPhoto.startsWith("https://")

                    val uploadPayload = if (isLocalSelection) {
                        val parsedUri = Uri.parse(currentPhoto)
                        val bytes = runCatching {
                            context.contentResolver.openInputStream(parsedUri)?.use { it.readBytes() }
                        }.getOrNull()
                        if (bytes == null) {
                            formError = "No se pudo leer la imagen seleccionada."
                            return@AppButton
                        }
                        val mimeType = context.contentResolver.getType(parsedUri)
                        val ext = MimeTypeMap.getSingleton()
                            .getExtensionFromMimeType(mimeType)
                            ?.ifBlank { "jpg" }
                            ?: "jpg"
                        PhotoUploadPayload(bytes = bytes, extension = ext)
                    } else {
                        null
                    }

                    val remotePhotoUrl = if (isLocalSelection) null else currentPhoto

                    onSave(
                        existingGarment?.id,
                        code,
                        parsedPrice,
                        parsedStock,
                        name,
                        remotePhotoUrl,
                        uploadPayload,
                        size,
                        color,
                        description
                    ) { error ->
                        if (error != null) {
                            formError = error
                        } else {
                            onCancel()
                        }
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceXS))

        AppButton(
            text = "Cancelar",
            onClick = onCancel,
            variant = AppButtonVariant.TEXT,
            enabled = !isSaving
        )

        Spacer(modifier = Modifier.height(LiaDimensions.SpaceLG))
    }
}
