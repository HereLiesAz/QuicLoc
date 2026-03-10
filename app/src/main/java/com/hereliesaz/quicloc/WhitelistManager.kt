package com.hereliesaz.quicloc

import android.content.Context
import android.content.SharedPreferences
import android.telephony.PhoneNumberUtils

class WhitelistManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("quicloc_prefs", Context.MODE_PRIVATE)

    fun addNumber(number: String) {
        val cleanNumber = cleanPhoneNumber(number)
        if (cleanNumber.isNotEmpty()) {
            val numbers = getNumbers().toMutableSet()
            numbers.add(cleanNumber)
            prefs.edit().putStringSet("whitelist", numbers).apply()
        }
    }

    fun removeNumber(number: String) {
        val numbers = getNumbers().toMutableSet()
        numbers.remove(number)
        prefs.edit().putStringSet("whitelist", numbers).apply()
    }

    fun getNumbers(): Set<String> {
        return prefs.getStringSet("whitelist", emptySet()) ?: emptySet()
    }

    fun isWhitelisted(number: String): Boolean {
        val cleanIncoming = cleanPhoneNumber(number)
        return getNumbers().any { whitelisted ->
            PhoneNumberUtils.compare(cleanIncoming, whitelisted)
        }
    }

    private fun cleanPhoneNumber(number: String): String {
        return number.replace(Regex("[^0-9+]"), "")
    }
}
