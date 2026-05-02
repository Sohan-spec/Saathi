package com.sohanreddy.sevak.ui.auth

import androidx.lifecycle.ViewModel
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

data class PhoneAuthState(
    val phone: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val otpSent: Boolean = false,
    val verificationId: String? = null,
    val resendToken: PhoneAuthProvider.ForceResendingToken? = null
)

class PhoneAuthViewModel : ViewModel() {
    private val _state = MutableStateFlow(PhoneAuthState())
    val state = _state.asStateFlow()
    private val auth = FirebaseAuth.getInstance()

    fun onPhoneChange(phone: String) {
        _state.value = _state.value.copy(phone = phone.filter { it.isDigit() }.take(10), error = null)
    }

    fun sendOtp(activity: android.app.Activity) {
        val phone = "+91${_state.value.phone}"
        if (_state.value.phone.length != 10) {
            _state.value = _state.value.copy(error = "Enter a valid 10-digit number")
            return
        }
        _state.value = _state.value.copy(isLoading = true, error = null)

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                // Auto-verification handled at OTP screen
            }
            override fun onVerificationFailed(e: FirebaseException) {
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "OTP send failed")
            }
            override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                _state.value = _state.value.copy(
                    isLoading = false, otpSent = true,
                    verificationId = id, resendToken = token
                )
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phone)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    fun resetOtpSent() {
        _state.value = _state.value.copy(otpSent = false)
    }
}
