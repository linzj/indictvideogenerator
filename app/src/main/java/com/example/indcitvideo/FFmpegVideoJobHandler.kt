package com.example.indcitvideo

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone


class FFmpegVideoJobHandler : VideoJobHandler {

    companion object {
        fun findFirstSystemFontPath(): String? {
            return try {
                val mapsFile = File("/proc/self/maps")
                val lines = mapsFile.readLines()

                lines.mapNotNull { line ->
                    val startIndex = line.indexOf("/system/fonts/")
                    if (startIndex != -1 && line.contains(".ttf")) {
                        line.substring(startIndex).split(" ").firstOrNull()
                    } else {
                        null
                    }
                }.firstOrNull()

            } catch (e: Exception) {
                e.printStackTrace()
                null // Return null in case of an error or if no font path is found
            }
        }

    }

    override fun handleWork(
        context: Context,
        uri: Uri,
        startTime: String?,
        stopTime: String?,
        finishAction: (String?) -> Unit,
        runOnUi: (() -> Unit) -> Unit,
        updateProgress: (Float) -> Unit
    ) {
        // Get video metadata
        val retriever = MediaMetadataRetriever()
        var formattedDate: String = "unknown" // To store the local date
        var formattedTime: String = "unknown" // To store the local time
        var frameRate: Float = 30f // Default frame rate if not available
        var totalDurationInMilliseconds = 0L
        var fontPath = findFirstSystemFontPath()
        var fontSize = 96;

        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { parcelFileDescriptor ->
                retriever.setDataSource(parcelFileDescriptor.fileDescriptor)
                val creationTime =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                frameRate =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                        ?.toFloatOrNull() ?: 30f
                totalDurationInMilliseconds =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L

                // Parse and format creation time
                val dateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US)
                dateFormat.timeZone = TimeZone.getTimeZone("UTC")
                val originalCreationDate: Date? = dateFormat.parse(creationTime!!)

                // Format the Date object into local date string
                val displayDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                displayDateFormat.timeZone = TimeZone.getDefault()
                formattedDate =
                    originalCreationDate?.let { displayDateFormat.format(it) } ?: "unknown"

                // Format the Date object into local time string
                val displayTimeFormat = SimpleDateFormat("HH\\:mm\\:ss", Locale.US)
                displayTimeFormat.timeZone = TimeZone.getDefault()
                formattedTime =
                    originalCreationDate?.let { displayTimeFormat.format(it) } ?: "unknown"

                // Calculate fontSize.
                val height =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 0
                if (height != 0) {
                    fontSize = (height * 0.04445).toInt()
                }
            }
        } finally {
            retriever.release()
        }

        totalDurationInMilliseconds =
            Utils.adjustTotalTime(startTime, stopTime, totalDurationInMilliseconds);

        // Register a new FFmpeg pipe
        val inputFileManager = InputFileManager(context)
        val inputString = inputFileManager.getInputString(uri)

        // Construct FFmpeg command
        val outputFile = Utils.buildOutputFile(context, uri)?.detachFd();

        val ffmpegCommand = StringBuilder("-y $inputString")

        // Add -ss option for startTime if it's not null
        startTime?.let {
            ffmpegCommand.append(" -ss $it")
        }

        // Add -to option for stopTime if it's not null
        stopTime?.let {
            ffmpegCommand.append(" -to $it")
        }
        ffmpegCommand.append(
            " -vf drawtext=fontfile=$fontPath:x=(w-tw)-10:y=(h-th)-10:fontcolor=white@1.0:fontsize=$fontSize:box=1:boxcolor=black@0.5:boxborderw=5:timecode=\\'$formattedTime\\:00\\':rate=$frameRate,drawtext=fontfile=$fontPath:text='$formattedDate':x=(w-tw)-text_w-40:y=(h-th)-10:fontcolor=white@1.0:fontsize=$fontSize:box=1:boxcolor=black@0.5:boxborderw=5 -f mp4 -c:v libx264 -an -fd $outputFile fd:"
        )

        // Execute FFmpeg command
        FFmpegKit.executeAsync(
            ffmpegCommand.toString(),
            { session ->
                inputFileManager.finish()
                if (outputFile != null) {
                    val fdObj = ParcelFileDescriptor.fromFd(outputFile)
                    fdObj.close()
                }

                // This callback is called when the execution is completed
                val returnCode = session.returnCode
                if (returnCode.isValueSuccess) {
                    // Handle the successful completion of the command
                    Utils.logD("session complete success")
                } else if (returnCode.isValueCancel) {
                    // Handle the cancellation of the command
                    Utils.logD("session complete cancle")
                } else {
                    // Handle the error
                    Utils.logD("session complete error")
                }
                // Optionally shut down the executor service if it is no longer needed
                runOnUi {
                    finishAction("indicted.mp4")
                }
            },
            // LogCallback
            { log ->
                // Handle log output if needed
                Utils.logD(log.toString())
            },
            // StatisticsCallback
            { statistics ->
                runOnUi {
                    val currentProgress =
                        (statistics.time.toDouble() / totalDurationInMilliseconds).toFloat()
                    updateProgress(currentProgress)
                }
            }
        )
    }

}