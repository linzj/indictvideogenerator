package com.example.indcitvideo

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import java.io.File

class Utils {
    companion object {
        private const val TAG = "LINZJ"

        fun scanCameraOutputFile(context: Context) {
            // Get the external storage DCIM/Camera directory
            val cameraDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM + "/Camera")

            // Construct the full path to the output file within the Camera directory
            val outputFile = File(cameraDir, "")

            // Now we can scan the file
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            val fileUri = Uri.fromFile(outputFile)
            intent.data = fileUri
            context.sendBroadcast(intent)
        }

        fun logI(msg: String) {
            Log.i(TAG, msg)
        }

        fun logD(msg: String) {
            Log.d(TAG, msg)
        }

        fun scanOutputFile(context: Context, newFileName: String, finish: () -> Unit) {
            MediaScannerConnection.scanFile(
                context, arrayOf(newFileName),
                null
            ) { path, uri ->
                logI("Scanned $path:")
                logI("-> uri=$uri")
                Toast.makeText(context, "Finished converting to $newFileName", Toast.LENGTH_SHORT)
                    .show()
                finish()
            }
        }

        fun buildOutputPath(context: Context, contentUri: Uri): String? {
            // Use ContentResolver to get the file name from the Uri
            val contentResolver = context.contentResolver

            var fileNameWithoutExtension: String? = null
            contentResolver.query(contentUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val fileName = cursor.getString(nameIndex)
                        fileNameWithoutExtension = fileName.substringBeforeLast(".")
                    }
                }
            }

            // If we couldn't get the file name, return null
            if (fileNameWithoutExtension == null) return null

            // Get the directory for the user's public pictures directory
            val cameraDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM + "/Camera")

            // If the external storage directory is not available, return null
            if (cameraDir == null || !cameraDir.exists() && !cameraDir.mkdirs()) return null

            // Append "_indict" to the file name and the ".mp4" suffix
            val outputFileName = "${fileNameWithoutExtension}_indict.mp4"

            // Build the output path in the Camera directory
            return File(cameraDir, outputFileName).absolutePath
        }
    }
}