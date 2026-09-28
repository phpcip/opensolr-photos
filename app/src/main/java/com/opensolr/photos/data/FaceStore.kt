package com.opensolr.photos.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The faces of a photo as one string kept in the index (`faces_json`): the file size they were read from, the
 * finder's version, and per face its box, score, name and fingerprint. A new install or phone reads them back
 * instead of finding the faces again.
 */
object FaceStore {

    class Stored(val sizeBytes: Long, val version: Int, val faces: List<Face>)

    class Face(val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val person: String?, val sure: Boolean, val how: Int, val vector: FloatArray)

    fun encode(sizeBytes: Long, version: Int, faces: List<Pair<PhotoCache.FaceRow, FloatArray>>, flags: Map<Long, Pair<Boolean, Int>>): String {
        val list = JSONArray()
        faces.forEach { (row, vector) ->
            val (sure, how) = flags[row.fid] ?: (true to PhotoCache.HOW_OWNER)
            list.put(JSONArray().put(row.x.toDouble()).put(row.y.toDouble()).put(row.w.toDouble()).put(row.h.toDouble())
                .put(0.0).put(row.person ?: JSONObject.NULL).put(if (sure) 1 else 0).put(how).put(bytes(vector)))
        }
        return JSONObject().put("n", sizeBytes).put("v", version).put("f", list).toString()
    }

    /** The photo's faces from the index, or null when the string is not ours or not whole. */
    fun decode(text: String?): Stored? {
        if (text.isNullOrBlank()) return null
        return try {
            val o = JSONObject(text)
            val list = o.getJSONArray("f")
            val faces = (0 until list.length()).map { i ->
                val a = list.getJSONArray(i)
                val vector = floats(a.getString(8)) ?: return null
                Face(
                    a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat(), a.getDouble(4).toFloat(),
                    if (a.isNull(5)) null else a.getString(5).takeIf { it.isNotBlank() }, a.optInt(6, 1) == 1, a.optInt(7, PhotoCache.HOW_OWNER), vector,
                )
            }
            Stored(o.getLong("n"), o.optInt("v", 0), faces)
        } catch (e: Exception) {
            null
        }
    }

    private fun bytes(vector: FloatArray): String {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
    }

    private fun floats(text: String): FloatArray? {
        val raw = try { Base64.decode(text, Base64.NO_WRAP) } catch (e: Exception) { return null }
        if (raw.isEmpty() || raw.size % 4 != 0) return null
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(raw.size / 4) { buffer.getFloat() }
    }
}
