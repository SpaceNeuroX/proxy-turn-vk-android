package com.glebsbp.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import java.io.BufferedReader
import java.io.InputStreamReader

object PingHelper {
    val pingResults = androidx.compose.runtime.mutableStateMapOf<String, Long>()
    val pingingState = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    /**
     * Executes a ping command via the proxy-turn-vk Go binary in -ping-only mode.
     * Returns the latency in milliseconds, or -1 if it fails or times out.
     */
    suspend fun measurePing(context: android.content.Context, profile: ConnectionProfile): Long = withContext(Dispatchers.IO) {
        android.util.Log.d("PingHelper", "=== Starting ping for profile: ${profile.id} peer=${profile.peer} ===")
        var process: Process? = null
        try {
            val binaryPath = context.applicationInfo.nativeLibraryDir + "/libclient.so"
            val binaryFile = java.io.File(binaryPath)
            if (!binaryFile.exists()) {
                android.util.Log.e("PingHelper", "Binary not found at $binaryPath")
                return@withContext -1L
            }
            android.util.Log.d("PingHelper", "Binary exists: ${binaryFile.canExecute()}")

            // We use the first hash if there are multiple
            android.util.Log.d("PingHelper", "Raw vkHashes: '${profile.vkHashes}' (len=${profile.vkHashes.length})")
            val parts = profile.vkHashes.split(Regex("[,\\s\\n]+"))
            android.util.Log.d("PingHelper", "Split parts: $parts")
            val firstHash = parts.firstOrNull { it.isNotBlank() } ?: ""
            if (firstHash.isBlank()) {
                android.util.Log.e("PingHelper", "No valid hash found in vkHashes='${profile.vkHashes}'")
                return@withContext -1L
            }
            android.util.Log.d("PingHelper", "Hash: ${firstHash.take(8)}... Peer: ${profile.peer}")

            val store = SettingsStore(context)
            val manualPorts = store.manualPortsEnabled.first()
            val defaultPort = if (manualPorts) store.serverDtlsPort.first() else 56000
            val peerWithPort = PeerAddress.ensurePort(profile.peer, defaultPort)

            val goDnsArg = store.resolveGoDnsArg()
            val cmd = mutableListOf(
                binaryPath,
                "-ping-only",
                "-peer", peerWithPort,
                "-vk", firstHash,
                "-password", profile.password,
                "-go-dns", goDnsArg,
            )
            android.util.Log.d("PingHelper", "Command: ${cmd.joinToString(" ") { if (it == profile.password) "***" else it }}")

            val processBuilder = ProcessBuilder(cmd)
            processBuilder.directory(context.filesDir)
            processBuilder.redirectErrorStream(true) // Merge stderr into stdout
            val env = processBuilder.environment()
            env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir

            val pingProcess = processBuilder.start()
            process = pingProcess
            android.util.Log.d("PingHelper", "Process started, reading output...")

            var timeMs = -1L

            // Read merged stdout+stderr synchronously (since redirectErrorStream=true)
            val reader = BufferedReader(InputStreamReader(pingProcess.inputStream))
            launch(Dispatchers.IO) {
                try {
                    reader.forEachLine { line ->
                        android.util.Log.d("PingHelper", "OUTPUT: $line")
                        if (line.startsWith("PING_RESULT|")) {
                            val timeStr = line.substringAfter("PING_RESULT|").trim()
                            timeMs = timeStr.toLongOrNull() ?: -1L
                        } else if (line.startsWith("PING_ERROR|")) {
                            android.util.Log.e("PingHelper", "PING_ERROR: $line")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PingHelper", "Error reading output", e)
                }
            }

            // Wait for process with timeout
            var exitVal = -1
            try {
                withTimeout(20000L) { // 20 seconds - enough for VK cred fetch + DTLS handshake
                    while (pingProcess.isAlive) {
                        delay(100)
                    }
                }
                exitVal = pingProcess.exitValue()
                // Give output reader a moment to finish
                delay(200)
            } catch (e: TimeoutCancellationException) {
                pingProcess.destroyForcibly()
                android.util.Log.e("PingHelper", "Ping process timed out after 20s")
            }

            android.util.Log.d("PingHelper", "Process finished: exitVal=$exitVal timeMs=$timeMs")

            if (timeMs != -1L) {
                return@withContext timeMs
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("PingHelper", "Exception during ping", e)
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()
        }
        return@withContext -1L
    }
}
