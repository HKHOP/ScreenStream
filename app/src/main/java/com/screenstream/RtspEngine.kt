package com.screenstream

import android.content.Intent

interface RtspEngine {
    fun configureVideo(width: Int, height: Int, fps: Int, bitrate: Int, rotation: Int, density: Int): Boolean
    fun configureAudio(): Boolean
    fun start(resultCode: Int, data: Intent): Boolean
    fun stop()
    fun isStreaming(): Boolean
}
