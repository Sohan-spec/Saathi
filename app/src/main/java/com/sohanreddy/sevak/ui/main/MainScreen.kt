package com.sohanreddy.sevak.ui.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
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
import androidx.compose.foundation.shape.CircleShape
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

    // Use detected language from state, or saved language from prefs, or default to "en"
    val currentLangCode = state.detectedLangCode
        ?: prefs.getLanguageCode()
        ?: "en"

    // ── Request ALL permissions immediately on first load ─────────
    val allPermsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.d("MainScreen", "Permission results: $results")
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true
        if (!audioGranted) {
            Toast.makeText(context, "Microphone permission is required for Saathi", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        val permsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permsNeeded.isNotEmpty()) {
            Log.d("MainScreen", "Requesting permissions: $permsNeeded")
            allPermsLauncher.launch(permsNeeded.toTypedArray())
        }
    }

    // MediaProjection launcher for screen capture
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("MainScreen", "MediaProjection result: code=${result.resultCode}, data=${result.data != null}")
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.activateScreenMode(result.resultCode, result.data!!)
            Toast.makeText(context, "Screen mode active — speak your question", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
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
                        viewModel.onMicTap()
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

        // ── Screen Mode Button — bottom right ───────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(24.dp)
        ) {
            // Button
            IconButton(
                onClick = {
                    if (state.screenModeActive) {
                        viewModel.deactivateScreenMode()
                        Toast.makeText(context, "Screen mode off", Toast.LENGTH_SHORT).show()
                    } else {
                        // Check audio permission first
                        if (!viewModel.hasAudioPermission()) {
                            Toast.makeText(context, "Microphone permission required first", Toast.LENGTH_SHORT).show()
                            allPermsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                            return@IconButton
                        }
                        val projectionManager = context.getSystemService(
                            Context.MEDIA_PROJECTION_SERVICE
                        ) as MediaProjectionManager
                        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (state.screenModeActive)
                            Color(0xFF4CAF50).copy(alpha = 0.25f)
                        else
                            Color.White.copy(alpha = 0.08f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_screen_share),
                    contentDescription = if (state.screenModeActive) "Disable screen mode" else "Enable screen mode",
                    tint = if (state.screenModeActive)
                        Color(0xFF4CAF50)
                    else
                        Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp)
                )
            }

            // Green dot indicator
            if (state.screenModeActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(10.dp)
                        .background(Color(0xFF4CAF50), CircleShape)
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
