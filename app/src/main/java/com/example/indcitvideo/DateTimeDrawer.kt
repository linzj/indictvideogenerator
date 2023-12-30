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
        val textBitmap = Bitmap.createBitmap(textureWidth, textureHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(textBitmap)
        val paint = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = textureHeight.toFloat()
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        // Draw the date onto the Bitmap
        canvas.drawColor(android.graphics.Color.TRANSPARENT)
        canvas.drawText(dateFormat.format(creationTime), 0f, paint.textSize, paint)
        // Generate a new OpenGL texture
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        Utils.checkGLError("After gen textures for datetime")
        val textTextureId = textureIds[0]

        // Bind the texture and load the Bitmap data
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTextureId)
        Utils.checkGLError("After bind texture for datetime")
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, textBitmap, 0)
        Utils.checkGLError("After upload texture for datetime")

        // Set texture parameters
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        Utils.checkGLError("After set min filter for datetime")
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        Utils.checkGLError("After set mag filter for datetime")
        return textTextureId
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