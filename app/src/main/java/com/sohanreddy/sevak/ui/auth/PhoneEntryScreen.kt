package com.sohanreddy.sevak.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sohanreddy.sevak.R
import com.sohanreddy.sevak.ui.theme.SaathiColors

@Composable
fun PhoneEntryScreen(
    viewModel: PhoneAuthViewModel = viewModel(),
    onOtpSent: (verificationId: String, phone: String, token: com.google.firebase.auth.PhoneAuthProvider.ForceResendingToken?) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as android.app.Activity

    LaunchedEffect(state.otpSent) {
        if (state.otpSent && state.verificationId != null) {
            onOtpSent(state.verificationId!!, state.phone, state.resendToken)
            viewModel.resetOtpSent()
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
            Spacer(Modifier.weight(0.30f))

            // Logo
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.saathi_logo),
                    contentDescription = "Saathi logo",
                    modifier = Modifier.size(176.dp)
                )
            }

            Spacer(Modifier.weight(0.16f))

            // ── Phone input pill ────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.White,
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    // Country code
                    Text(
                        text = "+91",
                        fontSize = 18.sp,
                        color = Color(0xFF333333)
                    )

                    // Vertical divider
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .width(1.dp)
                            .height(28.dp)
                            .background(Color(0xFFDDDDDD))
                    )

                    // Phone number field
                    TextField(
                        value = state.phone,
                        onValueChange = viewModel::onPhoneChange,
                        placeholder = {
                            Text("Enter number", color = Color(0xFFAAAAAA), fontSize = 16.sp)
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { viewModel.sendOtp(activity) }
                        ),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = SaathiColors.Primary,
                            focusedTextColor = Color(0xFF333333),
                            unfocusedTextColor = Color(0xFF333333)
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    // Submit arrow button
                    IconButton(
                        onClick = { viewModel.sendOtp(activity) },
                        enabled = !state.isLoading,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(SaathiColors.Primary)
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Send OTP",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            // Error message
            if (state.error != null) {
                Text(
                    state.error!!,
                    color = SaathiColors.Error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // Security note
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "We will send an OTP to verify your number",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.weight(0.35f))
        }
    }
}
