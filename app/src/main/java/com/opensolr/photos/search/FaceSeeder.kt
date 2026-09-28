package com.opensolr.photos.search

import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.media.FaceEngine

/**
 * Learns each person's face from the photos already tagged with their name. The tag is on the photo, not on a
 * face, so a photo can hold several faces or only the photographer's friend: the person's face is the one that
 * comes back across their tagged photos. Those recurring faces become the person's references.
 */
object FaceSeeder {

    /** How many tagged photos are looked at: the newest, enough to be sure, cheap to compare. */
    private const val SAMPLE = 300

    /** Two faces this close are taken as the same person when counting how often a face comes back. */
    private const val LIKE = 0.50f

    /** A face must come back in at least this share of the person's tagged photos (and in 2 at least). */
    private const val SHARE = 0.2f

    /** Most references kept per person. */
    private const val KEEP = 64

    /** Learns [person] again from their tagged photos; faces the owner named are never touched. */
    fun learn(cache: PhotoCache, person: String) {
        cache.clearLearned(person)
        val faces = cache.facesOfTagged(person, SAMPLE)
            .filter { (row, _) -> row.person == null || row.person.equals(person, ignoreCase = true) }
        val photos = faces.map { it.first.photoId }.distinct()
        if (photos.size < 3) return
        val need = maxOf(2, (photos.size * SHARE).toInt())

        // how many other tagged photos hold a face like this one
        val support = IntArray(faces.size)
        for (i in faces.indices) {
            val seen = HashSet<String>()
            val (a, va) = faces[i]
            for (j in faces.indices) {
                val (b, vb) = faces[j]
                if (b.photoId == a.photoId || b.photoId in seen) continue
                if (FaceEngine.similarity(va, vb) >= LIKE) seen += b.photoId
            }
            support[i] = seen.size
        }
        // the best-supported face of each photo, if it comes back often enough, is the person
        val chosen = faces.indices.groupBy { faces[it].first.photoId }
            .mapNotNull { (_, idx) -> idx.maxByOrNull { support[it] }?.takeIf { support[it] >= need } }
            .sortedByDescending { support[it] }
            .take(KEEP)
            .filter { faces[it].first.person == null }
        if (chosen.isNotEmpty()) cache.setFacePerson(chosen.map { faces[it].first.fid }, person, sure = true, how = PhotoCache.HOW_LEARNED)
    }
}
