package com.keepasskey.app.ui.screens.unlock

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal suspend fun observeBiometricAutoPrompt(
    screenLifecycle: Lifecycle,
    hostLifecycle: Lifecycle,
    state: StateFlow<UnlockUiState>,
    request: () -> Unit
) {
    screenLifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        hostLifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state.collect { request() }
        }
    }
}

internal class BiometricUnlockPrompt(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = 60_000L
) {
    private class Attempt(val onResult: (BiometricResult) -> Unit) {
        var timeout: Job? = null
        var cancel: (() -> Unit)? = null
        var succeeded = false
    }

    private var active: Attempt? = null

    fun start(
        onResult: (BiometricResult) -> Unit,
        authenticate: ((BiometricResult) -> Unit) -> (() -> Unit)
    ) {
        cancel()
        val attempt = Attempt(onResult)
        active = attempt
        attempt.timeout = scope.launch {
            delay(timeoutMillis)
            finish(
                attempt,
                BiometricResult.Error(
                    BiometricAuthManager.ERROR_AUTH_TIMEOUT,
                    BiometricAuthManager.AUTH_TIMEOUT_DIAGNOSTIC
                )
            )
        }
        try {
            val cancel = authenticate { finish(attempt, it) }
            if (active === attempt) {
                attempt.cancel = cancel
            } else if (!attempt.succeeded) {
                cancel()
            }
        } catch (e: Exception) {
            if (active === attempt) active = null
            attempt.timeout?.cancel()
            throw e
        }
    }

    fun cancel() {
        active?.let { finish(it, BiometricResult.Cancelled) }
    }

    private fun finish(attempt: Attempt, result: BiometricResult) {
        if (active !== attempt) return
        active = null
        attempt.succeeded = result is BiometricResult.Success
        attempt.timeout?.cancel()
        try {
            if (!attempt.succeeded) attempt.cancel?.invoke()
        } finally {
            attempt.onResult(result)
        }
    }
}
