package com.example.indcitvideo

class BottomLineAccountor {
    companion object {
        const val paddingPixels = 10.0f
    }
    var currentConsumed = 0.0f
    fun calculateTextureVertex(
        surfaceWidth: Int, surfaceHeight: Int, textureWidth: Int, textureHeight: Int
    ): FloatArray {
        val textureWidthNormalized = textureWidth.toFloat() / surfaceWidth * 2.0f
        val textureHeightNormalized = textureHeight.toFloat() / surfaceHeight * 2.0f
        val paddingNormalized = paddingPixels / surfaceHeight * 2.0f

        // Calculate the right and top positions
        val right = 1.0f
        val bottom = -1.0f

        val quadVertices = floatArrayOf(
            // Since OpenGL's default coordinates range from -1 to 1, we have to normalize our texture size accordingly
            right - textureWidthNormalized,
            bottom + currentConsumed,
            0.0f, // Bottom-left corner
            right,
            bottom + currentConsumed,
            0.0f, // Bottom-right corner
            right - textureWidthNormalized,
            bottom + textureHeightNormalized + currentConsumed,
            0.0f, // Top-left corner
            right,
            bottom + textureHeightNormalized + currentConsumed,
            0.0f, // Top-right corner

        )

        currentConsumed += textureHeightNormalized + paddingNormalized
        return quadVertices
    }
}