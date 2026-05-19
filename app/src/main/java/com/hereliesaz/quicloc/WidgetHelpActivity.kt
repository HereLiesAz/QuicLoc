package com.hereliesaz.quicloc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Transparent full-screen activity shown when the widget is tapped exactly
 * once. The user taps anywhere on screen to advance through three hint
 * messages, then the activity finishes itself.
 *
 * Triggered (instead of an actual SMS) because a single accidental tap on
 * the widget is the most likely-to-happen widget interaction, and spamming
 * the user's own number with `#Parking` every time they brush past the
 * widget would be a worse default than showing a one-time hint.
 */
class WidgetHelpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WidgetHelpUI(onFinished = { finish() })
            }
        }
    }
}

@Composable
fun WidgetHelpUI(onFinished: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val messages = listOf(
        "Tap the QuicLoc widget twice to mark your parking spot.",
        "Tap the QuicLoc widget three times to send a safety check to your starred whitelisters.",
        "Tap it four times to send an emergency location to everyone on your whitelist."
    )

    if (step < messages.size) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    step++
                },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Text(
                    text = messages[step],
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        LaunchedEffect(Unit) {
            onFinished()
        }
    }
}
