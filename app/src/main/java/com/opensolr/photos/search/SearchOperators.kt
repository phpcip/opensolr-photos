package com.opensolr.photos.search

/**
 * A typed query split into its plain text and its +/- operators, the same rules as
 * Hybrid_search::parse_operators on search.opensolr.com: a sign counts only at the start of
 * a token, the operand is a word or a "quoted phrase" (quotes kept), an unprefixed phrase
 * stays in the text, a lone sign is plain text.
 */
data class SearchOperators(
    val base: String,
    val required: List<String>,
    val excluded: List<String>,
) {
    val hasOps: Boolean get() = required.isNotEmpty() || excluded.isNotEmpty()

    companion object {
        fun parse(raw: String): SearchOperators {
            val s = raw
            val base = StringBuilder()
            val required = ArrayList<String>()
            val excluded = ArrayList<String>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c.isWhitespace()) { base.append(c); i++; continue }

                // An unprefixed phrase is taken whole, so a '-' inside it is never an exclusion
                if (c == '"') {
                    val end = s.indexOf('"', i + 1)
                    if (end < 0) { base.append(s, i, s.length); break }
                    base.append(s, i, end + 1)
                    i = end + 1
                    continue
                }

                if ((c == '+' || c == '-') && (i == 0 || s[i - 1].isWhitespace())) {
                    val j = i + 1
                    var term: String? = null
                    var next = j
                    if (j < s.length && s[j] == '"') {
                        val end = s.indexOf('"', j + 1)
                        if (end >= 0) { term = s.substring(j, end + 1); next = end + 1 }
                    }
                    if (term == null) {
                        var k = j
                        while (k < s.length && !s[k].isWhitespace()) k++
                        term = s.substring(j, k)
                        next = k
                    }
                    if (term.trim { it.isWhitespace() || it == '"' }.isNotEmpty()) {
                        (if (c == '+') required else excluded) += term
                        i = next
                        continue
                    }
                    base.append(c)
                    i++
                    continue
                }

                base.append(c)
                i++
            }
            return SearchOperators(base.toString().trim().replace(Regex("\\s+"), " "), required, excluded)
        }
    }
}
