package com.sohanreddy.sevak.ui.main

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sohanreddy.sevak.data.getStatusText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    langCode: String,
    viewModel: MainViewModel = viewModel(),
    onChangeLanguage: () -> Unit,
    onSignOut: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()
    var showSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

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

    val bgColor = Color(0xFF0A0A0F)
    val textColor = Color(0xFFF5F5F5)

    Box(
        modifier = Modifier.fillMaxSize().background(bgColor)
    ) {
        // Settings icon top-right
        IconButton(
            onClick = { showSheet = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = textColor)
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mic button
            Surface(
                modifier = Modifier.size(120.dp).clickable {
                    if (viewModel.hasAudioPermission()) {
                        viewModel.onMicTap()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                shape = CircleShape,
                color = when (state.assistantState) {
                    AssistantState.IDLE -> Color(0xFF4CAF50)
                    AssistantState.LISTENING -> Color(0xFFF44336)
                    AssistantState.PROCESSING -> Color(0xFFFF9800)
                    AssistantState.SPEAKING -> Color(0xFF2196F3)
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when (state.assistantState) {
                        AssistantState.IDLE -> Icon(Icons.Default.Mic, contentDescription = "Mic", tint = Color.White, modifier = Modifier.size(48.dp))
                        AssistantState.LISTENING -> Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White, modifier = Modifier.size(48.dp))
                        AssistantState.PROCESSING -> CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp), strokeWidth = 4.dp)
                        AssistantState.SPEAKING -> Icon(Icons.Default.VolumeUp, contentDescription = "Speaking", tint = Color.White, modifier = Modifier.size(48.dp))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Status text
            Text(
                text = getStatusText(state.assistantState.name, langCode),
                color = textColor,
                fontSize = 18.sp
            )

            Spacer(Modifier.height(24.dp))

            // Response area
            if (state.lastResponse.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = state.lastResponse,
                        color = textColor.copy(alpha = 0.8f),
                        fontSize = 16.sp
                    )
                }
            }
        }

        // Settings bottom sheet
        if (showSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = sheetState
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        "Change Language",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showSheet = false
                                onChangeLanguage()
                            }
                            .padding(vertical = 16.dp),
                        fontSize = 18.sp
                    )
                    HorizontalDivider()
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
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}
