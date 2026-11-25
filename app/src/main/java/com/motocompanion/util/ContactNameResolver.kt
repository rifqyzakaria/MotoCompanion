package com.motocompanion.util

import android.Manifest
import android.app.Notification
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import com.motocompanion.R

/**
 * Resolver cerdas untuk memastikan panggilan masuk WhatsApp selalu terbaca sebagai
 * NAMA KONTAK (bukan nomor telepon mentah) saat terhubung ke Headunit motor.
 */
object ContactNameResolver {

    private const val TAG = "ContactNameResolver"

    // Pola karakter kontrol arah teks tak terlihat (LTR/RTL marks) yang sering disisipkan WhatsApp pada nomor telepon
    private val unicodeFormatMarks = Regex("[\\p{Cf}\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")

    private val boilerplatePhrases = listOf(
        "panggilan suara whatsapp",
        "panggilan video whatsapp",
        "panggilan suara masuk",
        "panggilan video masuk",
        "panggilan grup masuk",
        "panggilan masuk",
        "panggilan suara",
        "panggilan video",
        "whatsapp voice call",
        "whatsapp video call",
        "incoming voice call",
        "incoming video call",
        "incoming group call",
        "incoming call",
        "voice call",
        "video call",
        "whatsapp business",
        "whatsapp"
    )

    /**
     * Mengekstrak dan mencocokkan nama kontak asli penelepon WhatsApp dari StatusBarNotification & buku telepon HP.
     */
    fun resolveWhatsAppCallerName(context: Context, sbn: StatusBarNotification): String {
        val notification = sbn.notification ?: return context.getString(R.string.unknown_caller)
        val extras = notification.extras

        val rawCandidates = mutableListOf<String>()
        val phoneNumbersToLookup = linkedSetOf<String>()
        val contactUrisToLookup = mutableListOf<Uri>()

        // 1. Ekstrak dari Notification.EXTRA_CALL_PERSON (Android 12+ CallStyle) & EXTRA_PEOPLE_LIST (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && extras != null) {
            @Suppress("DEPRECATION")
            val callPerson: Person? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable("android.callPerson", Person::class.java)
            } else {
                extras.getParcelable("android.callPerson")
            }
            if (callPerson != null) {
                callPerson.name?.toString()?.let { rawCandidates.add(it) }
                extractPersonUriInfo(callPerson.uri, contactUrisToLookup, phoneNumbersToLookup)
            }

            @Suppress("DEPRECATION")
            val peopleList: ArrayList<Person>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)
            } else {
                extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST)
            }
            peopleList?.forEach { person ->
                person.name?.toString()?.let { rawCandidates.add(it) }
                extractPersonUriInfo(person.uri, contactUrisToLookup, phoneNumbersToLookup)
            }
        }

        // 2. Ekstrak JID nomor telepon dari shortcutId atau tag notifikasi WhatsApp (format: "6281234567890@s.whatsapp.net")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            extractWhatsAppJidNumber(notification.shortcutId)?.let { phoneNumbersToLookup.add(it) }
        }
        extractWhatsAppJidNumber(sbn.tag)?.let { phoneNumbersToLookup.add(it) }

        // 3. Ekstrak teks dari seluruh field standar notifikasi
        if (extras != null) {
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.let { rawCandidates.add(it) }
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let { rawCandidates.add(it) }
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.let { rawCandidates.add(it) }
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.let { rawCandidates.add(it) }
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { rawCandidates.add(it) }
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let { rawCandidates.add(it) }

            @Suppress("DEPRECATION")
            val legacyPeople = extras.getStringArray(Notification.EXTRA_PEOPLE)
            legacyPeople?.forEach { uriStr ->
                extractPersonUriInfo(uriStr, contactUrisToLookup, phoneNumbersToLookup)
            }
        }

        val cleanedTextNames = mutableListOf<String>()

        for (raw in rawCandidates) {
            val cleaned = cleanCandidateText(raw)
            if (cleaned.isBlank()) continue

            if (looksLikePhoneNumber(cleaned)) {
                normalizeDigitsToLookup(cleaned)?.let { phoneNumbersToLookup.add(it) }
            } else {
                // Cek apakah gabungan nama + nomor atau murni nama kontak
                cleanedTextNames.add(cleaned)
            }
        }

        // 4. PRIORITAS TERTINGGI: Cari nama asli yang tersimpan di Kontak HP (ContactsContract)
        if (hasReadContactsPermission(context)) {
            // 4a. Cek lookup URI langsung jika tersedia dari Person
            for (contactUri in contactUrisToLookup) {
                val resolved = queryDisplayNameFromContactUri(context, contactUri)
                if (!resolved.isNullOrBlank() && !looksLikePhoneNumber(resolved)) {
                    Log.i(TAG, "Nama kontak ditemukan via Contact URI: $resolved")
                    return resolved
                }
            }

            // 4b. Cek semua kandidat nomor telepon (JID, tel:, maupun title berupa nomor) ke buku telepon HP
            for (phone in phoneNumbersToLookup) {
                val resolved = lookupContactNameByNumber(context, phone)
                if (!resolved.isNullOrBlank() && !looksLikePhoneNumber(resolved)) {
                    Log.i(TAG, "Nomor WA berhasil dicocokkan ke Nama Kontak HP: $resolved")
                    return resolved
                }
            }
        }

        // 5. PRIORITAS KEDUA: Gunakan nama tampilan dari notifikasi WhatsApp (bukan nomor telepon!)
        for (name in cleanedTextNames) {
            val withoutTilde = name.removePrefix("~").trim()
            if (withoutTilde.isNotBlank() && !looksLikePhoneNumber(withoutTilde)) {
                return withoutTilde
            }
        }

        // 6. JANGAN PERNAH mengeja deretan angka nomor telepon di intercom headunit
        return context.getString(R.string.unknown_caller)
    }

    fun hasReadContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun extractPersonUriInfo(
        uriString: String?,
        contactUris: MutableList<Uri>,
        phoneNumbers: MutableSet<String>
    ) {
        if (uriString.isNullOrBlank()) return
        try {
            when {
                uriString.startsWith("tel:", ignoreCase = true) -> {
                    val num = Uri.decode(uriString.substring(4))
                    normalizeDigitsToLookup(num)?.let { phoneNumbers.add(it) }
                }
                uriString.startsWith("content://com.android.contacts") -> {
                    contactUris.add(Uri.parse(uriString))
                }
            }
        } catch (_: Exception) {}
    }

    private fun extractWhatsAppJidNumber(jidOrTag: String?): String? {
        if (jidOrTag.isNullOrBlank()) return null
        // Contoh: "6281234567890@s.whatsapp.net" -> "+6281234567890"
        val jidMatch = Regex("(\\d{7,16})@s\\.whatsapp\\.net").find(jidOrTag)
        if (jidMatch != null) {
            return "+${jidMatch.groupValues[1]}"
        }
        return null
    }

    private fun cleanCandidateText(raw: String): String {
        var text = unicodeFormatMarks.replace(raw, "").trim()
        for (phrase in boilerplatePhrases) {
            if (text.equals(phrase, ignoreCase = true)) {
                return ""
            }
        }
        // Bersihkan keterangan status panggilan jika menempel dengan tanda pemisah
        for (phrase in boilerplatePhrases) {
            if (phrase != "whatsapp" && text.lowercase().contains(phrase)) {
                text = text.replace(Regex("(?i)${Regex.escape(phrase)}"), "").trim()
            }
        }
        text = text.trim(' ', '-', '•', ':', '|', ',', '(', ')')
        if (text.equals("whatsapp", ignoreCase = true) || text.equals("whatsapp business", ignoreCase = true)) {
            return ""
        }
        return text
    }

    fun looksLikePhoneNumber(text: String): Boolean {
        val stripped = unicodeFormatMarks.replace(text, "")
            .replace("@s.whatsapp.net", "", ignoreCase = true)
            .trim()
        if (stripped.isEmpty()) return false

        // Hitung jumlah huruf alfabet vs angka
        val letterCount = stripped.count { it.isLetter() }
        val digitCount = stripped.count { it.isDigit() }
        if (letterCount > 0) return false
        return digitCount >= 6
    }

    private fun normalizeDigitsToLookup(raw: String): String? {
        val cleaned = unicodeFormatMarks.replace(raw, "").trim()
        val hasPlus = cleaned.startsWith("+")
        val digits = cleaned.filter { it.isDigit() }
        if (digits.length < 6) return null
        return if (hasPlus) "+$digits" else digits
    }

    private fun queryDisplayNameFromContactUri(context: Context, uri: Uri): String? {
        try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    if (idx != -1) {
                        return cursor.getString(idx)?.trim()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal membaca Contact URI: ${e.message}")
        }
        return null
    }

    /**
     * Mencari nama kontak di buku telepon menggunakan variasi format internasional (+62), kode negara (62),
     * lokal (08), serta pencocokan akhiran 9 digit terakhir agar 100% akurat.
     */
    fun lookupContactNameByNumber(context: Context, rawNumber: String): String? {
        val digitsOnly = rawNumber.filter { it.isDigit() }
        if (digitsOnly.length < 6) return null

        val variants = linkedSetOf<String>()
        variants.add(rawNumber)
        variants.add("+$digitsOnly")
        if (digitsOnly.startsWith("62") && digitsOnly.length > 5) {
            variants.add("0${digitsOnly.substring(2)}")
        } else if (digitsOnly.startsWith("0") && digitsOnly.length > 5) {
            variants.add("+62${digitsOnly.substring(1)}")
            variants.add("62${digitsOnly.substring(1)}")
        }

        // Tahap 1: PhoneLookup cepat bawaan Android
        for (variant in variants) {
            try {
                val lookupUri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(variant)
                )
                context.contentResolver.query(
                    lookupUri,
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                        if (nameIdx != -1) {
                            val name = cursor.getString(nameIdx)?.trim()
                            if (!name.isNullOrBlank() && !looksLikePhoneNumber(name)) {
                                return name
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "PhoneLookup gagal untuk $variant: ${e.message}")
            }
        }

        // Tahap 2: Pencocokan akhiran 8-9 digit terakhir pada CommonDataKinds.Phone (jika format spasi/tanda hubung unik)
        val suffixLen = minOf(9, digitsOnly.length)
        val suffix = digitsOnly.takeLast(suffixLen)
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                arrayOf("%$suffix%"),
                null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val dbNum = if (numCol != -1) cursor.getString(numCol)?.filter { it.isDigit() } ?: "" else ""
                    if (dbNum.endsWith(suffix)) {
                        val name = if (nameCol != -1) cursor.getString(nameCol)?.trim() else null
                        if (!name.isNullOrBlank() && !looksLikePhoneNumber(name)) {
                            return name
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback suffix lookup gagal: ${e.message}")
        }

        return null
    }
}
