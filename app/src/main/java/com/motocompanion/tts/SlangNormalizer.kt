package com.motocompanion.tts

import java.util.regex.Pattern

object SlangNormalizer {

    // Kamus singkatan chat bahasa Indonesia untuk dibaca TTS agar intonasi alami
    private val slangDictionary = mapOf(
        "otw" to "sedang dalam perjalanan",
        "dmn" to "di mana",
        "dijalan" to "di jalan",
        "bwt" to "buat",
        "yg" to "yang",
        "sy" to "saya",
        "km" to "kamu",
        "kbrin" to "kabari",
        "bntr" to "sebentar",
        "bs" to "bisa",
        "sdh" to "sudah",
        "udah" to "sudah",
        "udh" to "sudah",
        "blm" to "belum",
        "jgn" to "jangan",
        "gk" to "tidak",
        "gak" to "tidak",
        "ga" to "tidak",
        "nggak" to "tidak",
        "mks" to "terima kasih",
        "mksh" to "terima kasih",
        "makasih" to "terima kasih",
        "thx" to "terima kasih",
        "bgt" to "banget",
        "knp" to "kenapa",
        "lg" to "lagi",
        "sm" to "sama",
        "dl" to "dulu",
        "dpt" to "dapat",
        "tau" to "tahu",
        "tp" to "tapi",
        "tpi" to "tapi",
        "klo" to "kalau",
        "kalo" to "kalau",
        "brp" to "berapa",
        "gmn" to "gimana",
        "bgmn" to "bagaimana",
        "pls" to "tolong",
        "tlng" to "tolong",
        "tlg" to "tolong",
        "asap" to "secepatnya",
        "nnti" to "nanti",
        "nnt" to "nanti",
        "skrg" to "sekarang",
        "skr" to "sekarang",
        "bener" to "benar",
        "bnr" to "benar",
        "ampe" to "sampai",
        "sampe" to "sampai",
        "smpe" to "sampai",
        "wkwk" to "tertawa",
        "wkwkwk" to "tertawa",
        "haha" to "tertawa",
        "hahaha" to "tertawa"
    )

    private val urlPattern = Pattern.compile("https?://\\S+\\b")
    private val emojiPattern = Pattern.compile("[\\p{So}\\p{Cn}]")
    private val multiPunctuation = Pattern.compile("([!?.,])\\1+")

    /**
     * Menormalisasi teks pesan WhatsApp agar siap dibacakan dengan artikulasi jelas
     */
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""

        // 1. Hilangkan link / URL
        var cleaned = urlPattern.matcher(text).replaceAll(" tautan ")

        // 2. Hilangkan simbol emoji dan karakter khusus visual
        cleaned = emojiPattern.matcher(cleaned).replaceAll(" ")

        // 3. Rapikan tanda baca berlebih (misal: "???" jadi "?")
        cleaned = multiPunctuation.matcher(cleaned).replaceAll("$1")

        // 4. Pisahkan per kata dan lakukan penggantian singkatan
        val words = cleaned.split("\\s+".toRegex())
        val sb = StringBuilder()

        for (word in words) {
            val lower = word.lowercase().trim()
            val cleanWord = lower.replace("[^a-zA-Z0-9]".toRegex(), "")
            val replacement = slangDictionary[cleanWord]

            if (replacement != null) {
                sb.append(replacement).append(" ")
            } else {
                sb.append(word).append(" ")
            }
        }

        return sb.toString().trim()
    }
}
