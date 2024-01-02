package com.example.indcitvideo

import android.opengl.GLES20
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GeolocationDrawer(
    private val geolocation: Pair<Double, Double>,
    private val surfaceWidth: Int,
    private val surfaceHeight: Int,
    private val bottomLineAccountor: BottomLineAccountor
) : AbstractDrawer() {

    companion object {
        const val apiKey = ""
        const val textureHeight = 64
        const val A = 6378245.0 // Earth's radius in meters (based on WGS-84)
        const val EE = 0.00669342162296594323 // WGS-84 first eccentricity squared
        fun transformLat(x: Double, y: Double): Double {
            var lat =
                -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
            lat += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
            lat += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
            lat += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
            return lat
        }

        fun transformLon(x: Double, y: Double): Double {
            var lon = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
            lon += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
            lon += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
            lon += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
            return lon
        }

        fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
            val dLat = transformLat(lon - 105.0, lat - 35.0)
            val dLon = transformLon(lon - 105.0, lat - 35.0)
            val radLat = lat / 180.0 * Math.PI
            var magic = Math.sin(radLat)
            magic = 1 - EE * magic * magic
            val sqrtMagic = Math.sqrt(magic)
            val offsetLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
            val offsetLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI)
            val mgLat = lat + offsetLat
            val mgLon = lon + offsetLon
            return Pair(mgLat, mgLon)
        }
    }

    private lateinit var shaderProgramNormal: ShaderProgramComponent
    var location: String? = null
    var textureWidth = 0

    @Throws(IOException::class)
    fun getLocationFromCoordsSync(lat: Double, lon: Double, apiKey: String): String? {
        val client = OkHttpClient()
        val (convertedLat, convertedLon) = wgs84ToGcj02(lat, lon)
        val url =
            "https://restapi.amap.com/v3/geocode/regeo?output=JSON&location=$convertedLon,$convertedLat&key=$apiKey&radius=1000&extensions=all"

        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val responseData = response.body?.string()
                if (responseData != null) {
                    val jsonObject = JSONObject(responseData)
                    return jsonObject.getJSONObject("regeocode").getString("formatted_address")
                }
            }
        }
        return null
    }

    override fun preapreResources() {
        try {
            if (apiKey != "")
                location = getLocationFromCoordsSync(geolocation.first, geolocation.second, apiKey)
            if (location == null)
                return
            textureWidth = measureTextWidth(
                location!!,
                textureHeight
            ).toInt()
            val vertexCoordForText =
                bottomLineAccountor.calculateTextureVertex(
                    surfaceWidth,
                    surfaceHeight,
                    textureWidth,
                    DateTimeDrawer.textureHeight
                )
            val vertexCoordBufferForText =
                ByteBuffer.allocateDirect(vertexCoordForText.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer().apply {
                        put(vertexCoordForText)
                        position(0)
                    }
            shaderProgramNormal = loadShaderProgram(
                VERTEX_SHADER_CODE,
                FRAGMENT_SHADER_CODE,
                GLES20.GL_TEXTURE_2D,
                vertexCoordBufferForText,
                texCoordBuffer
            )
        } catch (e: Exception) {
        }
    }

    override fun draw() {
        if (location == null) return
        val geolocationTex = prepareGeolocationTexture()
        Utils.checkGLError("After prepareDatetimeTexture")
        // Enable blending
        GLES20.glEnable(GLES20.GL_BLEND)
        // Set the blending function
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawTexture(
            shaderProgramNormal,
            geolocationTex,
            identityMatrix,
            identityMatrix
        )
        GLES20.glDisable(GLES20.GL_BLEND)
        Utils.checkGLError("After draw datetime texture")
        releaseResources(geolocationTex)
        Utils.checkGLError("After releaseResources")
    }

    private fun prepareGeolocationTexture(): Int {
        return prepareStringTexture(textureWidth, textureHeight, location!!)
    }

    private fun releaseResources(geolocationTex: Int) {
        val textureIds = intArrayOf(geolocationTex)
        GLES20.glDeleteTextures(1, textureIds, 0)
    }
}