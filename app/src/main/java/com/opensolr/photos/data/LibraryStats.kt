package com.opensolr.photos.data

data class LibraryStats(
    val total: Int,
    val bytes: Long,
    val tagged: Int,
    val withPeople: Int,
    val withPlace: Int,
    val withText: Int,
    val unread: Int,
    val undated: Int,
    val years: List<StatRow>,
    val months: IntArray,
    val weekdays: IntArray,
    val hours: IntArray,
    val people: List<StatRow>,
    val tags: List<StatRow>,
    val things: List<StatRow>,
    val countries: List<StatRow>,
    val cities: List<StatRow>,
    val cameras: List<StatRow>,
)

data class StatRow(val label: String, val count: Int, val field: String? = null, val value: String? = null)
