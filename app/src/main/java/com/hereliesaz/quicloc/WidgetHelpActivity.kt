package com.hereliesaz.quicloc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat

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
        // Dialable counts, not raw entry counts: a name-only whitelist entry
        // (no phone number) can't actually receive an SMS, so counting it
        // here would show a green checkmark for a tap that sends nothing —
        // see LocationHelper.handleWidgetTaps, which already only texts
        // dialable numbers.
        val starredCount = whitelistManager.getDialableStarredNumbers().size
        val whitelistCount = whitelistManager.getDialableNumbers().size
        val hasSendSms = ContextCompat.checkSelfPermission(
            this, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        val hasLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val appEnabled = AppSettings.isEnabled(this)

        setContent {
            QuicLocTheme {
                WidgetHelpUI(
                    myNumber = myNumber,
                    starredCount = starredCount,
                    whitelistCount = whitelistCount,
                    hasSendSms = hasSendSms,
                    hasLocation = hasLocation,
                    appEnabled = appEnabled,
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
    hasSendSms: Boolean = true,
    hasLocation: Boolean = true,
    appEnabled: Boolean = true,
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

                // Prerequisites shared by every tap count that actually sends
                // (2, 3, 4): the master switch, SEND_SMS, and location. Any
                // one of these missing means that tap does nothing, no matter
                // how the whitelist/starred/my-number counts look.
                fun sharedBlocker(): String? = when {
                    !appEnabled -> "QuicLoc is turned off — turn it on in settings first."
                    !hasSendSms -> "QuicLoc needs SMS permission first."
                    !hasLocation -> "QuicLoc needs location permission first."
                    else -> null
                }

                TapExplanation(
                    taps = "1 tap",
                    what = "Shows this screen. Nothing is sent.",
                    requirement = null,
                    ok = null,
                )
                TapExplanation(
                    taps = "2 taps",
                    what = "Parking — texts your location to yourself, tagged #Parking.",
                    requirement = sharedBlocker() ?: if (myNumber.isBlank())
                        "Set your own phone number in QuicLoc first."
                    else
                        "Goes to $myNumber.",
                    ok = sharedBlocker() == null && myNumber.isNotBlank(),
                )
                TapExplanation(
                    taps = "3 taps",
                    what = "Safety check — texts your starred contacts, tagged #SafetyCheck.",
                    requirement = sharedBlocker() ?: if (starredCount == 0)
                        "Star at least one trusted contact with a phone number in QuicLoc first."
                    else
                        "Goes to $starredCount starred contact${if (starredCount == 1) "" else "s"}.",
                    ok = sharedBlocker() == null && starredCount > 0,
                )
                TapExplanation(
                    taps = "4 taps",
                    what = "Emergency — texts everyone on your trusted list, tagged #Emergency.",
                    requirement = sharedBlocker() ?: if (whitelistCount == 0)
                        "Add at least one trusted contact with a phone number in QuicLoc first."
                    else
                        "Goes to all $whitelistCount trusted contact${if (whitelistCount == 1) "" else "s"}.",
                    ok = sharedBlocker() == null && whitelistCount > 0,
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
