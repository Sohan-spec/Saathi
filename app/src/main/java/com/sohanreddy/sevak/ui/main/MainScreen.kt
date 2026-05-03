package com.sohanreddy.sevak.ui.main

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sohanreddy.sevak.R
import com.sohanreddy.sevak.data.PrefsManager
import com.sohanreddy.sevak.data.getStatusText
import com.sohanreddy.sevak.data.supportedLanguages
import com.sohanreddy.sevak.screenshare.ScreenShareService
import com.sohanreddy.sevak.ui.theme.SaathiColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    prefs: PrefsManager,
    viewModel: MainViewModel = viewModel(),
    onSignOut: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()
    var showSheet by remember { mutableStateOf(false) }
    val waveformInteraction = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val activity = context as? Activity
    val projectionManager = remember(context) { context.getSystemService(MediaProjectionManager::class.java) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val projectionData = result.data
        if (result.resultCode != Activity.RESULT_OK || projectionData == null) {
            Toast.makeText(context, "Screen sharing permission denied", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }

        val serviceIntent = Intent(context, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_START
            putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(ScreenShareService.EXTRA_RESULT_DATA, projectionData)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
        Toast.makeText(context, "Screen sharing started", Toast.LENGTH_SHORT).show()
        activity?.moveTaskToBack(true)
    }

    val launchProjectionRequest: () -> Unit = {
        val manager = projectionManager
        if (manager == null) {
            Toast.makeText(context, "Screen capture is unavailable on this device", Toast.LENGTH_SHORT).show()
        } else {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
            mediaProjectionLauncher.launch(intent)
        }
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            launchProjectionRequest()
        } else {
            Toast.makeText(context, "Overlay permission is required for floating bubble", Toast.LENGTH_SHORT).show()
        }
    }

    // Use detected language from state, or saved language from prefs, or default to "en"
    val currentLangCode = state.detectedLangCode
        ?: prefs.getLanguageCode()
        ?: "en"

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onMicTap()
        } else {
            Toast.makeText(context, "Microphone permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    val screenShareAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "Microphone permission is required", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }

        if (Settings.canDrawOverlays(context)) {
            launchProjectionRequest()
        } else {
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            overlayPermissionLauncher.launch(overlayIntent)
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

        // ── Settings icon — top right ───────────────────────────────
        IconButton(
            onClick = { showSheet = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Settings",
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(26.dp)
            )
        }

        // ── Waveform centred content ────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Clickable waveform area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clickable(
                        interactionSource = waveformInteraction,
                        indication = null
                    ) {
                        if (viewModel.hasAudioPermission()) {
                            viewModel.onMicTap()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                WaveformCanvas(
                    modifier = Modifier.fillMaxSize(),
                    isActive = state.assistantState == AssistantState.LISTENING ||
                            state.assistantState == AssistantState.SPEAKING,
                    isStatic = state.assistantState == AssistantState.PROCESSING,
                    amplitude = state.audioAmplitude
                )
            }

            Spacer(Modifier.height(20.dp))

            // Status text
            Text(
                text = getStatusText(state.assistantState.name, currentLangCode),
                color = when (state.assistantState) {
                    AssistantState.LISTENING -> Color(0xCC64A0FF)
                    AssistantState.SPEAKING -> Color(0xCC64A0FF)
                    else -> Color.White.copy(alpha = 0.45f)
                },
                fontSize = 13.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Normal
            )
        }

        // ── Generated Image Overlay ──────────────────────────────────
        val generatedImage = state.generatedImage
        if (generatedImage != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .clickable { viewModel.clearGeneratedImage() },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    tonalElevation = 8.dp,
                    shadowElevation = 16.dp
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            bitmap = generatedImage.asImageBitmap(),
                            contentDescription = "AI generated image",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .clip(RoundedCornerShape(14.dp))
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Tap to dismiss",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // Quick share bubble launcher in bottom-right corner.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 24.dp)
                .size(74.dp)
                .clickable {
                    if (viewModel.hasAudioPermission()) {
                        if (Settings.canDrawOverlays(context)) {
                            launchProjectionRequest()
                        } else {
                            val overlayIntent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            overlayPermissionLauncher.launch(overlayIntent)
                        }
                    } else {
                        screenShareAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            shape = RoundedCornerShape(37.dp),
            color = Color(0xCC0D244F),
            shadowElevation = 8.dp,
            tonalElevation = 1.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.saathi_logo),
                    contentDescription = "Share screen",
                    modifier = Modifier.size(44.dp),
                    contentScale = ContentScale.Crop
                )
            }
        }

        // ── Settings bottom sheet ───────────────────────────────────
        if (showSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = sheetState,
                containerColor = Color(0xE60C1C41),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(top = 12.dp, bottom = 8.dp)
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(SaathiColors.PrimaryBright.copy(alpha = 0.4f))
                    )
                }
            ) {
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(
                        "Change Language",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SaathiColors.TextPrimary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Language grid — 2 columns
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.heightIn(max = 320.dp)
                    ) {
                        items(supportedLanguages) { lang ->
                            val isSelected = currentLangCode == lang.code
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setLanguageManually(lang.code, lang.englishName)
                                        showSheet = false
                                    },
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected)
                                    SaathiColors.PrimaryBright.copy(alpha = 0.25f)
                                else
                                    Color.White.copy(alpha = 0.06f),
                                border = if (isSelected)
                                    androidx.compose.foundation.BorderStroke(1.dp, SaathiColors.PrimaryBright.copy(alpha = 0.5f))
                                else
                                    androidx.compose.foundation.BorderStroke(1.dp, SaathiColors.CardBorder)
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 14.dp, horizontal = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = lang.displayName,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) SaathiColors.PrimaryBright else Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = lang.englishName,
                                        fontSize = 12.sp,
                                        color = if (isSelected)
                                            SaathiColors.PrimaryBright.copy(alpha = 0.7f)
                                        else
                                            Color.White.copy(alpha = 0.4f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(Modifier.height(8.dp))

                    Text(
                        "Sign Out",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showSheet = false
                                onSignOut()
                            }
                            .padding(vertical = 16.dp),
                        fontSize = 18.sp,
                        color = SaathiColors.Error
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}
