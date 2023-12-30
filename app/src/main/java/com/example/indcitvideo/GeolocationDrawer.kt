package com.example.indcitvideo

import android.opengl.GLES20
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GeolocationDrawer(
    private val geolocation: FloatArray,
    private val surfaceWidth: Int,
    private val surfaceHeight: Int,
    private val bottomLineAccountor: BottomLineAccountor
) : AbstractDrawer() {

    companion object {
        const val apiKey = ""
        const val textureHeight = 64
    }

    private lateinit var shaderProgramNormal: ShaderProgramComponent
    var location: String? = null
    var textureWidth = 0

    @Throws(IOException::class)
    fun getLocationFromCoordsSync(lat: Float, lon: Float, apiKey: String): String? {
        val client = OkHttpClient()
        val url =
            "https://restapi.amap.com/v3/geocode/regeo?output=JSON&location=$lon,$lat&key=$apiKey&radius=1000&extensions=all"

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
                location = getLocationFromCoordsSync(geolocation[0], geolocation[1], apiKey)
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
        } catch (e: IOException) {
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