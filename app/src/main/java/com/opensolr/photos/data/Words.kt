package com.opensolr.photos.data

import java.text.Normalizer

/**
 * How tags and names are told apart (Cip, 2026-09-17): two spellings of the same word are the same
 * tag or the same person. "ciprian dimofte" and "Ciprian Dimofte", "Luminița Laura" and
 * "Luminita Laura", "Maria  Popescu" with two spaces - one entry, never two on one photo.
 */
object Words {

    /** Letters Unicode decomposition does not take apart, folded by hand. */
    private val SPECIAL = mapOf(
        'ß' to "ss", 'æ' to "ae", 'Æ' to "ae", 'œ' to "oe", 'Œ' to "oe", 'ø' to "o", 'Ø' to "o",
        'đ' to "d", 'Đ' to "d", 'ł' to "l", 'Ł' to "l", 'þ' to "th", 'Þ' to "th", 'ð' to "d", 'Ð' to "d",
        'ı' to "i", 'ħ' to "h", 'Ħ' to "h", 'ŧ' to "t", 'Ŧ' to "t",
    )

    /**
     * The key two spellings are compared on: Latin folded to ASCII (diacritics removed, the
     * letters above spelled out), all whitespace collapsed to one space, lower case.
     */
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

    /**
     * Runs of blanks, compiled once. Building it inside [tidy] meant a fresh pattern for every
     * word compared - and every keystroke in the tag box compares the whole library's words
     * (Cip, 2026-09-18).
     */
    private val BLANKS = Regex("\\s+")

    /** [text] with its spaces trimmed and collapsed, as it is kept. */
    fun tidy(text: String): String {
        val trimmed = text.trim()
        // A word written with single spaces - nearly all of them - needs nothing done to it.
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

/**
 * The words tidied, blanks dropped, and every spelling of a word already in the list dropped: the
 * first spelling stays, so what a photo already carries wins over what is being added.
 */
fun List<String>.distinctWords(): List<String> =
    map { Words.tidy(it) }.filter { it.isNotEmpty() }.distinctBy { Words.fold(it) }
