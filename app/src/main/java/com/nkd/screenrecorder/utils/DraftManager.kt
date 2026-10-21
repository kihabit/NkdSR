package com.nkd.screenrecorder.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

data class DraftEntry(
    val draftId: String,
    var videoUrl: String,
    var thumbnailPath: String,
    var displayName: String,
    var lastEditTimestamp: Long,
    var durationMs: Long
)

object DraftManager {

    private const val INDEX_FILE = "drafts_index.json"

    private fun indexFile(context: Context): File = File(context.filesDir, INDEX_FILE)

    private fun draftsRootDir(context: Context): File =
        File(context.filesDir, "drafts").apply { if (!exists()) mkdirs() }

    fun draftFolder(context: Context, draftId: String): File =
        File(draftsRootDir(context), draftId).apply { if (!exists()) mkdirs() }

    fun stateFile(context: Context, draftId: String): File =
        File(draftFolder(context, draftId), "state.json")

    fun thumbFile(context: Context, draftId: String): File =
        File(draftFolder(context, draftId), "thumb.jpg")

    fun newDraftId(): String = UUID.randomUUID().toString()

    fun loadIndex(context: Context): MutableList<DraftEntry> {
        val file = indexFile(context)
        if (!file.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<DraftEntry>>() {}.type
            Gson().fromJson(file.readText(), type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveIndex(context: Context, list: List<DraftEntry>) {
        try {
            indexFile(context).writeText(Gson().toJson(list))
        } catch (_: Exception) {
        }
    }

    /** Insert or update a draft entry (most-recently-edited first). */
    @Synchronized
    fun upsertEntry(context: Context, entry: DraftEntry) {
        val list = loadIndex(context)
        list.removeAll { it.draftId == entry.draftId }
        list.add(0, entry)
        saveIndex(context, list)
    }

    @Synchronized
    fun removeEntry(context: Context, draftId: String) {
        val list = loadIndex(context)
        list.removeAll { it.draftId == draftId }
        saveIndex(context, list)
        draftFolder(context, draftId).deleteRecursively()
    }

    fun getEntry(context: Context, draftId: String): DraftEntry? =
        loadIndex(context).firstOrNull { it.draftId == draftId }

    /** Latest N drafts, most recently edited first. */
    fun recentDrafts(context: Context, limit: Int = 10): List<DraftEntry> =
        loadIndex(context).sortedByDescending { it.lastEditTimestamp }.take(limit)
}