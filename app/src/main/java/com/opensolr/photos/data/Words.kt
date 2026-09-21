package com.opensolr.photos.data

import java.text.Normalizer

object Words {

    private val SPECIAL = mapOf(
        'ß' to "ss", 'æ' to "ae", 'Æ' to "ae", 'œ' to "oe", 'Œ' to "oe", 'ø' to "o", 'Ø' to "o",
        'đ' to "d", 'Đ' to "d", 'ł' to "l", 'Ł' to "l", 'þ' to "th", 'Þ' to "th", 'ð' to "d", 'Ð' to "d",
        'ı' to "i", 'ħ' to "h", 'Ħ' to "h", 'ŧ' to "t", 'Ŧ' to "t",
    )

    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val out = StringBuilder(decomposed.length)
        for (c in decomposed) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            val special = SPECIAL[c]
            if (special != null) out.append(special) else out.append(c)
        }
        return tidy(out.toString()).lowercase()
    }

    private val BLANKS = Regex("\\s+")

    fun tidy(text: String): String {
        val trimmed = text.trim()

        var collapse = false
        for (i in trimmed.indices) {
            val c = trimmed[i]
            if (c.isWhitespace() && (c != ' ' || (i > 0 && trimmed[i - 1] == ' '))) {
                collapse = true
                break
            }
        }
        return if (collapse) trimmed.replace(BLANKS, " ") else trimmed
    }
}

fun List<String>.distinctWords(): List<String> =
    map { Words.tidy(it) }.filter { it.isNotEmpty() }.distinctBy { Words.fold(it) }
