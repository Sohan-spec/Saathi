package com.sohanreddy.sevak.ui.auth

import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.FirebaseException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

data class OtpState(
    val otp: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val verified: Boolean = false,
    val countdown: Int = 30,
    val canResend: Boolean = false
)

class OtpViewModel : ViewModel() {
    private val _state = MutableStateFlow(OtpState())
    val state = _state.asStateFlow()
    private val auth = FirebaseAuth.getInstance()

    fun onOtpChange(otp: String) {
        _state.value = _state.value.copy(otp = otp.filter { it.isDigit() }.take(6), error = null)
    }

    fun updateCountdown(value: Int) {
        _state.value = _state.value.copy(countdown = value, canResend = value <= 0)
    }

    fun verify(verificationId: String) {
        if (_state.value.otp.length != 6) {
            _state.value = _state.value.copy(error = "Enter 6-digit OTP")
            return
        }
        _state.value = _state.value.copy(isLoading = true, error = null)
        val credential = PhoneAuthProvider.getCredential(verificationId, _state.value.otp)
        auth.signInWithCredential(credential)
            .addOnSuccessListener { _state.value = _state.value.copy(isLoading = false, verified = true) }
            .addOnFailureListener { _state.value = _state.value.copy(isLoading = false, error = it.message ?: "Verification failed") }
    }

    fun resendOtp(activity: android.app.Activity, phone: String, resendToken: PhoneAuthProvider.ForceResendingToken?) {
        _state.value = _state.value.copy(canResend = false, countdown = 30, error = null)
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {}
            override fun onVerificationFailed(e: FirebaseException) {
                _state.value = _state.value.copy(error = e.message ?: "Resend failed")
            }
            override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                // New verificationId — caller should update
            }
        }
        val builder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber("+91$phone")
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
        if (resendToken != null) builder.setForceResendingToken(resendToken)
        PhoneAuthProvider.verifyPhoneNumber(builder.build())
    }
}
