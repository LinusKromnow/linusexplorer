package io.github.linuskromnow.linusexplorer.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.linuskromnow.linusexplorer.R
import kotlinx.coroutines.delay

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 12

@Composable
fun LockScreen(
    state: AppLockUiState,
    onSubmit: (String) -> Unit,
    onInput: () -> Unit,
    onBiometric: (() -> Unit)?,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.wrongPin) { if (state.wrongPin) pin = "" }
    LaunchedEffect(Unit) { onBiometric?.invoke() }
    val now by produceState(System.currentTimeMillis(), state.lockedUntil) {
        while (value < state.lockedUntil) {
            delay(500)
            value = System.currentTimeMillis()
        }
    }
    val waitSeconds = ((state.lockedUntil - now + 999) / 1000).coerceAtLeast(0)
    val inputEnabled = waitSeconds == 0L && !state.checking

    fun type(digit: Char) {
        if (pin.length < MAX_PIN_LENGTH) pin += digit
        onInput()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                waitSeconds > 0 -> stringResource(R.string.lock_wait, waitSeconds)
                state.wrongPin -> stringResource(R.string.lock_wrong_pin)
                else -> stringResource(R.string.lock_enter_pin)
            },
            color = if (state.wrongPin || waitSeconds > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(16.dp)) {
            repeat(pin.length) {
                Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
        Spacer(Modifier.height(32.dp))
        val rows = listOf("123", "456", "789")
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { digit -> KeypadButton(enabled = inputEnabled, onClick = { type(digit) }) { Text("$digit", style = MaterialTheme.typography.headlineMedium) } }
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            KeypadButton(enabled = pin.isNotEmpty(), onClick = { pin = pin.dropLast(1) }) {
                KeypadIcon(Icons.AutoMirrored.Filled.Backspace, stringResource(R.string.lock_delete_digit))
            }
            KeypadButton(enabled = inputEnabled, onClick = { type('0') }) { Text("0", style = MaterialTheme.typography.headlineMedium) }
            KeypadButton(enabled = inputEnabled && pin.length >= MIN_PIN_LENGTH, onClick = { onSubmit(pin) }) {
                KeypadIcon(Icons.Default.Check, stringResource(R.string.action_unlock))
            }
        }
        if (onBiometric != null) {
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onBiometric) {
                Icon(Icons.Default.Fingerprint, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.lock_use_biometric))
            }
        }
    }
}

@Composable
private fun KeypadButton(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(72.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun KeypadIcon(icon: ImageVector, description: String) {
    Icon(icon, contentDescription = description)
}
