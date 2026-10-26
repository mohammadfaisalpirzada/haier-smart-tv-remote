package com.mastersahib.haierremote

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.mastersahib.haierremote.androidLib.AndroidRemoteContext
import com.mastersahib.haierremote.androidLib.AndroidRemoteTv
import com.mastersahib.haierremote.androidLib.AndroidTvListener
import com.mastersahib.haierremote.androidLib.remote.Remotemessage
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val tag = "HaierRemoteActivity"
    private lateinit var webView: WebView

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var remoteTv: AndroidRemoteTv? = null
    private var currentTvIp = "192.168.0.107"
    private var isConnected = false
    private var needsPairing = false
    private var lastError = ""

    private val prefs by lazy {
        getSharedPreferences("haier_remote_prefs", Context.MODE_PRIVATE)
    }

    private val tvListener = object : AndroidTvListener {
        override fun onSessionCreated() {
            Log.d(tag, "Session created")
        }

        override fun onSecretRequested() {
            Log.d(tag, "Secret requested by TV")
            needsPairing = true
            isConnected = false
            mainHandler.post {
                webView.evaluateJavascript("window.onSecretRequested && window.onSecretRequested();", null)
            }
        }

        override fun onPaired() {
            Log.d(tag, "Paired successfully")
            needsPairing = false
            mainHandler.post {
                webView.evaluateJavascript("window.onPaired && window.onPaired();", null)
            }
        }

        override fun onConnectingToRemote() {
            Log.d(tag, "Connecting to TV remote port...")
        }

        override fun onConnected() {
            Log.d(tag, "Connected to TV successfully!")
            isConnected = true
            needsPairing = false
            lastError = ""
            mainHandler.post {
                webView.evaluateJavascript("window.onTvConnected && window.onTvConnected('$currentTvIp');", null)
            }
        }

        override fun onDisconnect() {
            Log.d(tag, "Disconnected from TV")
            isConnected = false
            mainHandler.post {
                webView.evaluateJavascript("window.onTvDisconnected && window.onTvDisconnected();", null)
            }
        }

        override fun onImeShow(text: String?, fieldCounter: Int) {
            val safeText = text ?: ""
            mainHandler.post {
                val escaped = JSONObject.quote(safeText)
                webView.evaluateJavascript("window.onImeShow && window.onImeShow($escaped, $fieldCounter);", null)
            }
        }

        override fun onError(error: String?) {
            val msg = error ?: "Communication error"
            Log.w(tag, "TV Error: $msg")
            lastError = msg
            mainHandler.post {
                val escaped = JSONObject.quote(msg)
                webView.evaluateJavascript("window.onTvError && window.onTvError($escaped);", null)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize AndroidRemoteContext
        try {
            val context = AndroidRemoteContext.getInstance()
            context.clientName = "Master Sahib Haier Remote"
            context.keyStoreFile = File(filesDir, "androidtv.keystore")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize AndroidRemoteContext", e)
        }

        currentTvIp = prefs.getString("last_tv_ip", "192.168.0.107") ?: "192.168.0.107"

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        try {
            val allowFile = settings.javaClass.getMethod("setAllowFileAccessFromFileURLs", Boolean::class.javaPrimitiveType)
            allowFile.invoke(settings, true)
            val allowUniversal = settings.javaClass.getMethod("setAllowUniversalAccessFromFileURLs", Boolean::class.javaPrimitiveType)
            allowUniversal.invoke(settings, true)
        } catch (ignored: Exception) {
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Attempt initial connection once page is loaded
                initiateConnection(currentTvIp)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
            }
        }

        // Bridge to JavaScript
        webView.addJavascriptInterface(WebAppInterface(), "AndroidRemote")

        // Load standalone local UI assets
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun initiateConnection(ip: String) {
        currentTvIp = ip
        prefs.edit().putString("last_tv_ip", ip).apply()

        executor.execute {
            try {
                if (remoteTv == null) {
                    remoteTv = AndroidRemoteTv()
                }

                // First attempt reconnecting to control port 6466 directly
                try {
                    Log.d(tag, "Attempting direct reconnect to $ip:6466")
                    remoteTv?.reconnect(ip, tvListener)
                    return@execute
                } catch (e: Exception) {
                    Log.d(tag, "Direct reconnect failed, proceeding with full connect: ${e.message}")
                }

                // If reconnect fails, initiate standard connect/pairing on 6467
                remoteTv?.connect(ip, tvListener)
            } catch (e: Exception) {
                Log.e(tag, "Error initiating TV connection", e)
                lastError = e.message ?: "Failed to connect"
                mainHandler.post {
                    val escaped = JSONObject.quote(lastError)
                    webView.evaluateJavascript("window.onTvError && window.onTvError($escaped);", null)
                }
            }
        }
    }

    inner class WebAppInterface {

        @JavascriptInterface
        fun isNative(): Boolean = true

        @JavascriptInterface
        fun getStatus(): String {
            val json = JSONObject()
            json.put("connected", isConnected)
            json.put("needs_pairing", needsPairing)
            json.put("tv_ip", currentTvIp)
            json.put("last_error", lastError)
            json.put("is_native", true)
            return json.toString()
        }

        @JavascriptInterface
        fun setTvIp(ip: String) {
            val cleanIp = ip.trim()
            if (cleanIp.isNotEmpty()) {
                initiateConnection(cleanIp)
            }
        }

        @JavascriptInterface
        fun startPairing() {
            executor.execute {
                try {
                    if (remoteTv == null) {
                        remoteTv = AndroidRemoteTv()
                    }
                    remoteTv?.connect(currentTvIp, tvListener)
                } catch (e: Exception) {
                    Log.e(tag, "startPairing failed", e)
                    lastError = e.message ?: "Pairing failed to start"
                    mainHandler.post {
                        val escaped = JSONObject.quote(lastError)
                        webView.evaluateJavascript("window.onTvError && window.onTvError($escaped);", null)
                    }
                }
            }
        }

        @JavascriptInterface
        fun sendPairCode(code: String) {
            executor.execute {
                try {
                    val cleanCode = code.trim().uppercase()
                    remoteTv?.sendSecret(cleanCode)
                } catch (e: Exception) {
                    Log.e(tag, "sendPairCode failed", e)
                    mainHandler.post {
                        val escaped = JSONObject.quote("Code error: " + (e.message ?: ""))
                        webView.evaluateJavascript("window.onTvError && window.onTvError($escaped);", null)
                    }
                }
            }
        }

        @JavascriptInterface
        fun sendKey(keyName: String) {
            executor.execute {
                try {
                    val keyCode = mapKeyToCode(keyName)
                    if (keyCode != Remotemessage.RemoteKeyCode.KEYCODE_UNKNOWN) {
                        remoteTv?.sendCommand(keyCode, Remotemessage.RemoteDirection.SHORT)
                    } else {
                        Log.w(tag, "Unknown key: $keyName")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error sending key $keyName", e)
                }
            }
        }

        @JavascriptInterface
        fun typeChar(char: String) {
            executor.execute {
                try {
                    val keyCode = when (char) {
                        "DEL", "BACKSPACE" -> Remotemessage.RemoteKeyCode.KEYCODE_DEL
                        "ENTER" -> Remotemessage.RemoteKeyCode.KEYCODE_ENTER
                        " " -> Remotemessage.RemoteKeyCode.KEYCODE_SPACE
                        else -> {
                            if (char.length == 1) {
                                val ch = char[0].uppercaseChar()
                                if (ch in 'A'..'Z') {
                                    Remotemessage.RemoteKeyCode.valueOf("KEYCODE_$ch")
                                } else if (ch in '0'..'9') {
                                    Remotemessage.RemoteKeyCode.valueOf("KEYCODE_$ch")
                                } else {
                                    Remotemessage.RemoteKeyCode.KEYCODE_UNKNOWN
                                }
                            } else {
                                Remotemessage.RemoteKeyCode.KEYCODE_UNKNOWN
                            }
                        }
                    }

                    if (keyCode != Remotemessage.RemoteKeyCode.KEYCODE_UNKNOWN) {
                        remoteTv?.sendCommand(keyCode, Remotemessage.RemoteDirection.SHORT)
                    } else {
                        // Attempt sending as text
                        remoteTv?.sendText(char, 0, 0)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error typing char: $char", e)
                }
            }
        }

        @JavascriptInterface
        fun sendText(text: String) {
            executor.execute {
                try {
                    remoteTv?.sendText(text, 0, 0)
                } catch (e: Exception) {
                    Log.e(tag, "Error sending text: $text", e)
                }
            }
        }

        @JavascriptInterface
        fun launchApp(appUrl: String) {
            executor.execute {
                try {
                    val target = when (appUrl.trim().uppercase()) {
                        "YOUTUBE" -> "https://www.youtube.com"
                        "NETFLIX" -> "https://www.netflix.com"
                        "PRIME", "PRIME_VIDEO" -> "https://app.primevideo.com"
                        "GOOGLE_PLAY" -> "https://play.google.com/store"
                        else -> appUrl
                    }
                    remoteTv?.sendAppLink(target)
                } catch (e: Exception) {
                    Log.e(tag, "Error launching app: $appUrl", e)
                }
            }
        }

        @JavascriptInterface
        fun autoDiscover() {
            executor.execute {
                runAutoDiscovery()
            }
        }
    }

    private fun runAutoDiscovery() {
        mainHandler.post {
            webView.evaluateJavascript("window.onDiscoveryStatus && window.onDiscoveryStatus('Searching local Wi-Fi subnet for Haier TV...');", null)
        }

        val baseSubnet = getLocalSubnetPrefix()
        var foundIp: String? = null

        // First test known / saved IP
        if (checkTvPort(currentTvIp)) {
            foundIp = currentTvIp
        }

        // Scan subnet IPs if not already found
        if (foundIp == null && baseSubnet != null) {
            for (i in 1..254) {
                val targetIp = "$baseSubnet.$i"
                if (checkTvPort(targetIp)) {
                    foundIp = targetIp
                    break
                }
            }
        }

        // Fallback check on standard 192.168.0.x if local subnet was different
        if (foundIp == null && baseSubnet != "192.168.0") {
            for (i in listOf(107, 100, 101, 102, 103, 104, 105, 106, 108, 109, 110)) {
                val targetIp = "192.168.0.$i"
                if (checkTvPort(targetIp)) {
                    foundIp = targetIp
                    break
                }
            }
        }

        if (foundIp != null) {
            val tvIp = foundIp
            currentTvIp = tvIp
            prefs.edit().putString("last_tv_ip", tvIp).apply()

            mainHandler.post {
                val msg = "Haier TV found at $tvIp!"
                val escapedMsg = JSONObject.quote(msg)
                val escapedIp = JSONObject.quote(tvIp)
                webView.evaluateJavascript("window.onTvDiscovered && window.onTvDiscovered($escapedIp, $escapedMsg);", null)
            }
            initiateConnection(tvIp)
        } else {
            mainHandler.post {
                val msg = "Could not locate Haier TV automatically. Please verify your phone is on the same Wi-Fi and TV is turned ON."
                val escapedMsg = JSONObject.quote(msg)
                webView.evaluateJavascript("window.onTvDiscoveryFailed && window.onTvDiscoveryFailed($escapedMsg);", null)
            }
        }
    }

    private fun checkTvPort(ip: String): Boolean {
        for (port in intArrayOf(6466, 6467)) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), 250)
                    return true
                }
            } catch (ignored: Exception) {
            }
        }
        return false
    }

    private fun getLocalSubnetPrefix(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    val host = addr.hostAddress
                    if (!addr.isLoopbackAddress && addr is InetAddress && host != null && !host.contains(':')) {
                        val parts = host.split(".")
                        if (parts.size == 4) {
                            return "${parts[0]}.${parts[1]}.${parts[2]}"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Error getting local subnet", e)
        }
        return "192.168.0"
    }

    private fun mapKeyToCode(key: String): Remotemessage.RemoteKeyCode {
        return when (key.trim().uppercase()) {
            "POWER" -> Remotemessage.RemoteKeyCode.KEYCODE_POWER
            "UP", "DPAD_UP" -> Remotemessage.RemoteKeyCode.KEYCODE_DPAD_UP
            "DOWN", "DPAD_DOWN" -> Remotemessage.RemoteKeyCode.KEYCODE_DPAD_DOWN
            "LEFT", "DPAD_LEFT" -> Remotemessage.RemoteKeyCode.KEYCODE_DPAD_LEFT
            "RIGHT", "DPAD_RIGHT" -> Remotemessage.RemoteKeyCode.KEYCODE_DPAD_RIGHT
            "ENTER", "DPAD_CENTER", "OK" -> Remotemessage.RemoteKeyCode.KEYCODE_DPAD_CENTER
            "BACK" -> Remotemessage.RemoteKeyCode.KEYCODE_BACK
            "HOME" -> Remotemessage.RemoteKeyCode.KEYCODE_HOME
            "VOLUME_UP", "VOL_UP" -> Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_UP
            "VOLUME_DOWN", "VOL_DOWN" -> Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_DOWN
            "MUTE", "VOLUME_MUTE" -> Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_MUTE
            "CHANNEL_UP", "CH_UP" -> Remotemessage.RemoteKeyCode.KEYCODE_CHANNEL_UP
            "CHANNEL_DOWN", "CH_DOWN" -> Remotemessage.RemoteKeyCode.KEYCODE_CHANNEL_DOWN
            "MENU" -> Remotemessage.RemoteKeyCode.KEYCODE_MENU
            "SETTINGS" -> Remotemessage.RemoteKeyCode.KEYCODE_SETTINGS
            "INFO" -> Remotemessage.RemoteKeyCode.KEYCODE_INFO
            "PLAY_PAUSE", "MEDIA_PLAY_PAUSE" -> Remotemessage.RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE
            "STOP", "MEDIA_STOP" -> Remotemessage.RemoteKeyCode.KEYCODE_MEDIA_STOP
            "FAST_FORWARD", "FFWD" -> Remotemessage.RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD
            "REWIND", "REW" -> Remotemessage.RemoteKeyCode.KEYCODE_MEDIA_REWIND
            "RED" -> Remotemessage.RemoteKeyCode.KEYCODE_PROG_RED
            "GREEN" -> Remotemessage.RemoteKeyCode.KEYCODE_PROG_GREEN
            "YELLOW" -> Remotemessage.RemoteKeyCode.KEYCODE_PROG_YELLOW
            "BLUE" -> Remotemessage.RemoteKeyCode.KEYCODE_PROG_BLUE
            "SOURCE", "INPUT" -> Remotemessage.RemoteKeyCode.KEYCODE_TV_INPUT
            "0", "NUM_0" -> Remotemessage.RemoteKeyCode.KEYCODE_0
            "1", "NUM_1" -> Remotemessage.RemoteKeyCode.KEYCODE_1
            "2", "NUM_2" -> Remotemessage.RemoteKeyCode.KEYCODE_2
            "3", "NUM_3" -> Remotemessage.RemoteKeyCode.KEYCODE_3
            "4", "NUM_4" -> Remotemessage.RemoteKeyCode.KEYCODE_4
            "5", "NUM_5" -> Remotemessage.RemoteKeyCode.KEYCODE_5
            "6", "NUM_6" -> Remotemessage.RemoteKeyCode.KEYCODE_6
            "7", "NUM_7" -> Remotemessage.RemoteKeyCode.KEYCODE_7
            "8", "NUM_8" -> Remotemessage.RemoteKeyCode.KEYCODE_8
            "9", "NUM_9" -> Remotemessage.RemoteKeyCode.KEYCODE_9
            "DEL", "BACKSPACE" -> Remotemessage.RemoteKeyCode.KEYCODE_DEL
            "SPACE" -> Remotemessage.RemoteKeyCode.KEYCODE_SPACE
            else -> Remotemessage.RemoteKeyCode.KEYCODE_UNKNOWN
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            remoteTv?.abort()
        } catch (ignored: Exception) {
        }
        executor.shutdown()
    }
}
