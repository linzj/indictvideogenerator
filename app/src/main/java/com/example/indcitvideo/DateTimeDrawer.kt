package com.example.indcitvideo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DateTimeDrawer(
    private val creationTime: Date,
    private val bottomLineAccountor: BottomLineAccountor,
    private val surfaceWidth: Int,
    private val surfaceHeight: Int
) : AbstractDrawer() {
    companion object {
        const val textureHeight = 64
    }

    private lateinit var shaderProgramNormal: ShaderProgramComponent

    var textureWidth = 256

    private fun prepareDateTimeTexture(): Int {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val text = dateFormat.format(creationTime)
        return prepareStringTexture(textureWidth, textureHeight, text)
    }

    private fun releaseResources(dateTimeFormatTex: Int) {
        val textureIds = intArrayOf(dateTimeFormatTex)
        GLES20.glDeleteTextures(1, textureIds, 0)
    }

    override fun preapreResources() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        textureWidth = measureTextWidth(dateFormat.format(creationTime), textureHeight).toInt()
        val vertexCoordForText =
            bottomLineAccountor.calculateTextureVertex(
                surfaceWidth,
                surfaceHeight,
                textureWidth,
                textureHeight
            )
        val vertexCoordBufferForText =
            ByteBuffer.allocateDirect(vertexCoordForText.size * 4).order(ByteOrder.nativeOrder())
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
    }

    override fun draw() {
        val dateTimeFormatTex = prepareDateTimeTexture()
        Utils.checkGLError("After prepareDatetimeTexture")
        // Enable blending
        GLES20.glEnable(GLES20.GL_BLEND)
        // Set the blending function
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawTexture(
            shaderProgramNormal,
            dateTimeFormatTex,
            identityMatrix,
            identityMatrix
        )
        GLES20.glDisable(GLES20.GL_BLEND)
        Utils.checkGLError("After draw datetime texture")
        releaseResources(dateTimeFormatTex)
        Utils.checkGLError("After releaseResources")
    }
}