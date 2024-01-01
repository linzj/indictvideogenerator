package com.example.indcitvideo

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.TimeZone


class ShaderProgramComponent(
    val shaderProgram: Int,
    val positionHandle: Int,
    val texCoordHandle: Int,
    val mvpMatrixHandle: Int,
    val texMatrixHandle: Int,
    val textureHandle: Int,
    val textureTarget: Int,
    val vertexBuffer: FloatBuffer,
    val texCoordBuffer: FloatBuffer
) {}

class HardwareVideoJobWorker(
    private val fileDescriptor: FileDescriptor,
    private val runOnUi: (() -> Unit) -> Unit,
    private val finishAction: (String?) -> Unit,
    private val updateProgress: (Float) -> Unit,
    private val creationTime: Date,
    private val totalDurationInMilliseconds: Long,
    private val startMills: Long?,
    private val stopMills: Long?,
    private val geolocation: Pair<Double, Double>?
) {
    private var mFrameAvailable: Boolean = false
    private lateinit var shaderProgramNormal: ShaderProgramComponent
    private lateinit var shaderProgramExternalTexture: ShaderProgramComponent
    private lateinit var videoTextureDrawer: VideoTextureDrawer

    private var width: Int = 0
    private var height: Int = 0
    private var bitRate: Int = 0
    private var frameRate: Int = 0

    private val bottomLineAccountor = BottomLineAccountor()
    private var drawers: Array<Drawer>? = null

    companion object {
        const val timeoutUs = 1000L
        fun getRecommendedBitrate(width: Int, height: Int): Int {
            // These are approximate bitrates for H.264 standard quality
            // Adjust these values based on your specific needs and quality expectations
            val bitrateHighQuality8K = 200_000_000   // For 8K
            val bitrateHighQuality4K = 100_000_000   // For 4K (UHD)
            val bitrateHighQuality2K = 12_000_000   // For 2K (QHD)
            val bitrateHighQuality1080p = 5_000_000 // For 1080p (FHD)
            val bitrateHighQuality720p = 2_500_000  // For 720p (HD)
            val bitrateHighQuality480p = 1_000_000  // For 480p (SD)
            val bitrateHighQuality360p = 750_000    // For 360p
            val bitrateHighQuality240p = 500_000    // For 240p

            val pixels = width * height
            return when {
                pixels >= 7680 * 4320 -> bitrateHighQuality8K
                pixels >= 3840 * 2160 -> bitrateHighQuality4K
                pixels >= 2560 * 1440 -> bitrateHighQuality2K
                pixels >= 1920 * 1080 -> bitrateHighQuality1080p
                pixels >= 1280 * 720 -> bitrateHighQuality720p
                pixels >= 854 * 480 -> bitrateHighQuality480p
                pixels >= 640 * 360 -> bitrateHighQuality360p
                else -> bitrateHighQuality240p
            }
        }
    }

    val extractor: MediaExtractor = MediaExtractor().apply {
        // Initialize the extractor with the given file descriptor
        setDataSource(fileDescriptor)
    }

    fun selectTrack(): Int {
        var videoTrackIndex = -1
        val numTracks = extractor.trackCount
        for (i in 0 until numTracks) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime!!.startsWith("video/")) {
                videoTrackIndex = i
                break
            }
        }

        if (videoTrackIndex == -1) {
            runOnUi {
                finishAction(null)
            }
            throw IOException("No video track found in URI.")
        }

        extractor.selectTrack(videoTrackIndex)
        return videoTrackIndex
    }

    class StartDecoderResult(
        val decoder: MediaCodec,
        val surface: Surface,
        val surfaceTexture: SurfaceTexture,
        val externalTextureId: Int
    ) {}

    fun startDecoder(videoTrackIndex: Int): StartDecoderResult {
        // Step 1: Retrieving the video format from the extractor
        val format = extractor.getTrackFormat(videoTrackIndex)

        // Step 2: Create a MediaCodec decoder for the MIME type
        val mime = format.getString(MediaFormat.KEY_MIME)
        val decoder = MediaCodec.createDecoderByType(mime!!)

        // Step 3: Create an output surface
        // Here we'll create an off-screen surface using a SurfaceTexture. Note that the texture ID (12345)
        // is arbitrary and should be generated with OpenGL functions if used for rendering.
        // If you're using this in an actual application, you'll need to integrate it with your rendering pipeline.

        val textures = IntArray(1)

        // Typically, you would use OpenGL functions to generate textures, e.g., glGenTextures(1, textures, 0).
        val textureId = textures[0] // Replace this with the actual generated texture ID.


        val surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener {
            synchronized(mFrameSyncObject) {
                if (mFrameAvailable) {
                    throw RuntimeException("mFrameAvailable already set, frame could be dropped")
                }
                mFrameAvailable = true
                (mFrameSyncObject as Object).notifyAll()
            }
        }
        val outputSurface = Surface(surfaceTexture)


        // Step 4: Configure the decoder with the format and the output surface
        decoder.configure(format, outputSurface, null, 0)

        // Step 5: Start the decoder
        decoder.start()
        // setup the context data
        width = format.getInteger(MediaFormat.KEY_WIDTH)
        height = format.getInteger(MediaFormat.KEY_HEIGHT)

        // Perform checks because not all formats specify the frame rate
        frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            format.getInteger(MediaFormat.KEY_FRAME_RATE)
        } else {
            // Choose a default frame rate if it's not specified in the format
            30
        }
        bitRate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            format.getInteger(MediaFormat.KEY_BIT_RATE)
        } else {
            0
        }
        return StartDecoderResult(decoder, outputSurface, surfaceTexture, textureId)
    }

    fun startEncoder(
        mime: String
    ): Pair<MediaCodec, Surface> {
        // Step 1: Create an encoder
        val encoder = MediaCodec.createEncoderByType(mime)
        var targetBitRate = bitRate
        if (targetBitRate == 0) targetBitRate = getRecommendedBitrate(width, height)

        // Step 2: Setup output video format for MP4
        // This is just an example format, adjust parameters to suit your needs
        val outputFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5) // Example I-frame interval
        }
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        // Step 3a: Create an input surface from the encoder
        val inputSurface = encoder.createInputSurface()

        // Step 4: Start the encoder
        encoder.start()

        return Pair(encoder, inputSurface)
    }

    fun checkEncoder() {
        val mimeType = "video/avc"

// Find the encoder that supports the MIME type

// Find the encoder that supports the MIME type
        var codecInfo: MediaCodecInfo? = null
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val codecInfos = codecList.codecInfos
        for (info in codecInfos) {
            if (!info.isEncoder) {
                continue
            }
            val types = info.supportedTypes
            for (type in types) {
                if (type.equals(mimeType, ignoreCase = true)) {
                    codecInfo = info
                    break
                }
            }
            if (codecInfo != null) {
                break
            }
        }

// Check if the encoder is found

// Check if the encoder is found
        if (codecInfo == null) {
            Utils.logD("No suitable encoder found for MIME type: $mimeType")
        } else {
            // Get the capabilities for the MIME type
            val capabilities = codecInfo.getCapabilitiesForType(mimeType)

            // Example: Check color format support
            for (colorFormat in capabilities.colorFormats) {
                Utils.logI("Supported color format: $colorFormat")
                if (colorFormat == CodecCapabilities.COLOR_FormatSurface) {
                    Utils.logI("COLOR_FormatSurface is supported")
                }
            }

            // Example: Check profile and level support
            val profileLevels = capabilities.profileLevels
            for (profileLevel in profileLevels) {
                Utils.logI(
                    "Supported profile: " + profileLevel.profile + ", level: " + profileLevel.level
                )
            }

            // You can check other capabilities like bitrate range, frame rate range, etc.
        }
    }

    fun createGLContextForInputSurface(inputSurface: Surface): Triple<EGLContext, EGLSurface, EGLDisplay> {
        // Get the default EGL display
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("Unable to get EGL14 display")
        }

        // Initialize EGL
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Unable to initialize EGL14")
        }

        // Configure EGL for recording and choose a configuration
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE,
            8,
            EGL14.EGL_GREEN_SIZE,
            8,
            EGL14.EGL_BLUE_SIZE,
            8,
            EGL14.EGL_ALPHA_SIZE,
            8,
            EGL14.EGL_RENDERABLE_TYPE,
            EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val eglConfig = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(
            eglDisplay, attribList, 0, eglConfig, 0, eglConfig.size, numConfigs, 0
        )

        // Specify the context client version (2 for OpenGL ES 2.0)
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE
        )
        val eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Unable to create EGLContext")
        }

        // Create an EGL surface associated with the input surface
        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig[0], inputSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("Unable to create EGLSurface")
        }

        // Make the context and surface current
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("Unable to make EGL context current")
        }

        // Return the created context and surface
        return Triple(eglContext, eglSurface, eglDisplay)
    }

    private val mFrameSyncObject = Any()

    fun prepareDrawers() {
        videoTextureDrawer = VideoTextureDrawer()
        val dateTimeDrawer = DateTimeDrawer(creationTime, bottomLineAccountor, width, height)
        drawers = arrayOf(videoTextureDrawer, dateTimeDrawer)
        if (geolocation != null) {
            val geolocationDrawer =
                GeolocationDrawer(geolocation, width, height, bottomLineAccountor)
            drawers = drawers!! + geolocationDrawer
        }
        drawers!!.forEach { drawer -> drawer.preapreResources() }
    }


    private fun updateCreationTime() {
        // Increase the creation time by the time per frame
        val timePerFrameMs = (1000 / frameRate).toLong()
        val newCreationTime = Date(creationTime.time + timePerFrameMs)

        // Update the creation time
        creationTime.time = newCreationTime.time
    }

    fun awaitNewImage() {
        val TIMEOUT_MS = 2500
        synchronized(mFrameSyncObject) {
            while (!mFrameAvailable) {
                try {
                    // Wait for onFrameAvailable() to signal us.  Use a timeout to avoid
                    // stalling the test if it doesn't arrive.
                    (mFrameSyncObject as Object).wait(TIMEOUT_MS.toLong())
                    if (!mFrameAvailable) {
                        // TODO: if "spurious wakeup", continue while loop
                        throw java.lang.RuntimeException("frame wait timed out")
                    }
                } catch (ie: InterruptedException) {
                    // shouldn't happen
                    throw java.lang.RuntimeException(ie)
                }
            }
            mFrameAvailable = false
        }
    }

    fun process(
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        surfaceTexture: SurfaceTexture,
        externalTextureId: Int,
        eglDisplay: EGLDisplay,
        eglSurface: EGLSurface
    ) {
        var isEOS = false
        var decodedFrameCount = 0L
        var encodedFrameCount = 0L
        var videoTrackIndex: Int = -1
        // Create a BufferInfo object
        val bufferInfo = MediaCodec.BufferInfo()
        // Create a queue to hold the presentation time stamps
        val ptsQueue: LinkedList<Long> = LinkedList()
        val targetFrames = (frameRate / 1000f * totalDurationInMilliseconds)
        val startFrame = startMills?.let { frameRate / 1000f * it }
        val stopFrame = stopMills?.let { frameRate / 1000f * it }

        while (!isEOS) {
            // Extract and decode frames from the extractor
            val inIndex: Int = decoder.dequeueInputBuffer(timeoutUs)
            if (inIndex >= 0) {
                val buffer: ByteBuffer = decoder.getInputBuffer(inIndex)
                    ?: throw IllegalStateException("null decoder buffer")
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    // End of stream -- send empty frame with EOS flag set
                    decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    isEOS = true
                } else {
                    val presentationTimeUs = extractor.sampleTime
                    decoder.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                    extractor.advance()
                }
            }
            // Process and encode frames
            var outIndex: Int = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            while (outIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (outIndex >= 0) {
                    val doRender =
                        bufferInfo.size != 0 && (startFrame == null || decodedFrameCount >= startFrame)
                    decoder.releaseOutputBuffer(outIndex, doRender)
                    if (doRender) {
                        awaitNewImage()
                        // Retrieve the presentation time stamp from the buffer info
                        val presentationTimeUs = bufferInfo.presentationTimeUs

                        // Add the PTS to the queue
                        ptsQueue.add(presentationTimeUs)
                        decodedFrameCount++;
                        // skip render if not reach startFrame
                        GLES20.glViewport(0, 0, width, height)
                        Utils.checkGLError("After set view port")
                        surfaceTexture.updateTexImage()
                        Utils.checkGLError("After surfacetexture updateTexImage")
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        Utils.checkGLError("After clear color")
                        val transformMatrix = FloatArray(16)
                        surfaceTexture.getTransformMatrix(transformMatrix)
                        // update video texture drawer.
                        videoTextureDrawer.updateExternalTextureId(externalTextureId)
                        videoTextureDrawer.updateTransformMatrix(transformMatrix)
                        drawers?.forEach { drawer ->
                            drawer.draw()
                        }
                        Utils.checkGLError("After draws ")
                        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                        updateCreationTime()
                    } else {
                        decodedFrameCount++
                    }
                }
                outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            }
            // Check if current decoded frame has reach stopFrame or EOS
            if (stopFrame != null && decodedFrameCount >= stopFrame)
                isEOS = true
            // Handle other cases like output format change, etc.
            if (isEOS) {
                encoder.signalEndOfInputStream();
            }
            var bufferIndex: Int = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            while (bufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrackIndex = muxer.addTrack(encoder.outputFormat) // after format change
                    muxer.start()
                }

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    bufferInfo.size = 0
                }

                if (bufferIndex >= 0) {
                    // Process the buffer here
                    if (bufferInfo.size != 0) {
                        val outputBuffer = encoder.getOutputBuffer(bufferIndex);
                        // Check if there is a PTS available in the queue
                        if (ptsQueue.isNotEmpty()) {
                            // Retrieve and remove the PTS from the queue
                            val pts = ptsQueue.remove()

                            // Set the PTS to the encoder's buffer info
                            bufferInfo.presentationTimeUs = pts
                        }

                        if (outputBuffer != null) {

                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);
                            encodedFrameCount++
                            // update the progress
                            runOnUi {
                                updateProgress(encodedFrameCount / targetFrames)
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(bufferIndex, false)
                }

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    if (!isEOS)
                        Utils.logD("unexpected encoder eos")
                    else
                        Utils.logD("expected encoder eos")
                    break
                }

                bufferIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            }
        }
        Utils.logD("we have decoded $decodedFrameCount frames, and encoded $encodedFrameCount frames")
        encoder.stop()
        if (videoTrackIndex != -1)
            muxer.stop()
    }

    fun createMuxer(outputFileDescriptor: FileDescriptor): MediaMuxer {
        val muxer = MediaMuxer(outputFileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return muxer
    }
}

class HardwareVideoJobHandler : VideoJobHandler {
    override fun handleWork(
        context: Context,
        uri: Uri,
        startTime: String?,
        stopTime: String?,
        finishAction: (String?) -> Unit,
        runOnUi: (() -> Unit) -> Unit,
        updateProgress: (Float) -> Unit
    ) {
        // Extract creation time first or fd may lead to interference between MediaMetadataRetriever
        // and MediaExtractor
        val retriever = MediaMetadataRetriever()
        var creationTime: Date;
        var totalDurationInMilliseconds: Long

        try {
            retriever.setDataSource(context, uri)
            val creationTimeString =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: ""
            val dateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            creationTime = dateFormat.parse(creationTimeString)
                ?: throw IllegalStateException("unable to parse $creationTimeString")
            totalDurationInMilliseconds =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
        } finally {
            retriever.close()
        }

        var geolocation = Utils.extractGpsLocationFromMp4(context, uri)

        totalDurationInMilliseconds =
            Utils.adjustTotalTime(startTime, stopTime, totalDurationInMilliseconds);

        val startMills = Utils.timeStringToMills(startTime)
        val stopMills = Utils.timeStringToMills(stopTime)

        val outputFile = Utils.buildOutputFile(context, uri)
            ?: throw IllegalStateException("can not get outputFilePath from uri: $uri")
        val outputFileFd = outputFile.fileDescriptor

        val fd = context.contentResolver.openFileDescriptor(uri, "r")?.fileDescriptor
            ?: throw IllegalStateException("can not get fd from uri: $uri")
        Thread {
            val worker = HardwareVideoJobWorker(
                fd,
                runOnUi,
                finishAction,
                updateProgress,
                creationTime,
                totalDurationInMilliseconds,
                startMills,
                stopMills,
                geolocation
            )
            val videoTrackIndex = worker.selectTrack()
            var surface: Surface? = null
            var surfaceTexture: SurfaceTexture? = null
            var eglDisplay: EGLDisplay? = null
            var eglSurface: EGLSurface? = null
            var eglContext: EGLContext? = null
            var muxerToRelease: MediaMuxer? = null;
            var encoderToRelease: MediaCodec? = null
            var decoderToRelease: MediaCodec? = null
            try {
                val startDecoderResult = worker.startDecoder(videoTrackIndex)
                surface = startDecoderResult.surface
                surfaceTexture = startDecoderResult.surfaceTexture
                decoderToRelease = startDecoderResult.decoder
                worker.checkEncoder()

                val (encoder, inputSurface) = worker.startEncoder(
                    "video/avc"
                )
                encoderToRelease = encoder
                val (neweglContext, neweglSurface, neweglDisplay) = worker.createGLContextForInputSurface(
                    inputSurface
                )
                eglDisplay = neweglDisplay
                eglSurface = neweglSurface
                eglContext = neweglContext
                worker.prepareDrawers()
                val muxer = worker.createMuxer(outputFileFd)
                muxerToRelease = muxer
                worker.process(
                    startDecoderResult.decoder,
                    encoder,
                    muxer,
                    surfaceTexture,
                    startDecoderResult.externalTextureId,
                    eglDisplay,
                    eglSurface
                )
            } finally {
                if (eglDisplay != null && eglSurface != null) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglDisplay != null && eglContext != null) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                surface?.release()
                surfaceTexture?.release()
                muxerToRelease?.release()
                encoderToRelease?.release();
                decoderToRelease?.release()
            }
            runOnUi {
                finishAction("indicted.mp4")
            }
        }.start()
    }
}