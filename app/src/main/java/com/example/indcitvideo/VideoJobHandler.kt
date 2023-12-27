package com.example.indcitvideo

import android.content.Context
import android.net.Uri

interface VideoJobHandler {
    fun handleWork(
        context: Context,
        uri: Uri,
        startTime: String?,
        stopTime: String?,
        finishAction: (String?) -> Unit,
        runOnUi: (() -> Unit) -> Unit,
        updateProgress: (Float) -> Unit
    );
}