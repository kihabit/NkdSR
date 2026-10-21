package com.nkd.screenrecorder.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.core.net.toUri

/**
 * Manages directory preferences for screen recordings
 */
class DirectoryPreferenceManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "ScreenRecorderPrefs"
        private const val KEY_DIRECTORY_URI = "directory_uri"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Save directory URI with persistent permissions
     */
    fun saveDirectoryUri(uri: Uri) {
        try {
            // Take persistable URI permission
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            prefs.edit()
                .putString(KEY_DIRECTORY_URI, uri.toString())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Get saved directory URI
     */
    fun getDirectoryUri(): Uri? {
        val uriString = prefs.getString(KEY_DIRECTORY_URI, null)
        return uriString?.toUri()
    }

    /**
     * Check if directory URI is valid and accessible
     */
    fun isDirectoryValid(): Boolean {
        val uri = getDirectoryUri() ?: return false
        return isUriValid(uri)
    }

    /**
     * Get human-readable directory path
     */
    fun getDirectoryPath(): String? {
        val uri = getDirectoryUri() ?: return null
        return try {
            val directory = DocumentFile.fromTreeUri(context, uri)
            directory?.name ?: uri.lastPathSegment
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clear saved directory
     */
    fun clearDirectory() {
        val uri = getDirectoryUri()
        uri?.let {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        prefs.edit()
            .remove(KEY_DIRECTORY_URI)
            .apply()
    }

    /**
     * Create a new file in the selected directory
     */
    fun createOutputFile(fileName: String): Uri? {
        val directoryUri = getDirectoryUri() ?: return null

        return try {
            val directory = DocumentFile.fromTreeUri(context, directoryUri)
            val file = directory?.createFile("video/mp4", fileName)
            file?.uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun isUriValid(uri: Uri): Boolean {
        return try {
            // Check if we still have permission
            val persistedUris = context.contentResolver.persistedUriPermissions
            val hasPermission = persistedUris.any { it.uri == uri }

            if (!hasPermission) {
                return false
            }

            // Check if directory still exists
            val directory = DocumentFile.fromTreeUri(context, uri)
            directory?.exists() == true && directory.canWrite()
        } catch (e: Exception) {
            false
        }
    }
}