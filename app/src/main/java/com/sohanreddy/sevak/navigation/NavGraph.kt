package com.sohanreddy.sevak.navigation

import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthProvider
import com.sohanreddy.sevak.data.PrefsManager
import com.sohanreddy.sevak.ui.auth.OtpScreen
import com.sohanreddy.sevak.ui.auth.PhoneEntryScreen
import com.sohanreddy.sevak.ui.language.LanguagePickerScreen
import com.sohanreddy.sevak.ui.main.MainScreen

object Routes {
    const val PHONE = "phone"
    const val OTP = "otp"
    const val LANGUAGE = "language"
    const val MAIN = "main"
}

@Composable
fun SaathiNavGraph(
    navController: NavHostController,
    prefs: PrefsManager
) {
    // Determine start destination
    val currentUser = FirebaseAuth.getInstance().currentUser
    val startDest = when {
        currentUser == null -> Routes.PHONE
        prefs.getLanguageCode() == null -> Routes.LANGUAGE
        else -> Routes.MAIN
    }

    // Shared state for passing OTP data between screens
    var verificationId by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var resendToken by remember { mutableStateOf<PhoneAuthProvider.ForceResendingToken?>(null) }

    NavHost(navController = navController, startDestination = startDest) {
        composable(Routes.PHONE) {
            PhoneEntryScreen(
                onOtpSent = { vId, phone, token ->
                    verificationId = vId
                    phoneNumber = phone
                    resendToken = token
                    navController.navigate(Routes.OTP)
                }
            )
        }

        composable(Routes.OTP) {
            OtpScreen(
                verificationId = verificationId,
                phone = phoneNumber,
                resendToken = resendToken,
                onVerified = {
                    if (prefs.getLanguageCode() != null) {
                        navController.navigate(Routes.MAIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    } else {
                        navController.navigate(Routes.LANGUAGE) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(Routes.LANGUAGE) {
            LanguagePickerScreen(
                onLanguageSelected = { code, name ->
                    prefs.saveLanguage(code, name)
                    navController.navigate(Routes.MAIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.MAIN) {
            val langCode = prefs.getLanguageCode() ?: "en"
            MainScreen(
                langCode = langCode,
                onChangeLanguage = {
                    navController.navigate(Routes.LANGUAGE)
                },
                onSignOut = {
                    FirebaseAuth.getInstance().signOut()
                    prefs.clear()
                    navController.navigate(Routes.PHONE) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
