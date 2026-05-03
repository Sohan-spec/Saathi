package com.sohanreddy.sevak.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.auth.PhoneAuthProvider
import com.sohanreddy.sevak.R
import com.sohanreddy.sevak.ui.theme.SaathiColors
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
    val focusRequester = remember { FocusRequester() }
    var lastAutoSubmittedOtp by rememberSaveable { mutableStateOf("") }

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

    // Auto-focus the OTP field
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Auto-submit exactly once for each completed 6-digit value.
    LaunchedEffect(state.otp, state.isLoading, state.verified, verificationId) {
        if (state.otp.length < 6) {
            lastAutoSubmittedOtp = ""
        } else if (
            state.otp.length == 6 &&
            !state.isLoading &&
            !state.verified &&
            state.otp != lastAutoSubmittedOtp
        ) {
            lastAutoSubmittedOtp = state.otp
            viewModel.verify(verificationId)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Background image ────────────────────────────────────────
        Image(
            painter = painterResource(R.drawable.background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // ── Foreground content ──────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.25f))

            // Logo
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.saathi_logo),
                    contentDescription = "Saathi logo",
                    modifier = Modifier.size(168.dp)
                )
            }

            Spacer(Modifier.height(28.dp))

            // Heading
            Text(
                text = "Enter OTP",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Sent to +91 $phone",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(28.dp))

            // ── OTP digit boxes ─────────────────────────────────────
            BasicTextField(
                value = state.otp,
                onValueChange = viewModel::onOtpChange,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cursorBrush = SolidColor(Color.Transparent),
                textStyle = TextStyle(color = Color.Transparent),
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .fillMaxWidth(),
                decorationBox = {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val spacing = 8.dp
                        val boxSize = ((maxWidth - (spacing * 5)) / 6).coerceAtMost(56.dp)

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            repeat(6) { index ->
                                val char = state.otp.getOrNull(index)?.toString() ?: ""
                                val isFocused = state.otp.length == index

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(boxSize)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color.White.copy(alpha = 0.95f))
                                        .then(
                                            if (isFocused) Modifier.border(
                                                2.dp,
                                                SaathiColors.Primary,
                                                RoundedCornerShape(14.dp)
                                            ) else Modifier
                                        )
                                ) {
                                    Text(
                                        text = char,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF333333),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            )

            // Error message
            if (state.error != null) {
                Text(
                    state.error!!,
                    color = SaathiColors.Error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── Verify button ───────────────────────────────────────
            IconButton(
                onClick = { viewModel.verify(verificationId) },
                enabled = !state.isLoading,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(SaathiColors.Primary)
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Verify",
                        tint = Color.White
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Resend text
            if (state.canResend) {
                Text(
                    "Resend OTP",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { viewModel.resendOtp(activity, phone, resendToken) }
                )
            } else {
                Text(
                    "Resend OTP in ${state.countdown}s",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )
            }

            Spacer(Modifier.weight(0.35f))
        }
    }
}
