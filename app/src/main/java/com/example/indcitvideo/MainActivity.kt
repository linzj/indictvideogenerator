package com.example.indcitvideo

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback
import com.example.indcitvideo.ui.theme.IndcitvideoTheme
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : ComponentActivity() {
    companion object {
        private const val PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 1
        private const val REQUEST_VIDEO_FILE = 2
        private const val TAG = "LINZJ"
    }

    private val viewModel = FFmpegViewModel()

    // Create a launcher with the GetContent contract for picking a video
    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                // Handle the selected video URI
                val validator = TimeFormatValidator()
                if (!validator.testTimeFormat(viewModel.startTime.value) || !validator.testTimeFormat(
                        viewModel.stopTime.value
                    )
                ) {
                    Toast.makeText(this, "Time not in the correct format", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    Toast.makeText(
                        this,
                        "FFMpeg processing started, be patient",
                        Toast.LENGTH_SHORT
                    )
                        .show()
                    handleWork(this, uri, viewModel.startTime.value, viewModel.stopTime.value)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IndcitvideoTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Greeting("Android")
                    FFmpegProgressView(viewModel,
                        onButtonClick = {
                            if (hasReadExternalStoragePermission()) {
                                openVideoPicker()
                            } else {
                                requestReadExternalStoragePermission()
                            }
                        })
                }
            }
        }
    }

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

    fun scanOutputFile(context: Context, newFileName: String) {
        MediaScannerConnection.scanFile(
            context, arrayOf(newFileName),
            null
        ) { path, uri ->
            Log.i(TAG, "Scanned $path:")
            Log.i(TAG, "-> uri=$uri")
            Toast.makeText(this, "Finished converting to $newFileName", Toast.LENGTH_SHORT)
                .show()
            finish()
            // Optionally, you can use the uri to open the file with an intent,
            // if you want to view the video in your app or another app.
        }
    }

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

    fun createTempMp4FileInCacheDir(context: Context): File {
        val prefix = "temp_video_"
        val suffix = ".mp4"
        val cacheDir = context.cacheDir // Get the cache directory of your app

        // Create a temp file in the specified directory
        val tempFile = File.createTempFile(prefix, suffix, cacheDir)

        return tempFile
    }

    private fun handleWork(context: Context, uri: Uri, startTime: String?, stopTime: String?) {
        // Get video metadata
        val retriever = MediaMetadataRetriever()
        var formattedDate: String = "unknown" // To store the local date
        var formattedTime: String = "unknown" // To store the local time
        var frameRate: Float = 30f // Default frame rate if not available
        var totalDurationInMilliseconds = 0f
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
                        ?.toFloatOrNull() ?: 0f

                // Parse and format creation time
                val dateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US)
                dateFormat.timeZone = TimeZone.getTimeZone("UTC")
                val originalCreationDate: Date? = dateFormat.parse(creationTime)

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

        // Register a new FFmpeg pipe
        // val pipePath = FFmpegKitConfig.registerNewFFmpegPipe(context)
        val inputFileManager = InputFileManager(context)
        val pipePath = inputFileManager.getFileFromUri(uri)

        // Construct FFmpeg command
        val outputFilePath = buildOutputPath(context, uri);

        val ffmpegCommand = StringBuilder("-y -f mp4 -i $pipePath")

        // Add -ss option for startTime if it's not null
        startTime?.let {
            ffmpegCommand.append(" -ss $it")
        }

        // Add -to option for stopTime if it's not null
        stopTime?.let {
            ffmpegCommand.append(" -to $it")
        }
        ffmpegCommand.append(
            " -vf drawtext=fontfile=$fontPath:x=(w-tw)-10:y=(h-th)-10:fontcolor=white@1.0:fontsize=$fontSize:box=1:boxcolor=black@0.5:boxborderw=5:timecode=\\'$formattedTime\\:00\\':rate=$frameRate,drawtext=fontfile=$fontPath:text='$formattedDate':x=(w-tw)-text_w-40:y=(h-th)-10:fontcolor=white@1.0:fontsize=$fontSize:box=1:boxcolor=black@0.5:boxborderw=5 -c:v libx264 -an $outputFilePath"
        )

        // Execute FFmpeg command
        FFmpegKit.executeAsync(
            ffmpegCommand.toString(),
            FFmpegSessionCompleteCallback { session ->
                inputFileManager.finish()
                // This callback is called when the execution is completed
                val returnCode = session.returnCode
                if (returnCode.isValueSuccess) {
                    // Handle the successful completion of the command
                    Log.d(TAG, "session complete success")
                } else if (returnCode.isValueCancel) {
                    // Handle the cancellation of the command
                    Log.d(TAG, "session complete cancle")
                } else {
                    // Handle the error
                    Log.d(TAG, "session complete error")
                }
                // Optionally shut down the executor service if it is no longer needed
                runOnUiThread {
                    if (outputFilePath != null)
                        scanOutputFile(this, outputFilePath)
                }
            },
            // LogCallback
            LogCallback { log ->
                // Handle log output if needed
                Log.d(TAG, log.toString())
            },
            // StatisticsCallback
            StatisticsCallback { statistics ->
                runOnUiThread {
                    viewModel.updateProgressWithStatistics(statistics, totalDurationInMilliseconds)
                }
            }
        )

        // Start a new thread to write data to the pipe
        // Thread {
        //     context.contentResolver.openInputStream(uri)?.use { inputStream ->
        //         FileOutputStream(pipePath).use { outputStream ->
        //             val buffer = ByteArray(1024 * 1024)
        //             var bytesRead: Int
        //             var bytesReadTotal: Int = 0
        //             while (inputStream.read(buffer).also { bytesRead = it } != -1) {
        //                 outputStream.write(buffer, 0, bytesRead)
        //                 bytesReadTotal += bytesRead
        //             }
        //             Log.d(TAG, "finished reading $bytesReadTotal")
        //         }
        //     }
        //     // Close the pipe to indicate to FFmpeg that no more data will be written
        //     FFmpegKitConfig.closeFFmpegPipe(pipePath)
        // }.start()
    }

    private fun hasReadExternalStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestReadExternalStoragePermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
            PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE
        )
    }

    private fun openVideoPicker() {
        // scanCameraOutputFile(this);
        pickVideoLauncher.launch("video/*")
//        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
//            type = "video/*"
//            addCategory(Intent.CATEGORY_OPENABLE)
//        }
//        startActivityForResult(intent, REQUEST_VIDEO_FILE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    openVideoPicker()
                } else {
                    // Permission denied. Handle the case where the user denies the permission.
                    Toast.makeText(this, "You shall grant the permission", Toast.LENGTH_SHORT)
                        .show()
                }
                return
            }

            else -> {
                // Ignore all other requests.
            }
        }
    }
}

class FFmpegViewModel : ViewModel() {
    private val _progress = MutableLiveData(0f)
    val progress: LiveData<Float> = _progress

    // Mutable live data for start and stop times
    private val _startTime = MutableLiveData<String?>()
    val startTime: LiveData<String?> = _startTime

    private val _stopTime = MutableLiveData<String?>()
    val stopTime: LiveData<String?> = _stopTime

    fun updateProgressWithStatistics(statistics: Statistics, totalDurationInMilliseconds: Float) {
        // TODO: Calculate the progress and update the LiveData
        val currentProgress = (statistics.time.toDouble() / totalDurationInMilliseconds).toFloat()
        _progress.value = currentProgress.coerceIn(0f, 1f)
    }

    // Functions to update start and stop times
    fun updateStartTime(time: String?) {
        _startTime.value = time
    }

    fun updateStopTime(time: String?) {
        _stopTime.value = time
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FFmpegProgressView(
    viewModel: FFmpegViewModel, // Pass the viewModel into the composable
    modifier: Modifier = Modifier,
    onButtonClick: () -> Unit
) {
    // Observe LiveData and convert it to State using the collectAsState() function
    val progress by viewModel.progress.observeAsState(0f)
    val startTime by viewModel.startTime.observeAsState("")
    val stopTime by viewModel.stopTime.observeAsState("")

    Column(modifier = modifier) {
        Text(text = "Hello FFmpeg!")
        LinearProgressIndicator(progress = progress)
        Spacer(modifier = Modifier.height(8.dp)) // Add some space between the progress bar and the button
        Button(onClick = onButtonClick) {
            Text(text = "Click Me")
        }

        // Use the observed states for the TextField values
        TextField(
            value = startTime ?: "",
            onValueChange = { newStartTime ->
                viewModel.updateStartTime(newStartTime) // Update ViewModel state
            },
            label = { Text("Start Time (hh:mm:ss)") }
        )
        TextField(
            value = stopTime ?: "",
            onValueChange = { newStopTime ->
                viewModel.updateStopTime(newStopTime) // Update ViewModel state
            },
            label = { Text("Stop Time (hh:mm:ss)") }
        )
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = "Hello $name!",
            modifier = Modifier
        )
        // Add space between the text and the progress bar, for example, using Spacer()
        CreateProgressBar()
    }
}

@Composable
fun CreateProgressBar() {
    // This will create an indeterminate linear progress indicator
    LinearProgressIndicator()

    // If you want a circular progress indicator instead, you can use:
    // CircularProgressIndicator()
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    IndcitvideoTheme {
        Greeting("Android")
    }
}
