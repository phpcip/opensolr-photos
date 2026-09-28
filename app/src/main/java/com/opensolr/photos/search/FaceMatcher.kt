package com.opensolr.photos.search

import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.media.FaceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where else a named person appears: every unnamed face compared with the faces already carrying the name. */
object FaceMatcher {

    /** Below this two faces are taken for different people (the SFace threshold for the same identity). */
    const val SAME = 0.36f

    /** From here a match is ticked in advance in the review. */
    const val SURE = 0.45f

    /** How many of the person's faces are compared: the clearest ones, enough for ages and angles. */
    private const val REFERENCES = 64

    /** Unnamed faces that look like [person], best first, one per photo. */
    suspend fun candidates(cache: PhotoCache, person: String): List<PhotoCache.FaceRow> = withContext(Dispatchers.Default) {
        val refs = cache.personVectors(person, REFERENCES)
        if (refs.isEmpty()) return@withContext emptyList()
        val best = HashMap<String, PhotoCache.FaceRow>()
        cache.forEachOpenFace(person) { page ->
            for ((row, vector) in page) {
                var sim = -1f
                for (r in refs) { val s = FaceEngine.similarity(r, vector); if (s > sim) sim = s }
                if (sim < SAME) continue
                val had = best[row.photoId]
                if (had == null || had.similarity < sim) best[row.photoId] = row.copy(similarity = sim)
            }
        }
        best.values.sortedByDescending { it.similarity }
    }
}
