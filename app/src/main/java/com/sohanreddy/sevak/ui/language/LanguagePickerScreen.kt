package com.sohanreddy.sevak.ui.language

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sohanreddy.sevak.data.supportedLanguages

@Composable
fun LanguagePickerScreen(
    onLanguageSelected: (code: String, name: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Choose your language", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))

        supportedLanguages.forEach { lang ->
            Button(
                onClick = { onLanguageSelected(lang.code, lang.englishName) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                Text(lang.displayName, fontSize = 20.sp)
            }
        }
    }
}
