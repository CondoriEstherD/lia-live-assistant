package com.lia.app.data.sample

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lia.app.data.auth.AuthOutcome
import com.lia.app.data.auth.AuthRepository
import com.lia.app.data.auth.ClerkAuthRepository
import com.lia.app.data.auth.SocialAuthProvider
import com.lia.app.data.garments.GarmentRepository
import com.lia.app.data.garments.GarmentRpcResult
import com.lia.app.data.garments.SupabaseGarmentRepository
import com.lia.app.data.model.AppNotification
import com.lia.app.data.model.Garment
import com.lia.app.data.model.GarmentStatus
import com.lia.app.data.model.SaleRecord
import com.lia.app.data.model.SalesPeriod
import com.lia.app.data.model.SellerProfile
import com.lia.app.data.model.ThemeMode
import com.lia.app.data.model.UiLoadState
import com.lia.app.data.notifications.NotificationRepository
import com.lia.app.data.notifications.SupabaseNotificationRepository
import com.lia.app.data.profile.AppPreferences
import com.lia.app.data.profile.InMemoryAppPreferences
import com.lia.app.data.profile.ProfileRepository
import com.lia.app.data.profile.SupabaseProfileRepository
import com.lia.app.data.sales.SaleRpcResult
import com.lia.app.data.sales.SalesRepository
import com.lia.app.data.sales.SupabaseSalesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PendingRegistrationProfile(
    val whatsapp: String,
    val name: String,
    val username: String,
    val socialLinks: String
)

data class PhotoUploadPayload(
    val bytes: ByteArray,
    val extension: String
)

data class LiaAppUiState(
    val isCheckingSession: Boolean = true,
    val isAuthenticated: Boolean = false,
    val needsProfileCompletion: Boolean = false,
    val clerkUserId: String? = null,
    val clerkSuggestedName: String = "",
    val clerkSuggestedUsername: String = "",
    val isAuthBusy: Boolean = false,
    val authError: String? = null,
    val awaitingEmailVerification: Boolean = false,
    val passwordResetCodeSent: Boolean = false,
    val isSavingProfile: Boolean = false,
    val isSavingGarment: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val profile: SellerProfile,
    val garments: List<Garment>,
    val sales: List<SaleRecord>,
    val notifications: List<AppNotification>,
    val notificationsEnabled: Boolean = true,
    val selectedPeriod: SalesPeriod = SalesPeriod.TODAY,
    val selectedStatusFilter: GarmentStatus = GarmentStatus.IN_CATALOG,
    val searchQuery: String = "",
    val loadState: UiLoadState = UiLoadState.Ready,
    val snackbarMessage: String? = null
)

class LiaAppViewModel(
    private val appPreferences: AppPreferences = InMemoryAppPreferences(),
    private val authRepository: AuthRepository = ClerkAuthRepository(),
    private val profileRepository: ProfileRepository = SupabaseProfileRepository(
        tokenProvider = { authRepository.fetchSupabaseToken() }
    ),
    private val garmentRepository: GarmentRepository = SupabaseGarmentRepository(
        tokenProvider = { authRepository.fetchSupabaseToken() }
    ),
    private val salesRepository: SalesRepository = SupabaseSalesRepository(
        tokenProvider = { authRepository.fetchSupabaseToken() }
    ),
    private val notificationRepository: NotificationRepository = SupabaseNotificationRepository(
        tokenProvider = { authRepository.fetchSupabaseToken() }
    ),
    checkSessionOnInit: Boolean = true
) : ViewModel() {

    private var pendingRegistrationProfile: PendingRegistrationProfile? = null

    private val _uiState = MutableStateFlow(
        LiaAppUiState(
            isCheckingSession = checkSessionOnInit,
            themeMode = appPreferences.getThemeMode(),
            notificationsEnabled = appPreferences.areNotificationsEnabled(),
            profile = profileRepository.emptyProfile(),
            garments = garmentRepository.initialGarments(),
            sales = salesRepository.initialSales(),
            notifications = notificationRepository.sampleNotifications()
        )
    )
    val uiState: StateFlow<LiaAppUiState> = _uiState.asStateFlow()

    init {
        if (checkSessionOnInit) {
            restorePersistedSession()
        }
    }

    private fun restorePersistedSession() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingSession = true) }
            val clerkUserId = runCatching { authRepository.awaitCurrentClerkUserId() }.getOrNull()
            if (clerkUserId != null) {
                loadAuthenticatedSeller(clerkUserId)
            } else {
                _uiState.update {
                    it.copy(
                        isCheckingSession = false,
                        isAuthenticated = false,
                        clerkUserId = null
                    )
                }
            }
        }
    }

    fun onClerkUserChanged(
        clerkUserId: String?,
        suggestedName: String,
        suggestedUsername: String
    ) {
        val currentId = _uiState.value.clerkUserId
        if (clerkUserId == null) {
            if (currentId != null || _uiState.value.isCheckingSession || _uiState.value.isAuthenticated) {
                _uiState.update { state ->
                    state.copy(
                        isCheckingSession = false,
                        isAuthenticated = false,
                        needsProfileCompletion = false,
                        clerkUserId = null,
                        clerkSuggestedName = "",
                        clerkSuggestedUsername = "",
                        authError = null,
                        profile = profileRepository.emptyProfile(),
                        garments = emptyList(),
                        sales = emptyList(),
                        notifications = emptyList(),
                        searchQuery = "",
                        selectedPeriod = SalesPeriod.TODAY,
                        selectedStatusFilter = GarmentStatus.IN_CATALOG,
                        loadState = UiLoadState.Ready
                    )
                }
            }
            return
        }

        if (clerkUserId == currentId && _uiState.value.isAuthenticated) {
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCheckingSession = true,
                    clerkUserId = clerkUserId,
                    clerkSuggestedName = suggestedName,
                    clerkSuggestedUsername = suggestedUsername,
                    authError = null
                )
            }
            val sellerResult = runCatching { profileRepository.fetchCurrentSeller(clerkUserId) }
            val seller = sellerResult.getOrNull()
            val syncError = sellerResult.exceptionOrNull()?.localizedMessage

            if (seller == null && syncError == null) {
                _uiState.update { state ->
                    state.copy(
                        isCheckingSession = false,
                        isAuthenticated = true,
                        needsProfileCompletion = true,
                        profile = profileRepository.emptyProfile(),
                        authError = null
                    )
                }
            } else {
                _uiState.update { state ->
                    state.copy(
                        isCheckingSession = false,
                        isAuthenticated = true,
                        needsProfileCompletion = seller == null,
                        profile = seller ?: profileRepository.emptyProfile(),
                        authError = syncError?.let { "No se pudo consultar tu perfil en Supabase: $it" }
                    )
                }
                if (seller != null) {
                    refreshSellerData()
                }
            }
        }
    }

    fun completeSellerProfile(
        whatsapp: String,
        name: String,
        username: String,
        socialLinks: String
    ) {
        val clerkUserId = _uiState.value.clerkUserId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingProfile = true, authError = null) }
            val result = runCatching {
                profileRepository.createSeller(
                    clerkUserId = clerkUserId,
                    whatsapp = whatsapp,
                    name = name,
                    username = username,
                    socialLinks = socialLinks
                )
            }
            result.onSuccess { created ->
                _uiState.update { state ->
                    state.copy(
                        isSavingProfile = false,
                        needsProfileCompletion = false,
                        authError = null,
                        profile = created,
                        snackbarMessage = "Perfil completado"
                    )
                }
                refreshSellerData()
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(
                        isSavingProfile = false,
                        authError = "No se pudo guardar tu perfil: ${err.localizedMessage ?: "intenta nuevamente"}"
                    )
                }
            }
        }
    }

    fun clearAuthError() {
        _uiState.update { it.copy(authError = null) }
    }

    fun resetAuthFlows() {
        pendingRegistrationProfile = null
        _uiState.update {
            it.copy(
                authError = null,
                awaitingEmailVerification = false,
                passwordResetCodeSent = false,
                isAuthBusy = false
            )
        }
    }

    fun signInWithPassword(identifier: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthBusy = true, authError = null) }
            when (val outcome = authRepository.signInWithPassword(identifier, password)) {
                is AuthOutcome.Authenticated -> loadAuthenticatedSeller(outcome.clerkUserId)
                is AuthOutcome.Error -> _uiState.update { it.copy(isAuthBusy = false, authError = outcome.message) }
                else -> _uiState.update { it.copy(isAuthBusy = false) }
            }
        }
    }

    fun signInWithSocial(
        provider: SocialAuthProvider,
        registrationProfile: PendingRegistrationProfile? = null
    ) {
        viewModelScope.launch {
            pendingRegistrationProfile = registrationProfile
            _uiState.update { it.copy(isAuthBusy = true, authError = null) }
            when (val outcome = authRepository.signInWithSocial(provider)) {
                is AuthOutcome.Authenticated -> finalizeSellerAfterAuth(outcome.clerkUserId)
                is AuthOutcome.Error -> {
                    pendingRegistrationProfile = null
                    _uiState.update { it.copy(isAuthBusy = false, authError = outcome.message) }
                }
                else -> _uiState.update { it.copy(isAuthBusy = false) }
            }
        }
    }

    fun registerWithEmail(
        email: String,
        password: String,
        whatsapp: String,
        name: String,
        username: String,
        socialLinks: String
    ) {
        viewModelScope.launch {
            pendingRegistrationProfile = PendingRegistrationProfile(
                whatsapp = whatsapp.trim(),
                name = name.trim(),
                username = username.trim(),
                socialLinks = socialLinks.trim()
            )
            _uiState.update {
                it.copy(
                    isAuthBusy = true,
                    authError = null,
                    awaitingEmailVerification = false
                )
            }
            when (val outcome = authRepository.signUpWithEmail(email, password, username)) {
                is AuthOutcome.Authenticated -> finalizeSellerAfterAuth(outcome.clerkUserId)
                AuthOutcome.NeedsEmailVerification -> {
                    _uiState.update {
                        it.copy(
                            isAuthBusy = false,
                            awaitingEmailVerification = true,
                            authError = null
                        )
                    }
                }
                is AuthOutcome.Error -> _uiState.update { it.copy(isAuthBusy = false, authError = outcome.message) }
                else -> _uiState.update { it.copy(isAuthBusy = false) }
            }
        }
    }

    fun verifyRegistrationCode(code: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthBusy = true, authError = null) }
            when (val outcome = authRepository.verifySignUpCode(code)) {
                is AuthOutcome.Authenticated -> finalizeSellerAfterAuth(outcome.clerkUserId)
                is AuthOutcome.Error -> _uiState.update { it.copy(isAuthBusy = false, authError = outcome.message) }
                else -> _uiState.update { it.copy(isAuthBusy = false) }
            }
        }
    }

    fun sendPasswordResetCode(email: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthBusy = true, authError = null) }
            when (val outcome = authRepository.sendPasswordResetCode(email)) {
                AuthOutcome.PasswordResetCodeSent -> {
                    _uiState.update {
                        it.copy(
                            isAuthBusy = false,
                            passwordResetCodeSent = true,
                            authError = null
                        )
                    }
                }
                is AuthOutcome.Error -> _uiState.update { it.copy(isAuthBusy = false, authError = outcome.message) }
                else -> _uiState.update { it.copy(isAuthBusy = false) }
            }
        }
    }

    fun confirmPasswordReset(code: String, newPassword: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthBusy = true, authError = null) }
            when (val outcome = authRepository.confirmPasswordReset(code, newPassword)) {
                is AuthOutcome.Authenticated -> loadAuthenticatedSeller(outcome.clerkUserId)
                is AuthOutcome.Error -> _uiState.update { it.copy(isAuthBusy = false, authError = outcome.message) }
                else -> _uiState.update { it.copy(isAuthBusy = false) }
            }
        }
    }

    private suspend fun finalizeSellerAfterAuth(clerkUserId: String) {
        val pending = pendingRegistrationProfile
        pendingRegistrationProfile = null

        val sellerResult = runCatching {
            val existing = profileRepository.fetchCurrentSeller(clerkUserId)
            when {
                existing != null -> existing
                pending != null -> profileRepository.createSeller(
                    clerkUserId = clerkUserId,
                    whatsapp = pending.whatsapp,
                    name = pending.name,
                    username = pending.username,
                    socialLinks = pending.socialLinks
                )
                else -> profileRepository.emptyProfile()
            }
        }

        sellerResult.onSuccess { seller ->
            _uiState.update { state ->
                state.copy(
                    isCheckingSession = false,
                    isAuthBusy = false,
                    isAuthenticated = true,
                    clerkUserId = clerkUserId,
                    awaitingEmailVerification = false,
                    passwordResetCodeSent = false,
                    authError = null,
                    profile = seller,
                    snackbarMessage = if (pending != null) "Cuenta creada y perfil guardado" else null
                )
            }
            refreshSellerData()
        }.onFailure { err ->
            val fallbackProfile = if (pending != null) {
                SellerProfile(
                    id = "",
                    whatsapp = pending.whatsapp,
                    name = pending.name,
                    username = pending.username,
                    socialLinks = pending.socialLinks
                )
            } else {
                profileRepository.emptyProfile()
            }
            _uiState.update { state ->
                state.copy(
                    isCheckingSession = false,
                    isAuthBusy = false,
                    isAuthenticated = true,
                    clerkUserId = clerkUserId,
                    awaitingEmailVerification = false,
                    authError = null,
                    profile = fallbackProfile,
                    snackbarMessage = "Sesión iniciada. No se pudo sincronizar el perfil: ${err.localizedMessage ?: "revisa Supabase"}"
                )
            }
        }
    }

    private suspend fun loadAuthenticatedSeller(clerkUserId: String) {
        val sellerResult = runCatching { profileRepository.fetchCurrentSeller(clerkUserId) }
        val seller = sellerResult.getOrNull() ?: profileRepository.emptyProfile()
        val syncError = sellerResult.exceptionOrNull()?.localizedMessage

        _uiState.update { state ->
            state.copy(
                isCheckingSession = false,
                isAuthBusy = false,
                isAuthenticated = true,
                clerkUserId = clerkUserId,
                awaitingEmailVerification = false,
                passwordResetCodeSent = false,
                authError = null,
                profile = seller,
                snackbarMessage = syncError?.let { "No se pudo cargar tu perfil desde Supabase: $it" }
            )
        }
        refreshSellerData()
    }

    fun refreshSellerData() {
        viewModelScope.launch {
            _uiState.update { it.copy(loadState = UiLoadState.Loading) }
            val result = runCatching {
                val garments = garmentRepository.fetchGarments()
                val garmentsById = garments.associateBy { it.id }
                val sales = salesRepository.fetchSales(garmentsById)
                val notifications = runCatching {
                    notificationRepository.fetchSellerNotifications()
                }.getOrDefault(emptyList())
                Triple(garments, sales, notifications)
            }
            result.onSuccess { (garments, sales, notifications) ->
                _uiState.update { state ->
                    state.copy(
                        garments = garments,
                        sales = sales,
                        notifications = notifications,
                        loadState = UiLoadState.Ready
                    )
                }
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(
                        loadState = UiLoadState.Error(
                            err.localizedMessage ?: "No se pudieron cargar tus datos de Supabase."
                        )
                    )
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthBusy = true) }
            authRepository.signOut()
            pendingRegistrationProfile = null
            _uiState.update { state ->
                state.copy(
                    isCheckingSession = false,
                    isAuthBusy = false,
                    isAuthenticated = false,
                    clerkUserId = null,
                    authError = null,
                    awaitingEmailVerification = false,
                    passwordResetCodeSent = false,
                    profile = profileRepository.emptyProfile(),
                    garments = emptyList(),
                    sales = emptyList(),
                    notifications = emptyList(),
                    searchQuery = "",
                    selectedPeriod = SalesPeriod.TODAY,
                    selectedStatusFilter = GarmentStatus.IN_CATALOG,
                    loadState = UiLoadState.Ready,
                    snackbarMessage = null
                )
            }
        }
    }

    fun updateProfile(
        whatsapp: String,
        name: String,
        username: String,
        socialLinks: String
    ) {
        val currentState = _uiState.value
        val clerkUserId = currentState.clerkUserId
        if (clerkUserId == null) {
            _uiState.update { it.copy(snackbarMessage = "Debes iniciar sesión para editar tu perfil.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSavingProfile = true) }
            val result = runCatching {
                val existingId = currentState.profile.id
                if (existingId.isBlank()) {
                    profileRepository.createSeller(
                        clerkUserId = clerkUserId,
                        whatsapp = whatsapp,
                        name = name,
                        username = username,
                        socialLinks = socialLinks
                    )
                } else {
                    profileRepository.updateSeller(
                        sellerId = existingId,
                        whatsapp = whatsapp,
                        name = name,
                        username = username,
                        socialLinks = socialLinks
                    )
                }
            }
            result.onSuccess { updated ->
                _uiState.update { state ->
                    state.copy(
                        isSavingProfile = false,
                        profile = updated,
                        snackbarMessage = "Perfil guardado en Supabase"
                    )
                }
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(
                        isSavingProfile = false,
                        snackbarMessage = "Error al guardar perfil: ${err.localizedMessage ?: "intenta de nuevo"}"
                    )
                }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        appPreferences.saveThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        appPreferences.saveNotificationsEnabled(enabled)
        _uiState.update { it.copy(notificationsEnabled = enabled) }
    }

    fun markNotificationRead(notificationId: String) {
        _uiState.update { state ->
            state.copy(
                notifications = state.notifications.map { n ->
                    if (n.id == notificationId) n.copy(isUnread = false) else n
                }
            )
        }
        viewModelScope.launch {
            runCatching { notificationRepository.markAsRead(notificationId) }
        }
    }

    fun markAllNotificationsRead() {
        val unreadIds = _uiState.value.notifications.filter { it.isUnread }.map { it.id }
        if (unreadIds.isEmpty()) return
        _uiState.update { state ->
            state.copy(
                notifications = state.notifications.map { it.copy(isUnread = false) }
            )
        }
        viewModelScope.launch {
            unreadIds.forEach { id ->
                runCatching { notificationRepository.markAsRead(id) }
            }
        }
    }

    fun selectPeriod(period: SalesPeriod) {
        _uiState.update { it.copy(selectedPeriod = period) }
    }

    fun selectStatusFilter(status: GarmentStatus) {
        _uiState.update { it.copy(selectedStatusFilter = status) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun retryLoad() {
        refreshSellerData()
    }

    fun filteredSales(): List<SaleRecord> {
        val state = _uiState.value
        return salesRepository.filterByPeriod(state.sales, state.selectedPeriod)
    }

    fun filteredGarments(): List<Garment> {
        val state = _uiState.value
        val q = state.searchQuery.trim().lowercase()
        return state.garments.filter { garment ->
            val matchesStatus = garment.status == state.selectedStatusFilter
            val matchesQuery = q.isEmpty() ||
                garment.code.lowercase().contains(q) ||
                garment.name.lowercase().contains(q)
            matchesStatus && matchesQuery
        }
    }

    fun findGarment(id: String): Garment? = _uiState.value.garments.find { it.id == id }

    fun isActiveCodeTaken(code: String, excludingId: String? = null): Boolean =
        garmentRepository.isActiveCodeTaken(_uiState.value.garments, code, excludingId)

    fun saveGarment(
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
    ) {
        val state = _uiState.value
        val sellerId = state.profile.id
        val clerkUserId = state.clerkUserId
        if (sellerId.isBlank() || clerkUserId == null) {
            onResult("Primero completa y guarda tu WhatsApp en Perfil para crear prendas.")
            return
        }

        val trimmedCode = code.trim().uppercase()
        val existing = existingId?.let { findGarment(it) }
        val willBeInCatalog = when {
            existing == null -> true
            existing.status == GarmentStatus.ARCHIVED -> false
            else -> stock > 0
        }

        if (willBeInCatalog && isActiveCodeTaken(trimmedCode, excludingId = existingId)) {
            onResult("Ya tienes una prenda en catálogo con el código $trimmedCode.")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSavingGarment = true) }
            val result = runCatching {
                val previousPhotoUrl = existing?.photoUri
                val finalPhotoUrl = when {
                    newPhotoUpload != null -> {
                        val uploadedUrl = garmentRepository.uploadGarmentPhoto(
                            clerkUserId = clerkUserId,
                            bytes = newPhotoUpload.bytes,
                            extension = newPhotoUpload.extension
                        )
                        if (!previousPhotoUrl.isNullOrBlank()) {
                            garmentRepository.deleteGarmentPhoto(previousPhotoUrl)
                        }
                        uploadedUrl
                    }
                    existingRemotePhotoUrl.isNullOrBlank() && !previousPhotoUrl.isNullOrBlank() -> {
                        garmentRepository.deleteGarmentPhoto(previousPhotoUrl)
                        null
                    }
                    else -> existingRemotePhotoUrl
                }

                if (existing == null) {
                    garmentRepository.createGarment(
                        sellerId = sellerId,
                        code = trimmedCode,
                        sellingPrice = sellingPrice,
                        stock = stock.coerceAtLeast(1),
                        name = name,
                        photoUrl = finalPhotoUrl,
                        size = size,
                        color = color,
                        description = description
                    )
                } else {
                    garmentRepository.updateGarmentDetails(
                        garmentId = existing.id,
                        code = trimmedCode,
                        sellingPrice = sellingPrice,
                        name = name,
                        photoUrl = finalPhotoUrl,
                        size = size,
                        color = color,
                        description = description
                    )
                    if (stock != existing.stock && existing.status != GarmentStatus.ARCHIVED) {
                        when (val stockRes = garmentRepository.setGarmentStock(existing.id, stock)) {
                            GarmentRpcResult.Success -> Unit
                            is GarmentRpcResult.Error -> throw IllegalStateException(stockRes.message)
                        }
                    }
                }
            }

            result.onSuccess {
                _uiState.update { s ->
                    s.copy(
                        isSavingGarment = false,
                        snackbarMessage = if (existing == null) "Prenda $trimmedCode creada" else "Prenda $trimmedCode actualizada"
                    )
                }
                refreshSellerData()
                onResult(null)
            }.onFailure { err ->
                _uiState.update { it.copy(isSavingGarment = false) }
                val raw = err.localizedMessage.orEmpty()
                val friendly = when {
                    raw.contains("garments_seller_code_uq", ignoreCase = true) ||
                        raw.contains("23505") ->
                        "Ya tienes una prenda en catálogo con el código $trimmedCode."
                    else -> raw.ifBlank { "No se pudo guardar la prenda." }
                }
                onResult(friendly)
            }
        }
    }

    fun recordSale(garmentId: String, quantity: Int) {
        val garment = findGarment(garmentId) ?: return
        viewModelScope.launch {
            val result = runCatching { salesRepository.recordManualSale(garmentId, quantity) }
            result.onSuccess { rpcResult ->
                when (rpcResult) {
                    SaleRpcResult.Success -> {
                        _uiState.update {
                            it.copy(snackbarMessage = "Venta registrada: ${garment.code} (x$quantity)")
                        }
                        refreshSellerData()
                    }
                    is SaleRpcResult.Error -> {
                        _uiState.update { it.copy(snackbarMessage = rpcResult.message) }
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(snackbarMessage = "Error al registrar venta: ${err.localizedMessage ?: "intenta de nuevo"}")
                }
            }
        }
    }

    fun revertSale(saleId: String) {
        val sale = _uiState.value.sales.find { it.id == saleId } ?: return
        viewModelScope.launch {
            val result = runCatching { salesRepository.voidSale(saleId) }
            result.onSuccess { rpcResult ->
                when (rpcResult) {
                    SaleRpcResult.Success -> {
                        _uiState.update {
                            it.copy(snackbarMessage = "Venta de ${sale.garmentCode} corregida y stock restaurado")
                        }
                        refreshSellerData()
                    }
                    is SaleRpcResult.Error -> {
                        _uiState.update { it.copy(snackbarMessage = rpcResult.message) }
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(snackbarMessage = "Error al corregir venta: ${err.localizedMessage ?: "intenta de nuevo"}")
                }
            }
        }
    }

    fun archiveGarment(garmentId: String) {
        val garment = findGarment(garmentId) ?: return
        viewModelScope.launch {
            val result = runCatching { garmentRepository.archiveGarment(garmentId) }
            result.onSuccess { rpcResult ->
                when (rpcResult) {
                    GarmentRpcResult.Success -> {
                        _uiState.update {
                            it.copy(snackbarMessage = "Prenda ${garment.code} archivada")
                        }
                        refreshSellerData()
                    }
                    is GarmentRpcResult.Error -> {
                        _uiState.update { it.copy(snackbarMessage = rpcResult.message) }
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(snackbarMessage = "Error al archivar: ${err.localizedMessage ?: "intenta de nuevo"}")
                }
            }
        }
    }
}
