package com.example.indcitvideo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

abstract class AbstractDrawer : Drawer {
    companion object {
        const val VERTEX_SHADER_CODE = """
    attribute vec4 aPosition;
    attribute vec2 aTexCoord;
    varying vec2 vTexCoord;
    uniform mat4 uMVPMatrix;
    uniform mat4 uTexMatrix;

    void main() {
        vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        gl_Position = uMVPMatrix * aPosition;
    }
"""

        const val FRAGMENT_SHADER_CODE_EXTERNAL_TEXTRUE = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    varying vec2 vTexCoord;
    uniform samplerExternalOES uTexture;

    void main() {
        gl_FragColor = texture2D(uTexture, vTexCoord);
    }
"""

        const val FRAGMENT_SHADER_CODE = """
    precision mediump float;
    varying vec2 vTexCoord;
    uniform sampler2D uTexture;

    void main() {
        gl_FragColor = texture2D(uTexture, vTexCoord);
    }
"""
        val quadVertices = floatArrayOf(
            // X, Y, Z
            -1.0f, -1.0f, 0.0f, 1.0f, -1.0f, 0.0f, -1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f
        )

        val quadTexCoordsExternal = floatArrayOf(
            // U, V
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
        )

        val quadTexCoords = floatArrayOf(
            // U, V
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,
        )


        val identityMatrix = floatArrayOf(
            1f, 0f, 0f, 0f, // Column 1
            0f, 1f, 0f, 0f, // Column 2
            0f, 0f, 1f, 0f, // Column 3
            0f, 0f, 0f, 1f  // Column 4
        )

        val vertexBuffer: FloatBuffer =
            ByteBuffer.allocateDirect(quadVertices.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply {
                    put(quadVertices)
                    position(0)
                }

        val texCoordBufferExternal: FloatBuffer =
            ByteBuffer.allocateDirect(quadTexCoordsExternal.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply {
                    put(quadTexCoordsExternal)
                    position(0)
                }

        val texCoordBuffer: FloatBuffer =
            ByteBuffer.allocateDirect(quadTexCoords.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply {
                    put(quadTexCoords)
                    position(0)
                }

        private fun loadShader(type: Int, shaderCode: String): Int {
            // Create a new shader object
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) {
                throw RuntimeException("Error creating shader.")
            }

            // Set the source code for the shader and compile it
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)

            // Check if the shader compiled successfully
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val error = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Error compiling shader: $error")
            }

            return shader
        }

        fun loadShaderProgram(
            vertexShaderCode: String,
            fragmentShaderCode: String,
            textureTarget: Int,
            vertexBuffer: FloatBuffer,
            texCoordBuffer: FloatBuffer
        ): ShaderProgramComponent {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

            val shaderProgram = GLES20.glCreateProgram().also {
                Utils.checkGLError("After glCreateProgram")
                GLES20.glAttachShader(it, vertexShader)
                Utils.checkGLError("After glAttachShader vertex")
                GLES20.glAttachShader(it, fragmentShader)
                Utils.checkGLError("After glAttachShader fragment")
                GLES20.glLinkProgram(it)
                Utils.checkGLError("After linkprogram")
            }

            val positionHandle = GLES20.glGetAttribLocation(shaderProgram, "aPosition")
            Utils.checkGLError("After get position vertex attrib")
            val texCoordHandle = GLES20.glGetAttribLocation(shaderProgram, "aTexCoord")
            Utils.checkGLError("After get position texcoord attrib")
            val mvpMatrixHandle =
                GLES20.glGetUniformLocation(shaderProgram, "uMVPMatrix")
            Utils.checkGLError("After get position mvp uniform")
            val texMatrixHandle =
                GLES20.glGetUniformLocation(shaderProgram, "uTexMatrix")
            Utils.checkGLError("After get position texmatrix uniform")
            val textureHandle = GLES20.glGetUniformLocation(shaderProgram, "uTexture")
            Utils.checkGLError("After get position texturehandle uniform")
            return ShaderProgramComponent(
                shaderProgram,
                positionHandle,
                texCoordHandle,
                mvpMatrixHandle,
                texMatrixHandle,
                textureHandle,
                textureTarget,
                vertexBuffer,
                texCoordBuffer,
            )

        }

        fun drawTexture(
            shaderProgram: ShaderProgramComponent,
            textureId: Int,
            mvpMatrix: FloatArray,
            transformMatrix: FloatArray
        ) {
            // Use the shader program
            GLES20.glUseProgram(shaderProgram.shaderProgram)
            Utils.checkGLError("After UseProgram")

            // Pass in the vertex data
            shaderProgram.vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(
                shaderProgram.positionHandle,
                3,
                GLES20.GL_FLOAT,
                false,
                0,
                shaderProgram.vertexBuffer
            )
            Utils.checkGLError("After set vertex attrib for position")
            GLES20.glEnableVertexAttribArray(shaderProgram.positionHandle)
            Utils.checkGLError("After set enable vertex attrib for position")

            // Pass in the texture coordinate data
            shaderProgram.texCordBuffer.position(0)
            GLES20.glVertexAttribPointer(
                shaderProgram.texCordHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                shaderProgram.texCordBuffer
            )
            Utils.checkGLError("After set texcoord attrib")
            GLES20.glEnableVertexAttribArray(shaderProgram.texCordHandle)
            Utils.checkGLError("After enable  texcoord attrib")

            // Set the active texture unit to texture unit 0
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            Utils.checkGLError("After active texture")

            // Bind the texture to this unit
            GLES20.glBindTexture(shaderProgram.textureTarget, textureId)
            Utils.checkGLError("After bind texture to target ${shaderProgram.textureTarget}")
            GLES20.glUniform1i(shaderProgram.textureHandle, 0)
            Utils.checkGLError("After set texture handle to uniform, target ${shaderProgram.textureTarget}, texture handle: ${shaderProgram.textureHandle}")

            GLES20.glUniformMatrix4fv(shaderProgram.mvpMatrixHandle, 1, false, mvpMatrix, 0)
            Utils.checkGLError("After set mvp data to uniform")

            // Apply the texture transformation matrix
            GLES20.glUniformMatrix4fv(shaderProgram.texMatrixHandle, 1, false, transformMatrix, 0)
            Utils.checkGLError("After set tex matrix data to uniform")

            // Draw the quad
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            Utils.checkGLError("After draw array")

            // Disable vertex array
            GLES20.glDisableVertexAttribArray(shaderProgram.positionHandle)
            Utils.checkGLError("After disable position attrib")
            GLES20.glDisableVertexAttribArray(shaderProgram.texCordHandle)
            Utils.checkGLError("After disable texcoord attrib")
            GLES20.glBindTexture(shaderProgram.textureTarget, 0)
            Utils.checkGLError("After bind texture ${shaderProgram.textureTarget} to 0")
            GLES20.glUseProgram(0)
            Utils.checkGLError("After use program to 0")
        }

        fun measureTextWidth(text: String, textureHeight: Int): Float {
            val paint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = textureHeight.toFloat()
                isAntiAlias = true
                typeface = Typeface.MONOSPACE
            }
            // Measure the text
            return paint.measureText(text)
        }

        fun prepareStringTexture(textureWidth: Int, textureHeight: Int, text: String): Int {
            val textBitmap =
                Bitmap.createBitmap(textureWidth, textureHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(textBitmap)
            val paint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = textureHeight.toFloat() - 2.0f
                isAntiAlias = true
                typeface = Typeface.MONOSPACE
            }

            // Draw the date onto the Bitmap
            canvas.drawColor(android.graphics.Color.TRANSPARENT)
            canvas.drawText(text, 0f, paint.textSize, paint)
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
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            Utils.checkGLError("After set min filter for datetime")
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
            Utils.checkGLError("After set mag filter for datetime")
            return textTextureId
        }
    }
}