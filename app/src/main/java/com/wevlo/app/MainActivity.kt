package com.wevlo.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MainActivity : Activity() {

    private lateinit var statusText: TextView

    companion object {
        private const val VPN_REQUEST_CODE = 100
        private const val NOTIF_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIF_REQUEST_CODE)
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        statusText = TextView(this).apply {
            text = "Status: Stopped"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        val startBtn = Button(this).apply {
            text = "Start AdBlocker VPN"
            setOnClickListener {
                val intent = VpnService.prepare(this@MainActivity)
                if (intent != null) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, VPN_REQUEST_CODE)
                } else {
                    startAdBlockerService()
                }
            }
        }

        val stopBtn = Button(this).apply {
            text = "Stop AdBlocker VPN"
            setOnClickListener {
                val intent = Intent(this@MainActivity, AdBlockVpnService::class.java).apply {
                    action = AdBlockVpnService.ACTION_STOP
                }
                startService(intent)
                statusText.text = "Status: Stopped"
            }
        }

        layout.addView(statusText)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        setContentView(layout)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startAdBlockerService()
        }
    }

    private fun startAdBlockerService() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText.text = "Status: Running"
    }
}

class AdBlockVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    @Volatile
    private var isRunning = false
    private var vpnThread: Thread? = null

    private val blockedDomains = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "ads.youtube.com",
        "an.facebook.com",
        "pixel.facebook.com",
        "unityads.unity3d.com",
        "applovin.com"
    )

    companion object {
        const val ACTION_START = "START_VPN"
        const val ACTION_STOP = "STOP_VPN"
        private const val CHANNEL_ID = "adblock_vpn_channel"
        private const val NOTIFICATION_ID = 101
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startForegroundServiceNotification()
        startVpn()
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AdBlocker VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("AdBlocker Active")
            .setContentText("DNS Ad-Blocking চলছে...")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startVpn() {
        if (isRunning) return

        val builder = Builder()
            .setSession("SimpleAdBlocker")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.1")
            .addRoute("10.0.0.1", 32)
            .addDisallowedApplication(packageName)

        vpnInterface = builder.establish() ?: return
        isRunning = true

        vpnThread = Thread {
            runDnsTunnel(vpnInterface!!)
        }.apply { start() }
    }

    private fun runDnsTunnel(tunFd: ParcelFileDescriptor) {
        val input = FileInputStream(tunFd.fileDescriptor)
        val output = FileOutputStream(tunFd.fileDescriptor)
        val buffer = ByteArray(32767)

        while (isRunning) {
            try {
                val length = input.read(buffer)
                if (length <= 0) continue

                val version = (buffer[0].toInt() ushr 4) and 0x0F
                if (version != 4) continue

                val ihl = (buffer[0].toInt() and 0x0F) * 4
                if (length < ihl + 8) continue

                val protocol = buffer[9].toInt() and 0xFF
                if (protocol != 17) continue

                val dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or
                    (buffer[ihl + 3].toInt() and 0xFF)
                if (dstPort != 53) continue

                val dnsOffset = ihl + 8
                if (length - dnsOffset < 12) continue

                val dnsQuery = buffer.copyOfRange(dnsOffset, length)
                val parsed = parseDomainName(dnsQuery) ?: continue
                val (domain, questionEnd) = parsed

                val dnsResponse: ByteArray? = if (isDomainBlocked(domain)) {
                    buildNxDomainResponse(dnsQuery, questionEnd)
                } else {
                    forwardDnsToUpstream(dnsQuery)
                }

                if (dnsResponse != null) {
                    val replyPacket = buildUdpIpv4Response(buffer, ihl, dnsResponse)
                    output.write(replyPacket)
                }
            } catch (_: Exception) {
                if (!isRunning) break
            }
        }
    }

    private fun isDomainBlocked(domain: String): Boolean {
        return blockedDomains.any { blocked ->
            domain == blocked || domain.endsWith(".$blocked")
        }
    }

    private fun parseDomainName(dns: ByteArray): Pair<String, Int>? {
        val sb = StringBuilder()
        var pos = 12
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            pos++
            if (len == 0) {
                val qEnd = minOf(pos + 4, dns.size)
                return sb.toString().lowercase() to qEnd
            }
            if ((len and 0xC0) == 0xC0 || pos + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, pos, len, Charsets.US_ASCII))
            pos += len
        }
        return null
    }

    private fun buildNxDomainResponse(dnsQuery: ByteArray, questionEnd: Int): ByteArray {
        val resp = dnsQuery.copyOfRange(0, questionEnd)
        resp[2] = (0x80 or (dnsQuery[2].toInt() and 0x79)).toByte()
        resp[3] = 0x83.toByte()
        resp[6] = 0; resp[7] = 0
        resp[8] = 0; resp[9] = 0
        resp[10] = 0; resp[11] = 0
        return resp
    }

    private fun forwardDnsToUpstream(dnsQuery: ByteArray): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 3000

            val upstreamAddr = InetAddress.getByName("8.8.8.8")
            val outPacket = DatagramPacket(dnsQuery, dnsQuery.size, upstreamAddr, 53)
            socket.send(outPacket)

            val respBuf = ByteArray(4096)
            val inPacket = DatagramPacket(respBuf, respBuf.size)
            socket.receive(inPacket)

            respBuf.copyOfRange(0, inPacket.length)
        } catch (_: Exception) {
            null
        } finally {
            socket?.close()
        }
    }

    private fun buildUdpIpv4Response(
        reqPacket: ByteArray,
        reqIhl: Int,
        dnsResponse: ByteArray
    ): ByteArray {
        val udpLen = 8 + dnsResponse.size
        val totalLen = 20 + udpLen
        val resp = ByteArray(totalLen)

        resp[0] = 0x45.toByte()
        resp[1] = 0x00
        resp[2] = (totalLen shr 8).toByte()
        resp[3] = (totalLen and 0xFF).toByte()
        resp[6] = 0x40
        resp[8] = 64
        resp[9] = 17

        System.arraycopy(reqPacket, 16, resp, 12, 4)
        System.arraycopy(reqPacket, 12, resp, 16, 4)

        val ipChecksum = calculateIpChecksum(resp, 20)
        resp[10] = (ipChecksum shr 8).toByte()
        resp[11] = (ipChecksum and 0xFF).toByte()

        System.arraycopy(reqPacket, reqIhl + 2, resp, 20, 2)
        System.arraycopy(reqPacket, reqIhl, resp, 22, 2)
        resp[24] = (udpLen shr 8).toByte()
        resp[25] = (udpLen and 0xFF).toByte()
        resp[26] = 0; resp[27] = 0

        System.arraycopy(dnsResponse, 0, resp, 28, dnsResponse.size)
        return resp
    }

    private fun calculateIpChecksum(buf: ByteArray, length: Int): Int {
        var sum = 0
        var i = 0
        while (i < length) {
            val high = buf[i].toInt() and 0xFF
            val low = if (i + 1 < length) buf[i + 1].toInt() and 0xFF else 0
            sum += (high shl 8) or low
            i += 2
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun stopVpn() {
        isRunning = false
        vpnThread?.interrupt()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}