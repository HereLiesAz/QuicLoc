package com.hereliesaz.quicloc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * List of saved Loc Notice locations. Deletion and the per-row enabled
 * switch mutate [store] directly and re-sync [GeofenceRegistrar] — this
 * screen owns those mutations the same way [LocNoticeEditScreen] owns save,
 * rather than routing them through `MainActivity`'s activity-level state
 * (matching [HistoryScreen]/`DiagnosticsScreen`'s manager-owns-its-screen
 * pattern, not `QuicLocScreen`'s heavier prop-drilled one — this is a
 * separate full-screen navigation, not an inline section).
 *
 * @param contacts The current whitelist, used only to flag when a location's
 *   stored contact tokens no longer resolve (e.g. after a rename) — see the
 *   "N contact(s) not found" line below.
 * @param onEntriesChanged Called after any mutation so the caller can
 *   refresh the count shown on the Config screen's Loc Notice section.
 */
@Composable
fun LocNoticeListScreen(
    modifier: Modifier = Modifier,
    store: GeofenceStore,
    contacts: List<WhitelistManager.ContactEntry>,
    onEdit: (String) -> Unit,
    onAddNew: () -> Unit,
    onBack: () -> Unit,
    onEntriesChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(store.getAll()) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    val resolvedTokens = remember(contacts) { contacts.map { it.displayToken }.toSet() }

    BackHandler(onBack = onBack)

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Loc Notice",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "Places QuicLoc watches in the background. Chosen contacts get a text " +
                        "the moment you arrive or leave — no request needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Button(onClick = onAddNew, modifier = Modifier.fillMaxWidth()) {
            Text("+ Add location")
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No locations yet. Add one to get a text when you arrive or leave.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            if (entries.size >= GeofenceEntry.MAX_GEOFENCES - 10) {
                Text(
                    text = "Approaching the ${GeofenceEntry.MAX_GEOFENCES}-location limit — older locations may need to be removed.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(entries, key = { it.id }) { entry ->
                    val resolvedCount = entry.contactTokens.count { it in resolvedTokens }
                    val missingCount = entry.contactTokens.size - resolvedCount
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = "${entry.radiusMeters.roundToInt()}m · " +
                                            (if (entry.notifyOnEnter) "arrive " else "") +
                                            (if (entry.notifyOnExit) "leave " else "") +
                                            "· ${entry.contactTokens.size} contact${if (entry.contactTokens.size == 1) "" else "s"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onEdit(entry.id) }) { Text("Edit") }
                                Switch(
                                    checked = entry.enabled,
                                    onCheckedChange = { newState ->
                                        store.setEnabled(entry.id, newState)
                                        // A missed opposite transition while paused would
                                        // otherwise permanently wedge future alerts (a
                                        // stale ENTER makes the next real ENTER read as a
                                        // duplicate) -- clearing on every enable/disable
                                        // keeps that state honest either direction.
                                        GeofenceStateStore.clear(context, entry.id)
                                        GeofenceRegistrar.sync(context)
                                        entries = store.getAll()
                                        onEntriesChanged()
                                    }
                                )
                                IconButton(onClick = { confirmDeleteId = entry.id }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete ${entry.name}")
                                }
                            }
                            if (missingCount > 0) {
                                Text(
                                    text = "⚠ $missingCount contact${if (missingCount == 1) "" else "s"} no longer found — check names in Emergency contacts.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val toDelete = entries.firstOrNull { it.id == confirmDeleteId }
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Delete \"${toDelete.name}\"?") },
            text = { Text("This location will no longer notify anyone.") },
            confirmButton = {
                Button(
                    onClick = {
                        store.remove(toDelete.id)
                        GeofenceStateStore.clear(context, toDelete.id)
                        GeofenceRegistrar.sync(context)
                        entries = store.getAll()
                        confirmDeleteId = null
                        onEntriesChanged()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Add/edit a Loc Notice location. The address→Maps and Maps→coordinates
 * handoff (see [MapsHandoff]) means this screen never calls a Maps/Places
 * API itself — it only writes/reads the clipboard and launches an intent.
 *
 * @param onBeginSystemFlow Called immediately before launching Maps, so the
 *   activity knows to skip its re-lock-on-pause path (see
 *   `MainActivity.beginSystemFlow`) — without it, leaving to Maps and coming
 *   back triggers a re-authentication that discards every `remember`ed field
 *   in this screen, silently wiping whatever the user had typed.
 */
@Composable
fun LocNoticeEditScreen(
    modifier: Modifier = Modifier,
    store: GeofenceStore,
    contacts: List<WhitelistManager.ContactEntry>,
    entryId: String?,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
    onBeginSystemFlow: () -> Unit = {},
) {
    val context = LocalContext.current
    val existing = remember(entryId) { entryId?.let { store.get(it) } }

    // Name-only whitelist entries (added by typing a handle with no phone
    // number) have nothing to text -- offering them here would let a
    // location "save" successfully while being unable to ever notify
    // anyone, silently. Matches WhitelistManager.getDialableNumbers()'s
    // existing exclusion for the same reason.
    val dialableContacts = remember(contacts) { contacts.filter { it.number.isNotEmpty() } }
    val nameOnlyCount = contacts.size - dialableContacts.size

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var pinned by remember {
        mutableStateOf(existing?.let { it.latitude to it.longitude })
    }
    var pasteError by remember { mutableStateOf<String?>(null) }
    var radius by remember { mutableStateOf(existing?.radiusMeters ?: GeofenceEntry.DEFAULT_RADIUS_M) }
    var notifyEnter by remember { mutableStateOf(existing?.notifyOnEnter ?: true) }
    var notifyExit by remember { mutableStateOf(existing?.notifyOnExit ?: true) }
    var selectedTokens by remember { mutableStateOf(existing?.contactTokens ?: emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name (e.g. \"Home\")") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Pinpoint the location",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "Type the address, open it in Maps to confirm the spot, then copy its " +
                "coordinates in Maps and paste them back here. QuicLoc never talks to a maps " +
                "service directly — this is just a clipboard handoff to the Maps app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            Button(
                onClick = {
                    onBeginSystemFlow()
                    MapsHandoff.openInMaps(context, address)
                },
                enabled = address.isNotBlank(),
            ) { Text("Open in Maps") }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                val result = MapsHandoff.pasteFromClipboard(context)
                if (result == null) {
                    pasteError = "That doesn't look like coordinates — expected something like " +
                        "37.4221, -122.0848. In Maps, long-press the pin, then tap the " +
                        "coordinates to copy them, then come back and try again."
                    // Deliberately NOT clearing `pinned` here -- a bad clipboard
                    // read (stale content, wrong locale decimal separator, a
                    // copy-paste of something unrelated) shouldn't destroy an
                    // already-valid pin with no way back short of redoing the
                    // whole Maps trip from memory.
                } else {
                    pasteError = null
                    pinned = result
                }
            }) { Text("Paste location") }
        }
        pinned?.let { (lat, lng) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Pinned: ${"%.4f".format(java.util.Locale.US, lat)}, ${"%.4f".format(java.util.Locale.US, lng)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp).weight(1f),
                )
                TextButton(onClick = {
                    onBeginSystemFlow()
                    MapsHandoff.viewCoordinatesInMaps(context, lat, lng)
                }) { Text("View in Maps") }
            }
        }
        pasteError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Radius: ${radius.roundToInt()}m", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Smaller radii can misfire from GPS drift — ${GeofenceEntry.MIN_RADIUS_M.roundToInt()}m minimum.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = radius,
            onValueChange = { radius = it },
            valueRange = GeofenceEntry.MIN_RADIUS_M..GeofenceEntry.MAX_RADIUS_M,
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Notify when I arrive", modifier = Modifier.weight(1f))
            Switch(checked = notifyEnter, onCheckedChange = { notifyEnter = it })
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Notify when I leave", modifier = Modifier.weight(1f))
            Switch(checked = notifyExit, onCheckedChange = { notifyExit = it })
        }
        if (!notifyEnter && !notifyExit) {
            Text(
                text = "Turn on at least one, or this location won't do anything.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Who gets told", style = MaterialTheme.typography.titleSmall)
        if (dialableContacts.isEmpty()) {
            Text(
                text = "No trusted contacts with a phone number yet — add one in Emergency " +
                    "contacts first (a name typed with no number can't be texted).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (contact in dialableContacts) {
                val token = contact.displayToken
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = token in selectedTokens,
                        onCheckedChange = { checked ->
                            selectedTokens = if (checked) selectedTokens + token else selectedTokens - token
                        }
                    )
                    Column {
                        Text(text = token, style = MaterialTheme.typography.bodyMedium)
                        if (contact.number.isNotEmpty() && contact.number != token) {
                            Text(
                                text = contact.number,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (nameOnlyCount > 0) {
                Text(
                    text = "$nameOnlyCount contact${if (nameOnlyCount == 1) "" else "s"} not shown — no phone number on file.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        validationError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Button(
            onClick = {
                val coords = pinned
                validationError = when {
                    name.isBlank() -> "Give this location a name."
                    coords == null -> "Pin a location first — paste coordinates copied from Maps."
                    !notifyEnter && !notifyExit -> "Turn on at least one of arrive/leave."
                    selectedTokens.isEmpty() -> "Pick at least one contact to notify."
                    else -> null
                }
                if (validationError != null) return@Button

                val entry = GeofenceEntry(
                    id = existing?.id ?: "",
                    name = name.trim(),
                    latitude = coords!!.first,
                    longitude = coords.second,
                    radiusMeters = radius,
                    notifyOnEnter = notifyEnter,
                    notifyOnExit = notifyExit,
                    enabled = existing?.enabled ?: true,
                    contactTokens = selectedTokens,
                    address = address.trim(),
                )
                if (existing == null) store.add(entry) else store.update(entry.copy(id = existing.id))
                GeofenceRegistrar.sync(context)
                onSaved()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }

        if (existing != null) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Delete this location", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete \"${existing.name}\"?") },
            text = { Text("This location will no longer notify anyone.") },
            confirmButton = {
                Button(
                    onClick = {
                        store.remove(existing.id)
                        GeofenceStateStore.clear(context, existing.id)
                        GeofenceRegistrar.sync(context)
                        showDeleteConfirm = false
                        onDeleted()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
