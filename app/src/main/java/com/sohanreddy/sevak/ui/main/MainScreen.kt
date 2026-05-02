package com.sohanreddy.sevak.ui.main

import android.Manifest
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
