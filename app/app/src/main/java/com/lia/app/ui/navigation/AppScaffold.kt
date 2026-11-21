package com.lia.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lia.app.ui.components.AddGarmentFab
import com.lia.app.ui.components.AppBottomBar
import com.lia.app.ui.components.MainDestination

@Composable
fun AppScaffold(
    currentRoute: String?,
    showMainChrome: Boolean,
    snackbarHostState: SnackbarHostState,
    onSelectDestination: (MainDestination) -> Unit,
    onAddGarmentClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (showMainChrome) {
                AppBottomBar(
                    currentRoute = currentRoute,
                    onSelectDestination = onSelectDestination
                )
            }
        },
        floatingActionButton = {
            if (showMainChrome) {
                AddGarmentFab(onClick = onAddGarmentClick)
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            content()
        }
    }
}
