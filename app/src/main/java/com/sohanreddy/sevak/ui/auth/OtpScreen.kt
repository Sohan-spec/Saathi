package com.sohanreddy.sevak.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.delay

@Composable
fun OtpScreen(
    verificationId: String,
    phone: String,
    resendToken: PhoneAuthProvider.ForceResendingToken?,
    viewModel: OtpViewModel = viewModel(),
    onVerified: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as android.app.Activity

    // Countdown timer
    LaunchedEffect(state.canResend) {
        if (!state.canResend && state.countdown > 0) {
            var count = state.countdown
            while (count > 0) {
                delay(1000)
                count--
                viewModel.updateCountdown(count)
            }
        }
    }

    LaunchedEffect(state.verified) {
        if (state.verified) onVerified()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Enter OTP", style = MaterialTheme.typography.headlineMedium)
        Text("Sent to +91 $phone", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = state.otp,
            onValueChange = viewModel::onOtpChange,
            label = { Text("6-digit OTP") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (state.error != null) {
            Text(state.error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { viewModel.verify(verificationId) },
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Verify")
        }

        Spacer(Modifier.height(16.dp))

        if (state.canResend) {
            Text(
                "Resend OTP",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { viewModel.resendOtp(activity, phone, resendToken) }
            )
        } else {
            Text("Resend OTP in ${state.countdown}s", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
