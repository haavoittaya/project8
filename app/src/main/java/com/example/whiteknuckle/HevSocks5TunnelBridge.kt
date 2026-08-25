package com.example.whiteknuckle

import android.os.Build
import android.util.Log

object HevSocks5TunnelBridge {
    private const val TAG = "HevSocks5Bridge"
    private const val LOAD_ERROR_CODE = -1001
    private var loadAttempted = false
    private var loaded = false

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (loadAttempted) return loaded
        loadAttempted = true
        loaded = try {
            System.loadLibrary("hev_socks5_tunnel_jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            val stacktrace = Log.getStackTraceString(e)
            Log.e(
                TAG,
                "Failed to load hev_socks5_tunnel_jni. " +
                    "message=${e.message}; supportedAbis=${Build.SUPPORTED_ABIS.joinToString()}.\n$stacktrace"
            )
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Security error while loading hev_socks5_tunnel_jni", e)
            false
        }
        return loaded
    }

    fun runTunnel(
        tunFd: Int,
        configPath: String
    ): Int {
        if (!ensureLoaded()) return LOAD_ERROR_CODE
        return try {
            nativeRunTunnel(tunFd, configPath)
        } catch (e: UnsatisfiedLinkError) {
            val stacktrace = Log.getStackTraceString(e)
            Log.e(
                TAG,
                "Native symbol nativeRunTunnel not found or has wrong JNI signature. " +
                    "Expected: Java_com_example_whiteknuckle_HevSocks5TunnelBridge_nativeRunTunnel.\n$stacktrace"
            )
            LOAD_ERROR_CODE
        }
    }

    fun stopTunnel() {
        if (!ensureLoaded()) return
        try {
            nativeStopTunnel()
        } catch (e: UnsatisfiedLinkError) {
            val stacktrace = Log.getStackTraceString(e)
            Log.e(
                TAG,
                "Native symbol nativeStopTunnel not found or has wrong JNI signature. " +
                    "Expected: Java_com_example_whiteknuckle_HevSocks5TunnelBridge_nativeStopTunnel.\n$stacktrace"
            )
        }
    }

    private external fun nativeRunTunnel(
        tunFd: Int,
        configPath: String
    ): Int

    private external fun nativeStopTunnel()
}
