package com.opensolr.photos.data

import android.content.Context
import com.opensolr.photos.media.LocalPhoto
import com.opensolr.photos.media.PhotoReader
import com.opensolr.photos.net.SolrClient

/**
 * Before the index is emptied (Reset, a configuration rebuild) everything the owner put on the photos is copied
 * from it to the phone: tags, people, their own wording, and a place the file does not carry. From there it is
 * sent with the photos as they are read again, and written into the files, so nothing is lost with the index.
 */
object OwnerWords {

    suspend fun keep(context: Context, cache: PhotoCache, solr: SolrClient, local: Map<String, LocalPhoto>) {
        val mine = cache.editedIds()
        solr.forEachDoc("id,custom_tags,meaning,meaning_own_b,labels,persons_ss,location") { doc ->
            val id = doc.optString("id")
            if (id.isEmpty()) return@forEachDoc
            fun list(field: String) = doc.optJSONArray(field)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
            val tags = list("custom_tags")
            val persons = list("persons_ss")
            val meaning = doc.optString("meaning").trim()
            val own = when {
                doc.has("meaning_own_b") -> meaning.takeIf { doc.optBoolean("meaning_own_b") && it.isNotEmpty() }
                else -> meaning.takeIf { it.isNotEmpty() && it != list("labels").joinToString(", ") }
            }
            // what the owner changed on this phone and did not send yet stays as it is
            if (id !in mine && (tags.isNotEmpty() || persons.isNotEmpty() || own != null)) {
                cache.putEdits(id, PhotoCache.Edits(tags, own, persons.ifEmpty { null }))
            }
            val point = com.opensolr.photos.search.parseLatLon(doc.optString("location"))
            val photo = local[id]
            if (point != null && photo != null && cache.setPlaces(listOf(id)).isEmpty() &&
                PhotoReader.gpsState(context, photo.uri) == PhotoReader.GpsState.MISSING
            ) {
                cache.putSetPlace(PhotoCache.SetPlace(id, point.first, point.second, owner = true, written = false, synced = false))
            }
        }
    }
}
