package com.example.indcitvideo

import android.opengl.GLES11Ext

class VideoTextureDrawer : AbstractDrawer() {
    private lateinit var shaderProgramExternalTexture: ShaderProgramComponent

    private var transformMatrix = identityMatrix
    private var externalTextureId = 0

    fun updateTransformMatrix(newTransformMatrix: FloatArray) {
        transformMatrix = newTransformMatrix
    }

    fun updateExternalTextureId(newExternalTextureId: Int) {
        externalTextureId = newExternalTextureId
    }

    override fun preapreResources() {
        shaderProgramExternalTexture = loadShaderProgram(
            VERTEX_SHADER_CODE,
            FRAGMENT_SHADER_CODE_EXTERNAL_TEXTRUE,
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            vertexBuffer,
            texCoordBufferExternal
        )
    }

    override fun draw() {
        drawTexture(
            shaderProgramExternalTexture, externalTextureId, identityMatrix, transformMatrix
        );
    }
}