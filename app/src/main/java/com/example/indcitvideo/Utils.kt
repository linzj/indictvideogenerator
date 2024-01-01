package com.example.indcitvideo

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.opengl.GLES20
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import com.drew.imaging.mp4.Mp4MetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.mp4.Mp4Directory
import java.io.File
import java.time.LocalTime
import java.time.temporal.ChronoUnit


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
            Toast.makeText(context, "Finished converting to DCIM directory", Toast.LENGTH_SHORT)
                .show()
            finish()
        }

        fun buildOutputFile(context: Context, contentUri: Uri): ParcelFileDescriptor? {
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
            val title = "${fileNameWithoutExtension}_indict"

            val values = ContentValues()
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM)
            values.put(MediaStore.Video.Media.TITLE, title)
            values.put(MediaStore.Video.Media.DISPLAY_NAME, outputFileName)
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())

            val videoUri: Uri? =
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (videoUri != null)
                return contentResolver.openFileDescriptor(videoUri, "w")
            return null
        }

        fun extractGpsLocationFromMp4(context: Context, uri: Uri): Pair<Double, Double>? {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                try {
                    val metadata: Metadata =
                        Mp4MetadataReader.readMetadata(inputStream) ?: return null

                    val gpsDirectory = metadata.getFirstDirectoryOfType(Mp4Directory::class.java)
                    if (gpsDirectory != null) {
                        val latitude = gpsDirectory.getDouble(Mp4Directory.TAG_LATITUDE)
                        val longitude = gpsDirectory.getDouble(Mp4Directory.TAG_LONGITUDE)
                        return Pair(latitude, longitude)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return null
        }

        fun adjustTotalTime(startTime: String?, stopTime: String?, totalTime: Long): Long {
            val startTimeObj = startTime?.let { LocalTime.parse(it) }
            val stopTimeObj = stopTime?.let { LocalTime.parse(it) }

            var adjustedTotalTime = totalTime

            // If both startTime and stopTime are not null, calculate the difference between them
            if (startTimeObj != null && stopTimeObj != null) {
                val duration = ChronoUnit.MILLIS.between(startTimeObj, stopTimeObj)
                adjustedTotalTime = duration
            } else {
                // If only startTime is not null, subtract its time from totalTime
                startTimeObj?.let {
                    val millis = it.toNanoOfDay() / 1000000
                    adjustedTotalTime -= millis
                }
                // If only stopTime is not null, subtract its time from totalTime
                stopTimeObj?.let {
                    val millis = it.toNanoOfDay() / 1000000
                    adjustedTotalTime = millis
                }
            }

            return adjustedTotalTime
        }

        fun timeStringToMills(timeString: String?): Long? {
            val startTimeObj = timeString?.let { LocalTime.parse(it) }
            val mills = startTimeObj?.let {
                it.toNanoOfDay() / 1000000
            }
            return mills
        }

        fun checkGLError(op: String) {
            var error: Int
            while (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
                val msg = op + ": glError " + Integer.toHexString(error)
                Utils.logD(msg)
                throw java.lang.RuntimeException(msg)
            }
        }
    }
}