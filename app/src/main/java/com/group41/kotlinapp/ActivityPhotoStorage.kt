package com.group41.kotlinapp

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Manages creation, URI generation, validation, and cleanup for persistent activity photo files.
 *
 * Activity photos are stored under context.filesDir / "activities" so they remain persistent
 * on disk across app sessions until background synchronization completes.
 */
object ActivityPhotoStorage {

    /**
     * Creates a new .jpg file within the 'activities' subfolder of context.filesDir.
     * Ensures the parent directory exists before returning the File reference.
     *
     * @param context Application or Activity context.
     * @return A new File instance named with a random UUID in context.filesDir/activities/.
     */
    fun newPhotoFile(context: Context): File {
        val dir = File(context.filesDir, "activities")
        check(dir.isDirectory || dir.mkdirs() || dir.isDirectory) {
            "Unable to create activity photo directory: ${dir.absolutePath}"
        }
        return File(dir, "${UUID.randomUUID()}.jpg")
    }

    /**
     * Generates a content:// URI for a photo file using the app's FileProvider authority.
     *
     * @param context Application or Activity context.
     * @param file The file requiring a content URI.
     * @return Content URI writable by external camera applications.
     */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Validates that a captured photo file exists on disk and contains data.
     *
     * @param file The photo file to validate.
     * @return True if the file exists and length > 0, false otherwise.
     */
    fun isValidPhoto(file: File?): Boolean =
        file != null && file.exists() && file.length() > 0L

    /**
     * Safely removes a photo file if capture fails, the user cancels, or the file is no longer needed.
     *
     * @param file The photo file to delete.
     * @return True if deleted or non-existent, false if deletion failed.
     */
    fun deletePhotoFile(file: File?): Boolean {
        return try {
            if (file != null && file.exists()) {
                file.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
