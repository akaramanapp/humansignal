package io.humansignal.research.tasks

import kotlin.random.Random

/** Sahte değerler: görevlerde gerçek PIN/IBAN asla istenmez (§3.6.1, §8). */
object Fake {
    /** 4–6 haneli sahte PIN; tüm haneleri aynı olmayan. */
    fun pin(r: Random): String {
        while (true) {
            val len = 4 + r.nextInt(3)
            val p = (1..len).map { r.nextInt(10) }.joinToString("")
            if (p.toSet().size > 1) return p
        }
    }

    /** Sahte TR IBAN (biçim doğru, kontrol hanesi rastgele): "TR12 3456 …" */
    fun iban(r: Random): String {
        val digits = "TR" + (1..24).map { r.nextInt(10) }.joinToString("")
        return digits.chunked(4).joinToString(" ")
    }

    fun amount(r: Random): String = "${10 + r.nextInt(4990)},${listOf("00", "50", "25", "90")[r.nextInt(4)]}"

    val descriptions = listOf("Kira Kasım", "Market alışverişi", "Elektrik faturası", "Doğum günü hediyesi", "Borç ödemesi", "Aidat")
    val recipients = listOf("Ayşe Yılmaz", "Mehmet Demir", "Zeynep Kaya", "Can Öztürk")

    /** Karşılaştırma için: boşluk/nokta yok, küçük harf, Türkçe karakterler katlanır (ç→c, ı→i …). */
    fun norm(s: String): String = s.replace(" ", "").replace(".", "")
        .replace('İ', 'i').replace('I', 'i').lowercase()
        .map { TR_FOLD[it] ?: it }.joinToString("")

    private val TR_FOLD = mapOf('ç' to 'c', 'ğ' to 'g', 'ı' to 'i', 'ö' to 'o', 'ş' to 's', 'ü' to 'u')
}
