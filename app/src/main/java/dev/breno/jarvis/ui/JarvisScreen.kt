package dev.breno.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.breno.jarvis.model.ChatMessage
import dev.breno.jarvis.model.JarvisStatus
import dev.breno.jarvis.model.JarvisUiState
import dev.breno.jarvis.model.PcStatus
import dev.breno.jarvis.model.Role
import dev.breno.jarvis.ui.theme.JarvisColors
import dev.breno.jarvis.ui.theme.JarvisTheme

@Composable
fun JarvisScreen(
    state: JarvisUiState,
    onOrbTap: () -> Unit,
    onConversationModeChange: (Boolean) -> Unit,
    onPcStatusTap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisColors.Background)
            .systemBarsPadding()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "J.A.R.V.I.S.",
            color = JarvisColors.Cyan,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 8.sp,
            modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
        )
        PcIndicator(status = state.pcStatus, toolCount = state.pcToolCount, onClick = onPcStatusTap)

        MessageList(messages = state.messages, modifier = Modifier.weight(1f).fillMaxWidth())

        StatusText(state)

        Orb(
            status = state.status,
            micLevel = state.micLevel,
            onClick = onOrbTap,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            Text("Modo conversa", color = JarvisColors.TextMuted, fontSize = 14.sp)
            Spacer(Modifier.padding(horizontal = 6.dp))
            Switch(
                checked = state.conversationMode,
                onCheckedChange = onConversationModeChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = JarvisColors.Background,
                    checkedTrackColor = JarvisColors.Cyan,
                ),
            )
        }
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(messages, key = { it.id }) { MessageBubble(it) }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == Role.USER
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            text = message.text,
            color = JarvisColors.TextPrimary,
            fontSize = 15.sp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                    )
                )
                .background(if (isUser) JarvisColors.UserBubble else JarvisColors.JarvisBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun StatusText(state: JarvisUiState) {
    val status = when (state.status) {
        JarvisStatus.IDLE -> "Toque para falar"
        JarvisStatus.LISTENING -> "Ouvindo…"
        JarvisStatus.THINKING -> state.activity?.let { "$it…" } ?: "Pensando…"
        JarvisStatus.SPEAKING -> "Falando… (toque pra interromper)"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Text(status, color = JarvisColors.Cyan.copy(alpha = 0.85f), fontSize = 14.sp, letterSpacing = 1.sp)
        if (state.partialTranscript.isNotBlank()) {
            Text(
                text = state.partialTranscript,
                color = JarvisColors.TextPrimary,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        state.error?.let {
            Text(
                text = it,
                color = JarvisColors.Error,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun PcIndicator(status: PcStatus, toolCount: Int, onClick: () -> Unit) {
    val (color, text) = when (status) {
        PcStatus.NOT_CONFIGURED -> JarvisColors.TextMuted to "PC não configurado"
        PcStatus.CHECKING -> JarvisColors.Amber to "Procurando o PC…"
        PcStatus.ONLINE -> JarvisColors.Cyan to "PC conectado · $toolCount ferramentas"
        PcStatus.OFFLINE -> JarvisColors.Error to "PC offline · toque para tentar de novo"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = JarvisColors.TextMuted, fontSize = 12.sp)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF05080F)
@Composable
private fun JarvisScreenPreview() {
    JarvisTheme {
        JarvisScreen(
            state = JarvisUiState(
                status = JarvisStatus.SPEAKING,
                messages = listOf(
                    ChatMessage(0, Role.USER, "Que horas são?"),
                    ChatMessage(1, Role.ASSISTANT, "São três da tarde, senhor. Ainda dá tempo de ser produtivo."),
                ),
            ),
            onOrbTap = {},
            onConversationModeChange = {},
            onPcStatusTap = {},
        )
    }
}
