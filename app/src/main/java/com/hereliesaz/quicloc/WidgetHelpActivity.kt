package com.hereliesaz.quicloc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shown when the widget is tapped exactly once — the most likely accidental
 * interaction, so it explains the widget rather than firing a location text
 * at anyone.
 *
 * It is the only screen a user reaches without opening the app, so it states
 * outright what each tap count does *and* what has to be configured for that
 * tap count to work, with a button straight into settings.
 */
class WidgetHelpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val whitelistManager = WhitelistManager(this)
        val myNumber = whitelistManager.getMyNumber()
        val starredCount = whitelistManager.getStarredNumbers().size
        val whitelistCount = whitelistManager.getNumbers().size

        setContent {
            QuicLocTheme {
                WidgetHelpUI(
                    myNumber = myNumber,
                    starredCount = starredCount,
                    whitelistCount = whitelistCount,
                    onOpenSettings = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                        finish()
                    },
                    onFinished = { finish() },
                )
            }
        }
    }
}

@Composable
fun WidgetHelpUI(
    myNumber: String,
    starredCount: Int,
    whitelistCount: Int,
    onOpenSettings: () -> Unit,
    onFinished: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Tapping the dimmed area outside the card dismisses, as everywhere
            // else in Android. The card itself doesn't consume the click.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onFinished,
            )
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Text(
                    text = "QuicLoc widget",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "Tap it several times in quick succession — the number of taps " +
                        "decides who gets your location. Each tap buzzes, and must land " +
                        "within about half a second of the last one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                )

                TapExplanation(
                    taps = "1 tap",
                    what = "Shows this screen. Nothing is sent.",
                    requirement = null,
                    ok = null,
                )
                TapExplanation(
                    taps = "2 taps",
                    what = "Parking — texts your location to yourself, tagged #Parking.",
                    requirement = if (myNumber.isBlank())
                        "Set your own phone number in QuicLoc first."
                    else
                        "Goes to $myNumber.",
                    ok = myNumber.isNotBlank(),
                )
                TapExplanation(
                    taps = "3 taps",
                    what = "Safety check — texts your starred contacts, tagged #SafetyCheck.",
                    requirement = if (starredCount == 0)
                        "Star at least one trusted contact in QuicLoc first."
                    else
                        "Goes to $starredCount starred contact${if (starredCount == 1) "" else "s"}.",
                    ok = starredCount > 0,
                )
                TapExplanation(
                    taps = "4 taps",
                    what = "Emergency — texts everyone on your trusted list, tagged #Emergency.",
                    requirement = if (whitelistCount == 0)
                        "Add at least one trusted contact in QuicLoc first."
                    else
                        "Goes to all $whitelistCount trusted contact${if (whitelistCount == 1) "" else "s"}.",
                    ok = whitelistCount > 0,
                )

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Open QuicLoc settings")
                    }
                    Button(
                        onClick = onFinished,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Got it")
                    }
                }
            }
        }
    }
}

/** One tap-count row: what it does, and whether it's actually set up. */
@Composable
private fun TapExplanation(
    taps: String,
    what: String,
    requirement: String?,
    ok: Boolean?,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            text = taps,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(64.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = what,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (requirement != null) {
                Text(
                    text = when (ok) {
                        true -> "✓ $requirement"
                        false -> "✗ $requirement"
                        null -> requirement
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (ok) {
                        true -> MaterialTheme.colorScheme.primary
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
