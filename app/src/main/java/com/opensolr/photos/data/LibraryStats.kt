package com.opensolr.photos.data

/**
 * The library in numbers, as the Stats screen draws it. Worked out on the phone, from its own copy
 * of the index, by a handful of grouped queries: nothing is asked of Opensolr.
 *
 * @property total       photos in the copy
 * @property bytes       their size on the phone, together
 * @property tagged      photos carrying at least one of the owner's tags
 * @property withPeople  photos with at least one person named on them
 * @property withPlace   photos with a city or a country
 * @property withText    photos with printed text read out of them (receipts, documents)
 * @property unread      photos whose content has not been read into words yet
 * @property undated     photos with no date at all
 * @property years       photos per year, newest first; each opens the grid on that year
 * @property months      photos per month of the year across every year, January first
 * @property weekdays    photos per day of the week, index 0 = Sunday (as SQLite's `%w` counts)
 * @property hours       photos per hour of the day, 0 to 23, in the phone's time zone
 */
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

/**
 * One line of a Stats table and one bar of its chart.
 *
 * @property label  what the line is called on screen
 * @property count  how many photos it holds
 * @property field  the index field that opens these photos on the grid, or null when none can
 * @property value  the value of [field] to filter by
 */
data class StatRow(val label: String, val count: Int, val field: String? = null, val value: String? = null)
