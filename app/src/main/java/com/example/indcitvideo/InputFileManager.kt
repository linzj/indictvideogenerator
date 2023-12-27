package com.example.indcitvideo

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class InputFileManager(private val context: Context) {
    private var tempFile: File? = null
    private var fd: Int? = null;

    companion object {
        private const val TAG = "LINZJ"
    }

    fun getInputString(uri: Uri): String {
        // Try to get file path from URI
        fd = context.contentResolver.openFileDescriptor(uri, "r")?.detachFd();
        if (fd != null)
            return "-fd $fd -i fd:";

        // Fall back to creating a temporary file if the above method fails
        val _tempFile = createTempMp4FileInCacheDir(context)
        tempFile = _tempFile
        // Copy the content from the uri
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                val buffer = ByteArray(1024 * 1024)
                var bytesRead: Int
                var bytesReadTotal: Int = 0
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    bytesReadTotal += bytesRead
                }
                Log.d(TAG, "finished reading $bytesReadTotal")
            }
        }
        return "-i $_tempFile"
    }

    private fun getInputString(context: Context, uri: Uri): File? {
        val contentResolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME
        )

        var displayName: String? = null

        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayNameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                displayName = cursor.getString(displayNameIndex)
            }
        }

        if (displayName != null) {
            // Get the directory for the user's public pictures directory
            val cameraDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM + "/Camera")
            return File(cameraDir, displayName)
        }

        return null
    }

    private fun createTempMp4FileInCacheDir(context: Context): File {
        val prefix = "temp_video_"
        val suffix = ".mp4"
        val cacheDir = context.cacheDir // Get the cache directory of your app
        // Create a temp file in the specified directory
        return File.createTempFile(prefix, suffix, cacheDir)
    }

    fun finish() {
        try {
            fd?.let { fd ->
                val pfd = ParcelFileDescriptor.adoptFd(fd)
                pfd.close()
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        // Delete the temp file if it was created
        tempFile?.delete()
    }
}
