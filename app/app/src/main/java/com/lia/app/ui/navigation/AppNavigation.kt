package com.lia.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.clerk.api.Clerk
import com.clerk.ui.auth.AuthMode
import com.clerk.ui.auth.AuthView
import com.lia.app.data.sample.LiaAppViewModel
import com.lia.app.ui.components.LoadingStateView
import com.lia.app.ui.components.MainDestination
import com.lia.app.ui.screens.auth.CompleteProfileScreen
import com.lia.app.ui.screens.auth.WelcomeScreen
import com.lia.app.ui.screens.data.SellerDataScreen
import com.lia.app.ui.screens.garments.GarmentFormScreen
import com.lia.app.ui.screens.garments.GarmentsScreen
import com.lia.app.ui.screens.home.HomeScreen
import com.lia.app.ui.screens.notifications.NotificationsScreen

object AppRoutes {
    const val HOME = "home"
    const val GARMENTS = "garments"
    const val NOTIFICATIONS = "notifications"
    const val DATA = "datos"

    const val GARMENT_CREATE = "garment_form"
    const val GARMENT_EDIT = "garment_form/{garmentId}"

    fun garmentEdit(garmentId: String): String = "garment_form/$garmentId"
}

private val mainTabRoutes = setOf(
    AppRoutes.HOME,
    AppRoutes.GARMENTS,
    AppRoutes.NOTIFICATIONS,
    AppRoutes.DATA
)

@Composable
fun AppNavigation(
    viewModel: LiaAppViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clerkInitialized by Clerk.isInitialized.collectAsStateWithLifecycle()
    val clerkUser by Clerk.userFlow.collectAsStateWithLifecycle()

    LaunchedEffect(clerkInitialized, clerkUser?.id) {
        if (clerkInitialized) {
            val user = clerkUser
            val suggestedName = listOfNotNull(user?.firstName, user?.lastName)
                .joinToString(" ")
                .trim()
            val suggestedUsername = user?.username.orEmpty()
            viewModel.onClerkUserChanged(
                clerkUserId = user?.id,
                suggestedName = suggestedName,
                suggestedUsername = suggestedUsername
            )
        }
    }

    if (!clerkInitialized || (clerkUser != null && uiState.isCheckingSession)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            LoadingStateView(label = "Verificando sesión...")
        }
        return
    }

    if (clerkUser == null) {
        var activeAuthModeName by rememberSaveable { mutableStateOf<String?>(null) }
        val activeAuthMode = activeAuthModeName?.let { runCatching { AuthMode.valueOf(it) }.getOrNull() }

        if (activeAuthMode == null) {
            WelcomeScreen(
                onSignInClick = { activeAuthModeName = AuthMode.SignIn.name },
                onCreateAccountClick = { activeAuthModeName = AuthMode.SignUp.name }
            )
        } else {
            BackHandler {
                activeAuthModeName = null
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .safeDrawingPadding()
            ) {
                AuthView(
                    mode = activeAuthMode,
                    isDismissible = true,
                    onAuthComplete = { activeAuthModeName = null }
                )
            }
        }
        return
    }

    if (uiState.needsProfileCompletion) {
        CompleteProfileScreen(
            initialName = uiState.clerkSuggestedName,
            initialUsername = uiState.clerkSuggestedUsername,
            isSaving = uiState.isSavingProfile,
            errorMessage = uiState.authError,
            onCompleteProfile = viewModel::completeSellerProfile,
            onSignOutClick = viewModel::signOut
        )
        return
    }

    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showMainChrome = currentRoute in mainTabRoutes

    LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearSnackbar()
        }
    }

    AppScaffold(
        currentRoute = currentRoute,
        showMainChrome = showMainChrome,
        snackbarHostState = snackbarHostState,
        onSelectDestination = { destination ->
            navController.navigate(destination.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        },
        onAddGarmentClick = {
            navController.navigate(AppRoutes.GARMENT_CREATE)
        }
    ) {
        NavHost(
            navController = navController,
            startDestination = AppRoutes.HOME
        ) {
            composable(MainDestination.HOME.route) {
                HomeScreen(
                    sellerName = uiState.profile.name,
                    selectedPeriod = uiState.selectedPeriod,
                    sales = viewModel.filteredSales(),
                    loadState = uiState.loadState,
                    onSelectPeriod = viewModel::selectPeriod,
                    onRevertSale = viewModel::revertSale,
                    onRetry = viewModel::retryLoad
                )
            }

            composable(MainDestination.GARMENTS.route) {
                GarmentsScreen(
                    garments = viewModel.filteredGarments(),
                    selectedStatus = uiState.selectedStatusFilter,
                    searchQuery = uiState.searchQuery,
                    loadState = uiState.loadState,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onSelectStatus = viewModel::selectStatusFilter,
                    onEditGarment = { garmentId ->
                        navController.navigate(AppRoutes.garmentEdit(garmentId))
                    },
                    onRecordSale = { garmentId, quantity ->
                        viewModel.recordSale(garmentId, quantity)
                    },
                    onArchiveGarment = viewModel::archiveGarment,
                    onRetry = viewModel::retryLoad
                )
            }

            composable(MainDestination.NOTIFICATIONS.route) {
                NotificationsScreen(
                    notificationsEnabled = uiState.notificationsEnabled,
                    notifications = uiState.notifications,
                    loadState = uiState.loadState,
                    onToggleNotifications = viewModel::setNotificationsEnabled,
                    onMarkNotificationRead = viewModel::markNotificationRead,
                    onMarkAllNotificationsRead = viewModel::markAllNotificationsRead,
                    onRetry = viewModel::retryLoad
                )
            }

            composable(MainDestination.DATA.route) {
                SellerDataScreen(
                    profile = uiState.profile,
                    isSaving = uiState.isSavingProfile,
                    themeMode = uiState.themeMode,
                    onUpdateData = viewModel::updateProfile,
                    onSelectTheme = viewModel::setThemeMode,
                    onSignOut = viewModel::signOut
                )
            }

            composable(AppRoutes.GARMENT_CREATE) {
                GarmentFormScreen(
                    existingGarment = null,
                    isSaving = uiState.isSavingGarment,
                    onCancel = { navController.popBackStack() },
                    onSave = viewModel::saveGarment
                )
            }

            composable(
                route = AppRoutes.GARMENT_EDIT,
                arguments = listOf(navArgument("garmentId") { type = NavType.StringType })
            ) { entry ->
                val garmentId = entry.arguments?.getString("garmentId")
                val existing = garmentId?.let { viewModel.findGarment(it) }
                GarmentFormScreen(
                    existingGarment = existing,
                    isSaving = uiState.isSavingGarment,
                    onCancel = { navController.popBackStack() },
                    onSave = viewModel::saveGarment
                )
            }
        }
    }
}
