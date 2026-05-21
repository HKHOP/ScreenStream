package com.screenstream

import android.content.Context
import android.content.Intent
import android.util.Log

class ReflectiveRtspEngine private constructor(
    private val instance: Any,
    private val clazz: Class<*>
) : RtspEngine {

    override fun configureVideo(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        rotation: Int,
        density: Int
    ): Boolean {
        return try {
            val m = clazz.getMethod(
                "prepareVideo",
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE
            )
            (m.invoke(instance, width, height, fps, bitrate, rotation, density) as? Boolean) == true
        } catch (_: NoSuchMethodException) {
            try {
                val m = clazz.getMethod(
                    "prepareVideo",
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE
                )
                (m.invoke(instance, width, height, fps, bitrate, rotation) as? Boolean) == true
            } catch (e: Exception) {
                Log.w(TAG, "prepareVideo not available or failed", e)
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "prepareVideo invocation failed", e)
            false
        }
    }

    override fun configureAudio(bitrateKbps: Int, sampleRate: Int, stereo: Boolean): Boolean {
        val bitrateBps = bitrateKbps.coerceIn(32, 320) * 1024

        // Try known/likely signatures first, then fallback to any compatible prepareAudio overload.
        val signatureCandidates = listOf(
            arrayOf<Class<*>>(Integer.TYPE, Integer.TYPE, java.lang.Boolean.TYPE),
            arrayOf<Class<*>>(Integer.TYPE, Integer.TYPE, Integer.TYPE),
            arrayOf<Class<*>>(Integer.TYPE, Integer.TYPE),
            arrayOf<Class<*>>(Integer.TYPE),
            emptyArray<Class<*>>()
        )

        for (params in signatureCandidates) {
            try {
                val m = clazz.getMethod("prepareAudio", *params)
                val args = buildAudioArgs(params, bitrateBps, sampleRate, stereo)
                return (m.invoke(instance, *args) as? Boolean) == true
            } catch (_: NoSuchMethodException) {
                // Continue trying next supported signature.
            } catch (e: Exception) {
                Log.w(TAG, "prepareAudio invocation failed for ${params.joinToString { it.simpleName }}", e)
                return false
            }
        }

        val reflective = clazz.methods.firstOrNull { it.name == "prepareAudio" }
        if (reflective != null) {
            return try {
                val args = buildAudioArgs(reflective.parameterTypes, bitrateBps, sampleRate, stereo)
                (reflective.invoke(instance, *args) as? Boolean) == true
            } catch (e: Exception) {
                Log.w(TAG, "prepareAudio reflective fallback failed", e)
                false
            }
        }

        Log.w(TAG, "prepareAudio not available")
        return false
    }

    override fun configureAudioSource(source: String): Boolean {
        val sourceMethod = clazz.methods.firstOrNull { it.name == "setAudioSource" } ?: return true
        val sourceInt = when (source) {
            "playback" -> 1
            else -> 0
        }
        return try {
            val param = sourceMethod.parameterTypes.firstOrNull()
            when (param) {
                Integer.TYPE, java.lang.Integer::class.java -> sourceMethod.invoke(instance, sourceInt)
                else -> return false
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "setAudioSource invocation failed", e)
            false
        }
    }


    private fun buildAudioArgs(paramTypes: Array<Class<*>>, bitrateBps: Int, sampleRate: Int, stereo: Boolean): Array<Any> {
        val stereoAsInt = if (stereo) 2 else 1
        val values = ArrayList<Any>(paramTypes.size)

        paramTypes.forEachIndexed { index, param ->
            val value: Any = when {
                param == java.lang.Boolean.TYPE || param == java.lang.Boolean::class.java -> stereo
                param == Integer.TYPE || param == java.lang.Integer::class.java -> when (index) {
                    0 -> bitrateBps
                    1 -> sampleRate
                    else -> stereoAsInt
                }
                else -> throw IllegalArgumentException("Unsupported prepareAudio parameter type: $param")
            }
            values.add(value)
        }

        return values.toTypedArray()
    }

    override fun start(resultCode: Int, data: Intent): Boolean {
        try {
            try {
                val startWithParams = clazz.getMethod("startStream", Integer.TYPE, Intent::class.java)
                startWithParams.invoke(instance, resultCode, data)
            } catch (_: NoSuchMethodException) {
                val startNoParams = clazz.getMethod("startStream")
                startNoParams.invoke(instance)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTSP stream", e)
            return false
        }
        return isStreaming()
    }

    override fun stop() {
        try {
            if (isStreaming()) {
                try {
                    val stopMethod = clazz.getMethod("stopStream")
                    stopMethod.invoke(instance)
                } catch (_: NoSuchMethodException) {
                }
            }
            try {
                val releaseMethod = clazz.getMethod("release")
                releaseMethod.invoke(instance)
            } catch (_: NoSuchMethodException) {
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop/release RTSP server reflectively", e)
        }
    }

    override fun isStreaming(): Boolean {
        return try {
            val isStreamingMethod = clazz.getMethod("isStreaming")
            (isStreamingMethod.invoke(instance) as? Boolean) == true
        } catch (_: Exception) {
            true
        }
    }

    companion object {
        private const val TAG = "ReflectiveRtspEngine"

        fun createOrNull(context: Context, port: Int): RtspEngine? {
            val classCandidates = listOf(
                "com.pedro.rtspserver.RtspServerDisplay",
                "com.pedro.library.rtsp.rtspserver.RtspServerDisplay"
            )
            val clazz = classCandidates.firstNotNullOfOrNull { className ->
                try {
                    Class.forName(className)
                } catch (_: Exception) {
                    null
                }
            } ?: return null

            return try {
                val ctor = clazz.constructors.firstOrNull { it.parameterTypes.isNotEmpty() }
                val instance = if (ctor != null) {
                    val params = ctor.parameterTypes.map { paramType ->
                        when {
                            paramType == java.lang.Boolean.TYPE -> java.lang.Boolean.FALSE
                            paramType == java.lang.Integer.TYPE -> Integer.valueOf(port)
                            paramType == java.lang.Integer::class.java -> Integer.valueOf(port)
                            paramType == java.lang.Boolean::class.java -> java.lang.Boolean.FALSE
                            paramType.isAssignableFrom(Context::class.java) -> context
                            else -> null
                        }
                    }.toTypedArray()
                    ctor.newInstance(*params)
                } else {
                    clazz.getDeclaredConstructor().newInstance()
                }

                try {
                    val setPort = clazz.getMethod("setPort", Integer.TYPE)
                    setPort.invoke(instance, Integer.valueOf(port))
                } catch (_: Exception) {
                }

                ReflectiveRtspEngine(instance, clazz)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to instantiate RTSP server reflectively", e)
                null
            }
        }
    }
}
