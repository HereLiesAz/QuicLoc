package com.hereliesaz.quicloc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The two-way handoff Loc Notice uses to let a user define a place without
 * QuicLoc ever calling a Maps/Places/Geocoding API — no API key, no billing.
 *
 * Forward: the user types a free-text address in-app; [openInMaps] copies it
 * to the clipboard and opens Google Maps (or the system chooser if no maps
 * app is resolvable) pre-searched for it, so the user can visually confirm
 * or refine the pin themselves.
 *
 * Backward: the user locates/drops a pin in Maps, uses Maps' own "copy
 * coordinates" share action, switches back to QuicLoc, and taps "Paste
 * location" — [pasteFromClipboard] reads the clipboard and hands the text to
 * [CoordinateParser].
 *
 * The backward step is a manual button tap, not an automatic read-on-resume:
 * since Android 10, only the foreground app can read the clipboard, and
 * QuicLoc *is* foreground again once the user switches back — so a manual
 * tap works reliably. An auto-detect alternative can't distinguish "just
 * copied from Maps" from unrelated clipboard content anyway, so it would
 * need the same parse-and-gate logic regardless — the explicit button is
 * simpler and no less capable, not a tradeoff.
 */
object MapsHandoff {

    fun openInMaps(context: Context, address: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("QuicLoc address", address))

        val uri = Uri.parse("geo:0,0?q=${Uri.encode(address)}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            context.startActivity(Intent.createChooser(intent, "Open address in a maps app"))
        }
    }

    /** @return the parsed (lat, lng) pair from the clipboard, or null if it doesn't look like one. */
    fun pasteFromClipboard(context: Context): Pair<Double, Double>? {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        return CoordinateParser.parse(text)
    }

    /**
     * Opens Maps centered on an already-pinned coordinate — lets the user
     * verify or re-derive a saved location (e.g. after a bad paste, or just
     * to sanity-check it looks right) without redoing the address search.
     */
    fun viewCoordinatesInMaps(context: Context, latitude: Double, longitude: Double) {
        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            context.startActivity(Intent.createChooser(intent, "Open location in a maps app"))
        }
    }
}
