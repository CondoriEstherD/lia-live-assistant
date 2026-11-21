package com.lia.app.data.auth

import com.clerk.api.Clerk
import com.clerk.api.network.model.error.ClerkErrorResponse
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.network.serialization.onFailure
import com.clerk.api.network.serialization.onSuccess
import com.clerk.api.session.fetchToken
import com.clerk.api.signin.SignIn
import com.clerk.api.signin.attemptFirstFactor
import com.clerk.api.signin.resetPassword
import com.clerk.api.signup.SignUp
import com.clerk.api.signup.attemptVerification
import com.clerk.api.signup.prepareVerification
import com.clerk.api.sso.OAuthProvider
import com.clerk.api.sso.ResultType
import kotlinx.coroutines.flow.first

enum class SocialAuthProvider {
    GOOGLE,
    TIKTOK
}

sealed interface AuthOutcome {
    data class Authenticated(val clerkUserId: String) : AuthOutcome
    data object NeedsEmailVerification : AuthOutcome
    data object PasswordResetCodeSent : AuthOutcome
    data class Error(val message: String) : AuthOutcome
}

interface AuthRepository {
    suspend fun awaitCurrentClerkUserId(): String?
    suspend fun fetchSupabaseToken(): String?
    suspend fun signInWithPassword(identifier: String, password: String): AuthOutcome
    suspend fun signInWithSocial(provider: SocialAuthProvider): AuthOutcome
    suspend fun signUpWithEmail(email: String, password: String, username: String?): AuthOutcome
    suspend fun verifySignUpCode(code: String): AuthOutcome
    suspend fun sendPasswordResetCode(email: String): AuthOutcome
    suspend fun confirmPasswordReset(code: String, newPassword: String): AuthOutcome
    suspend fun signOut(): Result<Unit>
}

class ClerkAuthRepository : AuthRepository {

    private var pendingSignUp: SignUp? = null
    private var pendingResetSignIn: SignIn? = null

    private fun ClerkResult.Failure<ClerkErrorResponse>.readableMessage(fallback: String): String {
        val first = error?.errors?.firstOrNull()
        return first?.longMessage
            ?: first?.message
            ?: throwable?.localizedMessage
            ?: fallback
    }

    private suspend fun ensureInitialized() {
        if (!AuthConfig.isClerkConfigured) return
        if (!Clerk.isInitialized.value) {
            Clerk.isInitialized.first { it }
        }
    }

    override suspend fun awaitCurrentClerkUserId(): String? {
        if (!AuthConfig.isClerkConfigured) return null
        ensureInitialized()
        return Clerk.user?.id
    }

    override suspend fun fetchSupabaseToken(): String? {
        if (!AuthConfig.isClerkConfigured) return null
        ensureInitialized()
        val session = Clerk.session ?: return null
        var jwt: String? = null
        session.fetchToken().onSuccess { token ->
            jwt = token.jwt
        }
        return jwt
    }

    override suspend fun signInWithPassword(identifier: String, password: String): AuthOutcome {
        if (!AuthConfig.isClerkConfigured) {
            return AuthOutcome.Error("Configura CLERK_PUBLISHABLE_KEY en app/local.properties.")
        }
        ensureInitialized()
        var outcome: AuthOutcome = AuthOutcome.Error("No se pudo iniciar sesión.")
        SignIn.create(
            SignIn.CreateParams.Strategy.Password(
                identifier = identifier.trim(),
                password = password
            )
        ).onSuccess { signIn ->
            outcome = if (signIn.status == SignIn.Status.COMPLETE) {
                val userId = Clerk.user?.id
                if (userId != null) {
                    AuthOutcome.Authenticated(userId)
                } else {
                    AuthOutcome.Error("Sesión iniciada, pero no se encontró el usuario activo.")
                }
            } else {
                AuthOutcome.Error("Tu cuenta requiere un paso adicional de verificación en Clerk.")
            }
        }.onFailure { failure ->
            outcome = AuthOutcome.Error(failure.readableMessage("Credenciales inválidas."))
        }
        return outcome
    }

    override suspend fun signInWithSocial(provider: SocialAuthProvider): AuthOutcome {
        if (!AuthConfig.isClerkConfigured) {
            return AuthOutcome.Error("Configura CLERK_PUBLISHABLE_KEY en app/local.properties.")
        }
        ensureInitialized()
        val clerkProvider = when (provider) {
            SocialAuthProvider.GOOGLE -> OAuthProvider.GOOGLE
            SocialAuthProvider.TIKTOK -> OAuthProvider.TIKTOK
        }
        var outcome: AuthOutcome = AuthOutcome.Error("No se pudo completar el acceso con ${provider.name}.")
        SignIn.authenticateWithRedirect(
            SignIn.AuthenticateWithRedirectParams.OAuth(provider = clerkProvider)
        ).onSuccess { result ->
            val completed = when (result.resultType) {
                ResultType.SIGN_IN -> result.signIn?.status == SignIn.Status.COMPLETE
                ResultType.SIGN_UP -> result.signUp?.status == SignUp.Status.COMPLETE
                else -> Clerk.user != null
            }
            val userId = Clerk.user?.id
            outcome = if (completed && userId != null) {
                AuthOutcome.Authenticated(userId)
            } else {
                AuthOutcome.Error("No se completó el inicio de sesión social.")
            }
        }.onFailure { failure ->
            outcome = AuthOutcome.Error(
                failure.readableMessage("Error al iniciar sesión con ${provider.name}.")
            )
        }
        return outcome
    }

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
        username: String?
    ): AuthOutcome {
        if (!AuthConfig.isClerkConfigured) {
            return AuthOutcome.Error("Configura CLERK_PUBLISHABLE_KEY en app/local.properties.")
        }
        ensureInitialized()
        var outcome: AuthOutcome = AuthOutcome.Error("No se pudo crear la cuenta.")
        val cleanUsername = username?.trim()?.takeIf { it.isNotEmpty() }

        SignUp.create(
            SignUp.CreateParams.Standard(
                emailAddress = email.trim(),
                password = password,
                username = cleanUsername
            )
        ).onSuccess { signUp ->
            if (signUp.status == SignUp.Status.COMPLETE) {
                pendingSignUp = null
                val userId = Clerk.user?.id
                outcome = if (userId != null) {
                    AuthOutcome.Authenticated(userId)
                } else {
                    AuthOutcome.Error("Cuenta creada, pero no se pudo obtener la sesión.")
                }
            } else {
                pendingSignUp = signUp
                signUp.prepareVerification(SignUp.PrepareVerificationParams.Strategy.EmailCode())
                    .onSuccess { prepared ->
                        pendingSignUp = prepared
                        outcome = AuthOutcome.NeedsEmailVerification
                    }
                    .onFailure { failure ->
                        outcome = AuthOutcome.Error(
                            failure.readableMessage("No se pudo enviar el código de verificación.")
                        )
                    }
            }
        }.onFailure { failure ->
            outcome = AuthOutcome.Error(failure.readableMessage("No se pudo registrar el usuario."))
        }
        return outcome
    }

    override suspend fun verifySignUpCode(code: String): AuthOutcome {
        val activeSignUp = pendingSignUp
            ?: return AuthOutcome.Error("No hay un registro pendiente por verificar.")
        var outcome: AuthOutcome = AuthOutcome.Error("Código inválido.")

        activeSignUp.attemptVerification(
            SignUp.AttemptVerificationParams.EmailCode(code = code.trim())
        ).onSuccess { verified ->
            if (verified.status == SignUp.Status.COMPLETE) {
                pendingSignUp = null
                val userId = Clerk.user?.id
                outcome = if (userId != null) {
                    AuthOutcome.Authenticated(userId)
                } else {
                    AuthOutcome.Error("Correo verificado, pero no se pudo iniciar sesión.")
                }
            } else {
                pendingSignUp = verified
                outcome = AuthOutcome.Error("Aún faltan datos para completar el registro.")
            }
        }.onFailure { failure ->
            outcome = AuthOutcome.Error(failure.readableMessage("El código ingresado no es válido."))
        }
        return outcome
    }

    override suspend fun sendPasswordResetCode(email: String): AuthOutcome {
        if (!AuthConfig.isClerkConfigured) {
            return AuthOutcome.Error("Configura CLERK_PUBLISHABLE_KEY en app/local.properties.")
        }
        ensureInitialized()
        var outcome: AuthOutcome = AuthOutcome.Error("No se pudo enviar el código de recuperación.")

        SignIn.create(
            SignIn.CreateParams.Strategy.ResetPasswordEmailCode(
                identifier = email.trim()
            )
        ).onSuccess { signIn ->
            pendingResetSignIn = signIn
            outcome = AuthOutcome.PasswordResetCodeSent
        }.onFailure { failure ->
            outcome = AuthOutcome.Error(
                failure.readableMessage("No encontramos una cuenta con ese correo.")
            )
        }
        return outcome
    }

    override suspend fun confirmPasswordReset(code: String, newPassword: String): AuthOutcome {
        val activeSignIn = pendingResetSignIn
            ?: return AuthOutcome.Error("Primero solicita el código a tu correo.")
        var outcome: AuthOutcome = AuthOutcome.Error("No se pudo actualizar la contraseña.")

        activeSignIn.attemptFirstFactor(
            SignIn.AttemptFirstFactorParams.ResetPasswordEmailCode(code = code.trim())
        ).onSuccess { verifiedSignIn ->
            verifiedSignIn.resetPassword(newPassword).onSuccess { completed ->
                pendingResetSignIn = null
                val userId = Clerk.user?.id
                outcome = if (completed.status == SignIn.Status.COMPLETE && userId != null) {
                    AuthOutcome.Authenticated(userId)
                } else {
                    AuthOutcome.Error("Contraseña actualizada. Inicia sesión nuevamente.")
                }
            }.onFailure { failure ->
                outcome = AuthOutcome.Error(
                    failure.readableMessage("No se pudo guardar la nueva contraseña.")
                )
            }
        }.onFailure { failure ->
            outcome = AuthOutcome.Error(
                failure.readableMessage("El código ingresado no es válido.")
            )
        }
        return outcome
    }

    override suspend fun signOut(): Result<Unit> {
        pendingSignUp = null
        pendingResetSignIn = null
        if (!AuthConfig.isClerkConfigured) return Result.success(Unit)
        ensureInitialized()
        var result: Result<Unit> = Result.success(Unit)
        Clerk.auth.signOut()
            .onFailure { failure ->
                result = Result.failure(
                    IllegalStateException(failure.readableMessage("Error al cerrar sesión."))
                )
            }
        return result
    }
}
