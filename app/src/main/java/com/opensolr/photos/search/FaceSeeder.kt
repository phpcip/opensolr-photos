package com.opensolr.photos.search

import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.media.FaceEngine

/**
 * Learns every person's face from the photos already tagged with their name, all people at once. The tag is on
 * the photo, not on a face, and people are photographed together: the face that comes back most often in one
 * person's photos may well be the photographer's partner. So the faces of all tagged photos are grouped first,
 * and each group of look-alike faces goes to exactly one person: the one whose tagged photos it fits best,
 * present in most of them and rarely in anyone else's.
 */
object FaceSeeder {

    /** How many tagged photos are looked at per person: the newest, enough to be sure, cheap to compare. */
    private const val SAMPLE = 300

    /** Two faces this close are taken as the same person when grouping. */
    private const val LIKE = 0.50f

    /** A group must be in at least this share of the person's tagged photos (and in 2 at least). */
    private const val SHARE = 0.2f

    /** How well the group's photos must coincide with the person's tagged photos (harmonic mean of both shares). */
    private const val FIT = 0.25f

    /** Most references kept per person. */
    private const val KEEP = 64

    /** Learns all [people] again from their tagged photos; faces the owner named are never touched. */
    fun learnAll(cache: PhotoCache, people: Collection<String>) {
        cache.clearAllLearned()
        val tagged = people.associateWith { cache.taggedPhotoIds(it, SAMPLE).toHashSet() }.filterValues { it.size >= 3 }
        if (tagged.isEmpty()) return
        val faces = cache.facesOfPhotos(tagged.values.flatten().toHashSet())
            .filter { (row, _) -> row.person == null || row.how != PhotoCache.HOW_OWNER }
            .sortedByDescending { it.first.score }
        if (faces.isEmpty()) return

        // greedy grouping around the clearest faces: each face joins the first group it is close enough to
        val leaders = ArrayList<FloatArray>()
        val members = ArrayList<MutableList<Int>>()
        faces.forEachIndexed { i, (_, v) ->
            var g = -1
            var best = LIKE
            for (k in leaders.indices) {
                val s = FaceEngine.similarity(v, leaders[k])
                if (s >= best) { best = s; g = k }
            }
            if (g < 0) { leaders += v; members += arrayListOf(i) } else members[g] += i
        }

        // each group goes to the one person it fits best; an owner's name on a face in the group is final
        val chosen = HashMap<String, MutableList<Int>>()
        members.forEach { idx ->
            val photos = idx.map { faces[it].first.photoId }.toHashSet()
            var bestPerson: String? = null
            var bestFit = FIT
            for ((person, set) in tagged) {
                val inside = photos.count { it in set }
                if (inside < maxOf(2, (set.size * SHARE).toInt())) continue
                val fit = 2f * inside / (photos.size + set.size)
                if (fit > bestFit) { bestFit = fit; bestPerson = person }
            }
            val person = bestPerson ?: return@forEach
            val named = idx.mapNotNull { faces[it].first.person }.map { it.lowercase() }.toSet()
            if (named.any { it != person.lowercase() }) return@forEach
            // only faces inside the person's own tagged photos become references
            val own = idx.filter { faces[it].first.photoId in tagged.getValue(person) }
            chosen.getOrPut(person) { ArrayList() } += own
        }
        chosen.forEach { (person, idx) ->
            val fids = idx.sortedByDescending { faces[it].first.score }.take(KEEP).map { faces[it].first.fid }
            cache.setFacePerson(fids, person, sure = true, how = PhotoCache.HOW_LEARNED)
        }
    }
}
