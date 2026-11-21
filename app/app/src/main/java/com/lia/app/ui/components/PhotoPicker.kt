package com.lia.app.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lia.app.ui.theme.LiaDimensions
import com.lia.app.ui.theme.LiaTheme

@Composable
fun PhotoPicker(
    photoUri: String?,
    onPhotoSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onPhotoSelected(uri.toString())
        }
    }

    val openPicker = {
        launcher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(LiaTheme.colors.surfaceElevated)
                .border(
                    BorderStroke(1.dp, LiaTheme.colors.borderStrong),
                    MaterialTheme.shapes.medium
                )
                .clickable(onClick = openPicker),
            contentAlignment = Alignment.Center
        ) {
            if (photoUri != null) {
                AsyncImage(
                    model = photoUri,
                    contentDescription = "Foto de la prenda",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddAPhoto,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(LiaDimensions.SpaceXS))
                    Text(
                        text = "Agregar foto (opcional)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Toca para elegir de tu galería",
                        style = MaterialTheme.typography.bodySmall,
                        color = LiaTheme.colors.textMuted
                    )
                }
            }
        }

        if (photoUri != null) {
            Spacer(modifier = Modifier.height(LiaDimensions.SpaceXS))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LiaDimensions.SpaceXS)
            ) {
                AppButton(
                    text = "Cambiar foto",
                    onClick = openPicker,
                    variant = AppButtonVariant.OUTLINE,
                    icon = Icons.Outlined.PhotoLibrary,
                    fullWidth = false,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(LiaDimensions.SpaceXXS))
                AppButton(
                    text = "Quitar",
                    onClick = { onPhotoSelected(null) },
                    variant = AppButtonVariant.TEXT,
                    icon = Icons.Outlined.DeleteOutline,
                    fullWidth = false
                )
            }
        }
    }
}
