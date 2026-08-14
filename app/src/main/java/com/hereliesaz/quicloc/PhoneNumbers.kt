package com.hereliesaz.quicloc

/**
 * Shared strict phone-number comparison, used anywhere QuicLoc decides whether
 * two numbers refer to the same person (whitelist matching, cross-intake
 * dedupe).
 *
 * We deliberately do NOT use `android.telephony.PhoneNumberUtils.compare`.
 * Besides treating two blank strings as equal, it falls back to a loose match
 * on the trailing ~7 digits when a strict match fails — which lets two
 * *different* real phone numbers that merely share an area-code-sized suffix
 * match each other. For a whitelist gate, that's a bypass: an unrelated
 * number could be treated as a trusted contact. We instead require an exact
 * digit match, tolerating only a missing/extra leading US "+1" trunk code.
 */
object PhoneNumbers {

    /** Strips everything except digits and a leading '+'. */
    fun clean(number: String): String = number.replace(Regex("[^0-9+]"), "")

    /**
     * True if [rawA] and [rawB] are the same number. Both are cleaned first;
     * either being empty after cleaning is never a match (an empty string
     * must never match another empty string here — that would let a
     * name-only whitelist entry, whose number is "", match any sender whose
     * "number" also cleans to empty).
     */
    fun match(rawA: String, rawB: String): Boolean {
        val cleanA = clean(rawA).removePrefix("+")
        val cleanB = clean(rawB).removePrefix("+")
        if (cleanA.isEmpty() || cleanB.isEmpty()) return false
        if (cleanA == cleanB) return true
        val shorter = if (cleanA.length <= cleanB.length) cleanA else cleanB
        val longer = if (cleanA.length <= cleanB.length) cleanB else cleanA
        return shorter.length >= 10 && longer.length == shorter.length + 1 &&
            longer.startsWith("1") && longer.substring(1) == shorter
    }
}
