package com.example.signage_front.ui.composables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.signage_front.data.DeviceDisplay
import com.example.signage_front.data.LanguageManager
import kotlinx.coroutines.delay

/** Close the dialog after this long without any interaction. */
private const val AUTO_CLOSE_MS = 30_000L

/**
 * Small centered modal with the device settings: UI language, screen brightness
 * and a soft "turn off" (idle) action. Any interaction resets the auto-close
 * timer; leaving it untouched for 30s dismisses it.
 */
@Composable
fun SettingsDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onTurnOff: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    val context = LocalContext.current
    val language by LanguageManager.language.collectAsState()
    val brightness by DeviceDisplay.brightness.collectAsState()

    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lastInteraction) {
        delay(AUTO_CLOSE_MS)
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier
                .width(380.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            lastInteraction = System.currentTimeMillis()
                        }
                    }
                },
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = LanguageManager.t(language, "Beállítások", "Settings", "Einstellungen"),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                // Language selection
                Text(
                    text = LanguageManager.t(language, "Nyelv", "Language", "Sprache"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Column {
                    LanguageManager.supported.forEach { code ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { LanguageManager.set(context, code) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = language == code,
                                onClick = { LanguageManager.set(context, code) }
                            )
                            Text(
                                text = LanguageManager.displayName(code),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }

                // Brightness
                Text(
                    text = LanguageManager.t(language, "Fényerő", "Brightness", "Helligkeit"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Brightness6,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Slider(
                        value = brightness,
                        onValueChange = { DeviceDisplay.setBrightness(context, it) },
                        valueRange = 0.05f..1f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    )
                }

                // Turn off (idle)
                Button(
                    onClick = onTurnOff,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(LanguageManager.t(language, "Kikapcsolás", "Turn off", "Ausschalten"))
                }
            }
        }
    }
}
