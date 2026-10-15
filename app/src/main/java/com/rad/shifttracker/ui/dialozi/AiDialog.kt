package com.rad.shifttracker.ui.dialozi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rad.shifttracker.ai.AiApplyResult
import com.rad.shifttracker.ai.AiChange
import com.rad.shifttracker.ai.AiException
import com.rad.shifttracker.ai.AiProposal
import com.rad.shifttracker.ai.AiSession
import com.rad.shifttracker.ai.describeAiChange
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.ui.CenteredDialog
import com.rad.shifttracker.ui.PopupCard
import com.rad.shifttracker.ui.RoundedButton
import com.rad.shifttracker.ui.Spacing
import com.rad.shifttracker.ui.extendedColors

/** Poruka u AI razgovoru. */
internal sealed interface AiMessage {
    data class User(val text: String) : AiMessage
    data class Assistant(val text: String) : AiMessage
    data class Info(val text: String) : AiMessage
    data class Error(val title: String, val detail: String?) : AiMessage
}

/**
 * Stanje AI razgovora; živi u view modelu, pa razgovor ostaje i kad se prozor zatvori, a odgovor
 * stiže i ako je prozor u međuvremenu zatvoren.
 */
internal class AiChatState(private val session: AiSession, private val scope: CoroutineScope) {
    val messages = mutableStateListOf<AiMessage>()
    var proposal by mutableStateOf<AiProposal?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    fun send(text: String, repo: ShiftRepository, today: LocalDate) {
        val question = text.trim()
        if (question.isEmpty() || busy) return
        proposal?.let { decline() }
        messages += AiMessage.User(question)
        busy = true
        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { session.send(question, repo, today) }
                if (reply.text.isNotBlank()) messages += AiMessage.Assistant(reply.text)
                proposal = reply.proposal
            } catch (e: AiException) {
                messages += AiMessage.Error(e.title, e.detail)
            } catch (e: Exception) {
                // Greška u samoj aplikaciji (ne kod Google-a): vrsta i poruka, da se zna šta je puklo.
                messages += AiMessage.Error("Greška u aplikaciji", "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun confirm(apply: (List<AiChange>) -> AiApplyResult) {
        val current = proposal ?: return
        proposal = null
        val result = apply(current.changes)
        val text = buildString {
            append("Upisano dana: ${result.writtenDays}")
            result.skipped.forEach { append("\nNije upisano — $it") }
        }
        messages += AiMessage.Info(text)
        session.noteForNextMessage("Korisnik je potvrdio izmene; $text")
    }

    fun decline() {
        if (proposal == null) return
        proposal = null
        messages += AiMessage.Info("Izmene nisu upisane")
        session.noteForNextMessage("Korisnik je odbio predložene izmene; ništa nije upisano")
    }

    fun clear() {
        if (busy) return
        messages.clear()
        proposal = null
        session.clear()
    }
}

@Composable
internal fun AiDialog(
    chat: AiChatState,
    repo: ShiftRepository,
    today: LocalDate,
    onApply: (List<AiChange>) -> AiApplyResult,
    onDismiss: () -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val itemCount = chat.messages.size + (if (chat.busy) 1 else 0)
    LaunchedEffect(itemCount, chat.proposal) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    val submit = {
        if (input.isNotBlank() && !chat.busy) {
            chat.send(input, repo, today)
            input = ""
        }
    }
    CenteredDialog(title = AI_DIALOG_TITLE, onDismiss = onDismiss) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            items(chat.messages) { message -> AiMessageRow(message) }
            if (chat.busy) {
                item { AiThinkingRow() }
            }
        }
        chat.proposal?.let { proposal ->
            AiProposalCard(
                proposal = proposal,
                onConfirm = { chat.confirm(onApply) },
                onDecline = chat::decline
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                maxLines = AI_INPUT_MAX_LINES,
                shape = MaterialTheme.shapes.small,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            IconButton(onClick = { submit() }, enabled = input.isNotBlank() && !chat.busy) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = AI_SEND_LABEL,
                    tint = if (input.isNotBlank() && !chat.busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun AiMessageRow(message: AiMessage) {
    val scheme = MaterialTheme.colorScheme
    when (message) {
        is AiMessage.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = message.text,
                color = scheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(start = USER_BUBBLE_INDENT_DP.dp)
                    .background(scheme.primaryContainer, MaterialTheme.shapes.medium)
                    .padding(Spacing.sm)
            )
        }
        is AiMessage.Assistant -> Text(
            text = message.text,
            color = scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        is AiMessage.Info -> Text(
            text = message.text,
            color = MaterialTheme.extendedColors.success,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth()
        )
        is AiMessage.Error -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.errorContainer, MaterialTheme.shapes.medium)
                .padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Text(text = message.title, color = scheme.onErrorContainer, style = MaterialTheme.typography.titleSmall)
            message.detail?.let {
                Text(text = it, color = scheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AiThinkingRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        CircularProgressIndicator(modifier = Modifier.size(AI_PROGRESS_SIZE_DP.dp), strokeWidth = 2.dp)
        Text(text = AI_THINKING_TEXT, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AiProposalCard(proposal: AiProposal, onConfirm: () -> Unit, onDecline: () -> Unit) {
    PopupCard {
        Text(text = proposal.summary.ifBlank { AI_PROPOSAL_TITLE }, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        proposal.changes.forEach { change ->
            Text(text = describeAiChange(change), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RoundedButton(
                text = AI_DECLINE_LABEL,
                modifier = Modifier.weight(1f).height(AI_BUTTON_HEIGHT_DP.dp),
                bgColor = MaterialTheme.colorScheme.surfaceContainer,
                textColor = MaterialTheme.colorScheme.onSurface,
                onClick = onDecline
            )
            RoundedButton(
                text = AI_CONFIRM_LABEL,
                modifier = Modifier.weight(1f).height(AI_BUTTON_HEIGHT_DP.dp),
                bgColor = MaterialTheme.colorScheme.primary,
                textColor = MaterialTheme.colorScheme.onPrimary,
                bold = true,
                onClick = onConfirm
            )
        }
    }
}

internal const val AI_DIALOG_TITLE = "AI"
internal const val AI_CONFIRM_LABEL = "Upiši"
internal const val AI_DECLINE_LABEL = "Otkaži"
private const val AI_SEND_LABEL = "Pošalji"
private const val AI_THINKING_TEXT = "Razmišljam…"
private const val AI_PROPOSAL_TITLE = "Izmene"
private const val AI_INPUT_MAX_LINES = 4
private const val AI_PROGRESS_SIZE_DP = 18
private const val AI_BUTTON_HEIGHT_DP = 48
private const val USER_BUBBLE_INDENT_DP = 40
