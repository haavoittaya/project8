package com.example.whiteknuckle

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

class BinaryManager(private val context: Context) {

    fun getBinaryFile(): File {
        return File(context.applicationInfo.nativeLibraryDir, "libolcrtc-android.so")
    }

    fun extractCaBundle(): File {
        val certFile = File(context.filesDir, "cacert.pem")
        if (certFile.exists()) certFile.delete()
        context.assets.open("cacert.pem").use { input ->
            certFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        if (!certFile.exists() || certFile.length() == 0L) {
            throw IOException("Failed to extract cacert.pem to ${certFile.absolutePath}")
        }
        Log.d("BinaryManager", "Extracted cacert.pem size=${certFile.length()} bytes")
        return certFile
    }

    fun prepareResolvConf(): File {
        val resolvFile = File(context.filesDir, "resolv.conf")
        resolvFile.writeText(
            """
            nameserver 77.88.8.8
            nameserver 1.1.1.1
            """.trimIndent()
        )
        return resolvFile
    }

    fun generateConfigFile(rawYaml: String): File {
        val configFile = File(context.filesDir, "client.yaml")
        if (configFile.exists()) configFile.delete()
        configFile.writeText(rawYaml)
        return configFile
    }
}