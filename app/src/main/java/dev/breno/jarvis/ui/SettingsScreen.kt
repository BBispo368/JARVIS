package dev.breno.jarvis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.breno.jarvis.model.DiscoveryState
import dev.breno.jarvis.settings.JarvisSettings
import dev.breno.jarvis.ui.theme.JarvisColors

@Composable
fun SettingsScreen(
    initial: JarvisSettings,
    discovery: DiscoveryState,
    onDiscover: () -> Unit,
    onSave: (JarvisSettings) -> Unit,
    onCancel: () -> Unit,
) {
    var apiKey by rememberSaveable { mutableStateOf(initial.apiKey) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var pcUrl by rememberSaveable { mutableStateOf(initial.pcUrl) }
    var pcToken by rememberSaveable { mutableStateOf(initial.pcToken) }
    var showSecrets by rememberSaveable { mutableStateOf(false) }

    // A busca na rede preenche o endereço sozinha.
    LaunchedEffect(discovery.foundUrl) { discovery.foundUrl?.let { pcUrl = it } }
    BackHandler(onBack = onCancel)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisColors.Background)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ajustes", color = JarvisColors.Cyan, fontSize = 22.sp, letterSpacing = 4.sp)

        Section("Claude")
        Field("Chave da API", apiKey, { apiKey = it }, secret = !showSecrets)
        Field("Modelo", model, { model = it })

        Section("Computador")
        Hint(
            "Rode o pc-agent/jarvis_agent.py no PC. Ele mostra o token no terminal. " +
                "Com o token salvo, o app acha o PC na rede sozinho, mesmo se o IP mudar."
        )
        Field("Token do PC", pcToken, { pcToken = it }, secret = !showSecrets)
        Field("Endereço do PC (opcional)", pcUrl, { pcUrl = it }, keyboard = KeyboardType.Uri)
        OutlinedButton(onClick = onDiscover, enabled = !discovery.searching) {
            Text(if (discovery.searching) "Procurando…" else "Procurar PC na rede")
        }
        discovery.message?.let { Hint(it) }

        TextButton(onClick = { showSecrets = !showSecrets }) {
            Text(if (showSecrets) "Ocultar chaves" else "Mostrar chaves", color = JarvisColors.TextMuted)
        }

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancelar") }
            Button(
                onClick = { onSave(JarvisSettings(apiKey, model, pcUrl, pcToken)) },
                colors = ButtonDefaults.buttonColors(containerColor = JarvisColors.Cyan),
            ) { Text("Salvar", color = JarvisColors.Background) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = JarvisColors.TextPrimary, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = JarvisColors.TextMuted, fontSize = 13.sp)
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else keyboard,
            autoCorrectEnabled = false,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = JarvisColors.TextPrimary,
            unfocusedTextColor = JarvisColors.TextPrimary,
            focusedBorderColor = JarvisColors.Cyan,
            focusedLabelColor = JarvisColors.Cyan,
            unfocusedLabelColor = JarvisColors.TextMuted,
            cursorColor = JarvisColors.Cyan,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
