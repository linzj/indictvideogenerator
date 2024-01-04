package com.example.indcitvideo

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.indcitvideo.ui.theme.IndcitvideoTheme

class MainActivity : ComponentActivity() {
    companion object {
        private const val PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 1
        private const val REQUEST_VIDEO_FILE = 2
        private val androidUPermissions = arrayOf(
            READ_MEDIA_IMAGES,
            READ_MEDIA_VIDEO,
            READ_MEDIA_VISUAL_USER_SELECTED,
            ACCESS_MEDIA_LOCATION
        )

        private val androidTPermissions = arrayOf(
            READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, ACCESS_MEDIA_LOCATION
        )

        private val androidSPermissions = arrayOf(
            READ_EXTERNAL_STORAGE, ACCESS_MEDIA_LOCATION
        )

        private val androidPreTPermissions = arrayOf(READ_EXTERNAL_STORAGE)

        private val permissionsRequired = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                androidUPermissions
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                androidTPermissions
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                androidSPermissions
            }

            else -> {
                androidPreTPermissions
            }
        }

    }

    private val viewModel = FFmpegViewModel()
    private var useHardware = false

    // Create a launcher with the GetContent contract for picking a video
    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                data?.data?.also { uri ->
                    // Handle the Uri, perform read or display operations
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
                            this, "FFMpeg processing started, be patient", Toast.LENGTH_SHORT
                        ).show()
                        viewModel.updateButtonEnable(false)
                        val startTime: String? =
                            if (viewModel.startTime.value == "00:00:00") null else viewModel.startTime.value
                        val stopTime: String? =
                            if (viewModel.stopTime.value == "00:00:00") null else viewModel.stopTime.value
                        val videoJobHandler: VideoJobHandler =
                            if (!useHardware) FFmpegVideoJobHandler() else HardwareVideoJobHandler()
                        videoJobHandler.handleWork(this,
                            uri,
                            startTime,
                            stopTime,
                            { outputFilePath ->
                                viewModel.updateButtonEnable(true)
                                if (outputFilePath != null) Utils.scanOutputFile(
                                    this,
                                    outputFilePath
                                ) {
                                    finish()
                                }
                            },
                            { action ->
                                runOnUiThread(action)
                            },
                            { currentProgress ->
                                viewModel.updateProgressWithStatistics(currentProgress)
                            })
                    }
                }
            }
        }

    // Register ActivityResult handler
    private val requestPermissions =
        this.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // Handle permission requests results
            // See the permission example in the Android platform samples: https://github.com/android/platform-samples
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                openVideoPicker()
            } else {
                permissions.forEach { (permission, granted) ->
                    if (!granted) {
                        Toast.makeText(
                            this, "You shall grant the permission: $permission", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IndcitvideoTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    // Greeting("Android")
                    FFmpegProgressView(viewModel, onButtonClick = {
                        useHardware = false
                        if (hasReadExternalStoragePermission()) {
                            openVideoPicker()
                        } else {
                            requestReadExternalStoragePermission()
                        }
                    }, onSecondButtonClick = {
                        useHardware = true
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

    private fun hasReadExternalStoragePermission(): Boolean {
        return permissionsRequired.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestReadExternalStoragePermission() {
        requestPermissions.launch(permissionsRequired)
    }

    private fun openVideoPicker() {
        Utils.scanCameraOutputFile(this);

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        pickVideoLauncher.launch(intent)
    }
}

class FFmpegViewModel : ViewModel() {
    private val _progress = MutableLiveData(0f)
    val progress: LiveData<Float> = _progress

    private val _buttonEnabled = MutableLiveData(true)
    val buttonEnabled: LiveData<Boolean> = _buttonEnabled

    // Mutable live data for start and stop times
    private val _startTime = MutableLiveData<String?>("00:00:00")
    val startTime: LiveData<String?> = _startTime

    private val _stopTime = MutableLiveData<String?>("00:00:00")
    val stopTime: LiveData<String?> = _stopTime

    fun updateProgressWithStatistics(currentProgress: Float) {
        // TODO: Calculate the progress and update the LiveData

        _progress.value = currentProgress.coerceIn(0f, 1f)
    }

    // Functions to update start and stop times
    fun updateStartTime(time: String?) {
        _startTime.value = time
    }

    fun updateStopTime(time: String?) {
        _stopTime.value = time
    }

    fun updateButtonEnable(enabled: Boolean) {
        _buttonEnabled.value = enabled;
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FFmpegProgressView(
    viewModel: FFmpegViewModel, // Pass the viewModel into the composable
    modifier: Modifier = Modifier, onButtonClick: () -> Unit, onSecondButtonClick: () -> Unit
) {
    // Observe LiveData and convert it to State using the collectAsState() function
    val progress by viewModel.progress.observeAsState(0f)
    val startTime by viewModel.startTime.observeAsState("")
    val stopTime by viewModel.stopTime.observeAsState("")
    val buttonEnabled by viewModel.buttonEnabled.observeAsState(true);

    Column(modifier = modifier) {
        Text(text = "Hello FFmpeg!")
        LinearProgressIndicator(progress = progress)
        Spacer(modifier = Modifier.height(8.dp)) // Add some space between the progress bar and the button
        Button(onClick = onButtonClick, enabled = buttonEnabled) {
            Text(text = "Start with ffmpeg")
        }
        Button(onClick = onSecondButtonClick, enabled = buttonEnabled) {
            Text(text = "Start with hardware")
        }

        // Use the observed states for the TextField values
        TextField(value = startTime ?: "", onValueChange = { newStartTime ->
            viewModel.updateStartTime(newStartTime) // Update ViewModel state
        }, label = { Text("Start Time (hh:mm:ss)") })
        TextField(value = stopTime ?: "", onValueChange = { newStopTime ->
            viewModel.updateStopTime(newStopTime) // Update ViewModel state
        }, label = { Text("Stop Time (hh:mm:ss)") })
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = "Hello $name!", modifier = Modifier
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
