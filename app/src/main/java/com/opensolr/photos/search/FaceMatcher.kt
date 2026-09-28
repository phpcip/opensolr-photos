package com.opensolr.photos.search

import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.media.FaceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where else a named person appears: every unnamed face compared with the faces already carrying the name. */
object FaceMatcher {

    /** Below this a face is not offered for the person at all. */
    const val SAME = 0.40f

    /** From here a match is ticked in advance in the review. */
    const val SURE = 0.50f

    /** How many of the person's faces are compared: the clearest of those that agree with each other. */
    private const val REFERENCES = 64

    /** How many of the person's faces are looked at before the ones that disagree are dropped. */
    private const val POOL = 200

    /** A face whose median likeness to the person's other faces is lower is somebody else, named by mistake. */
    private const val AGREE = 0.30f

    /** A face counts as the person only when it is close to this many of the person's faces, not to one. */
    private const val VOTES = 3

    /**
     * The person's faces to compare with: faces named for sure that look like the rest of them. A face named
     * wrongly (a photo tagged with one person where the only face found is another) disagrees with the others
     * and is left out, so it never pulls that other person's photos in.
     */
    fun references(cache: PhotoCache, person: String): List<FloatArray> {
        val pool = cache.personVectors(person, POOL)
        if (pool.size <= VOTES) return pool
        val agree = pool.filterIndexed { i, v ->
            val sims = FloatArray(pool.size - 1)
            var k = 0
            for (j in pool.indices) if (j != i) sims[k++] = FaceEngine.similarity(v, pool[j])
            sims.sort()
            sims[sims.size / 2] >= AGREE
        }
        return (if (agree.size >= VOTES) agree else pool).take(REFERENCES)
    }

    /** How much [vector] looks like the person: the mean of its [VOTES] best likenesses to the references. */
    fun score(vector: FloatArray, refs: List<FloatArray>): Float {
        if (refs.isEmpty()) return -1f
        val votes = minOf(VOTES, refs.size)
        val best = FloatArray(votes) { -1f }
        for (r in refs) {
            val s = FaceEngine.similarity(r, vector)
            if (s > best[votes - 1]) {
                var i = votes - 1
                while (i > 0 && best[i - 1] < s) { best[i] = best[i - 1]; i-- }
                best[i] = s
            }
        }
        return best.sum() / votes
    }

    /** A face this close to a person is taken as that person without asking. */
    const val AUTO = 0.6f

    /** Every person with references, and those references. */
    fun people(cache: PhotoCache): Map<String, List<FloatArray>> =
        cache.facePeople().keys.associateWith { references(cache, it) }.filterValues { it.isNotEmpty() }

    /**
     * Names the unnamed faces of one photo that are very close to a person (one person per photo at most once),
     * marks them as the matcher's own and puts the people on the photo. True when anything was named.
     */
    fun autoName(cache: PhotoCache, edits: EditRepository, photoId: String, rows: List<PhotoCache.FaceRow>, vectors: List<FloatArray>, people: Map<String, List<FloatArray>>): Boolean {
        if (people.isEmpty()) return false
        val taken = rows.mapNotNull { it.person?.lowercase() }.toMutableSet()
        val named = ArrayList<String>()
        rows.forEachIndexed { i, row ->
            if (row.person != null) return@forEachIndexed
            val vector = vectors.getOrNull(i) ?: return@forEachIndexed
            var bestName: String? = null
            var best = AUTO
            for ((name, refs) in people) {
                if (name.lowercase() in taken) continue
                val s = score(vector, refs)
                if (s >= best) { best = s; bestName = name }
            }
            bestName?.let { name ->
                cache.setFacePerson(listOf(row.fid), name, sure = false, how = PhotoCache.HOW_AUTO)
                taken += name.lowercase()
                named += name
            }
        }
        if (named.isEmpty()) return false
        edits.queueForAll(listOf(photoId), null, false, named, false)
        return true
    }

    /** [autoName] over photos already read ([photoIds]), with the people as they are now. True when anything was named. */
    fun nameRecent(cache: PhotoCache, edits: EditRepository, photoIds: List<String>): Boolean {
        val people = people(cache)
        if (people.isEmpty()) return false
        var any = false
        photoIds.forEach { id ->
            val faces = cache.facesWithVectors(id)
            if (faces.isEmpty() || faces.all { it.first.person != null }) return@forEach
            if (autoName(cache, edits, id, faces.map { it.first }, faces.map { it.second }, people)) any = true
        }
        return any
    }

    /** Unnamed faces that look like [person], best first, one per photo. */
    suspend fun candidates(cache: PhotoCache, person: String): List<PhotoCache.FaceRow> = withContext(Dispatchers.Default) {
        val refs = references(cache, person)
        if (refs.isEmpty()) return@withContext emptyList()
        val best = HashMap<String, PhotoCache.FaceRow>()
        cache.forEachOpenFace(person) { page ->
            for ((row, vector) in page) {
                val sim = score(vector, refs)
                if (sim < SAME) continue
                val had = best[row.photoId]
                if (had == null || had.similarity < sim) best[row.photoId] = row.copy(similarity = sim)
            }
        }
        best.values.sortedByDescending { it.similarity }
    }
}
