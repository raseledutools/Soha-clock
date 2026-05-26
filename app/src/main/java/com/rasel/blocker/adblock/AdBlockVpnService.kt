package com.rasel.blocker.adblock

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rasel.blocker.MainActivity
import com.rasel.blocker.adblock.https.CaManager
import com.rasel.blocker.adblock.https.HttpsInterceptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdBlockVpnService — Upgraded with C++ engine + SNI blocking
 *
 * 1. DNS queries    → C++ engine で parse → block or forward
 * 2. TLS (HTTPS)    → C++ engine で SNI parse → block by hostname
 * 3. HTTPS inspect  → HttpsInterceptor (CA cert required)
 */
class AdBlockVpnService : VpnService() {

    companion object {
        const val ACTION_START  = "ACTION_ADBLOCK_START"
        const val ACTION_STOP   = "ACTION_ADBLOCK_STOP"
        const val CHANNEL_ID    = "AdBlockVpnChannel"
        const val NOTIF_ID      = 77
        var isRunning = false
            private set
        private const val TAG   = "AdBlockVPN"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running     = AtomicBoolean(false)
    private val executor    = Executors.newFixedThreadPool(4)
    private val upstreamDns = InetAddress.getByName("1.1.1.1")
    private var httpsInterceptor: HttpsInterceptor? = null
    private var httpsEnabled = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                httpsEnabled = intent.getBooleanExtra("https_filter", false)
                startVpn()
            }
            ACTION_STOP  -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (running.get()) return
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        // ── 1. Init C++ native engine ─────────────────────────────
        NativeEngine.loadBlockList(FilterManager.getAllDomains())
        NativeEngine.nativeSetEnabled(true)
        Log.i(TAG, "Native engine: ${if (NativeEngine.isAvailable) "C++ ✅" else "Kotlin fallback"}")

        // ── 2. Init HTTPS interceptor if enabled ──────────────────
        if (httpsEnabled) {
            httpsInterceptor = HttpsInterceptor(this).also {
                if (!it.init()) {
                    Log.w(TAG, "HTTPS interceptor init failed — CA cert missing?")
                    httpsInterceptor = null
                }
            }
        }

        // ── 3. Start VPN ──────────────────────────────────────────
        try {
            vpnInterface = Builder()
                .setSession("RaselAdBlock")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("10.0.0.2")   // redirect DNS to ourselves
                .setMtu(1500)
                .setBlocking(false)
                .addDisallowedApplication(packageName)
                .establish()
            running.set(true)
            isRunning = true
            Log.i(TAG, "VPN established")
            executor.execute { runPacketLoop() }
        } catch (e: Exception) {
            Log.e(TAG, "VPN start failed", e)
            stopVpn()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Main packet loop
    // ─────────────────────────────────────────────────────────────
    private fun runPacketLoop() {
        val vpn    = vpnInterface ?: return
        val input  = FileInputStream(vpn.fileDescriptor)
        val output = FileOutputStream(vpn.fileDescriptor)
        val buf    = java.nio.ByteBuffer.allocate(32767)

        while (running.get()) {
            buf.clear()
            val len = input.channel.read(buf)
            if (len <= 0) { Thread.sleep(5); continue }
            buf.flip()
            val raw = buf.array().copyOf(len)
            executor.execute { handlePacket(raw, len, output) }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Route packet by protocol
    // ─────────────────────────────────────────────────────────────
    private fun handlePacket(raw: ByteArray, len: Int, out: FileOutputStream) {
        if (len < 20) return
        val version  = (raw[0].toInt() and 0xFF) shr 4
        if (version != 4) return   // IPv4 only

        val protocol = raw[9].toInt() and 0xFF
        val ihl      = (raw[0].toInt() and 0x0F) * 4

        when (protocol) {
            17 -> handleUdp(raw, len, ihl, out)   // UDP (DNS)
            6  -> handleTcp(raw, len, ihl, out)          // TCP (HTTPS SNI)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // UDP — DNS query handling (C++ engine)
    // ─────────────────────────────────────────────────────────────
    private fun handleUdp(raw: ByteArray, len: Int, ihl: Int, out: FileOutputStream) {
        if (len <= ihl + 8) return
        val dstPort = ((raw[ihl + 2].toInt() and 0xFF) shl 8) or (raw[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val dns    = raw.copyOfRange(ihl + 8, len)
        // ── C++ DNS domain parse ──
        val domain = NativeEngine.parseDnsDomain(dns)
        Log.d(TAG, "DNS → $domain")

        if (domain.isNotEmpty() && NativeEngine.shouldBlock(domain)) {
            FilterManager.incrementBlocked()
            Log.d(TAG, "BLOCKED DNS: $domain")
            // ── C++ NXDOMAIN build ──
            val nxResp = NativeEngine.buildNxDomain(dns)
            sendDnsResponse(nxResp, raw, ihl, out)
        } else {
            val resp = forwardDns(dns)
            if (resp != null) sendDnsResponse(resp, raw, ihl, out)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TCP — TLS SNI extraction (C++ engine, no CA cert needed)
    // ─────────────────────────────────────────────────────────────
    private fun handleTcp(raw: ByteArray, len: Int, ihl: Int, out: FileOutputStream) {
        val tcpOffset = ihl
        if (len <= tcpOffset + 20) return
        val dstPort   = ((raw[tcpOffset + 2].toInt() and 0xFF) shl 8) or
                         (raw[tcpOffset + 3].toInt() and 0xFF)
        if (dstPort != 443) return

        val tcpHdrLen = ((raw[tcpOffset + 12].toInt() and 0xFF) shr 4) * 4
        val payloadOff = tcpOffset + tcpHdrLen
        if (len <= payloadOff + 5) return

        val payload = raw.copyOfRange(payloadOff, len)

        // ── C++ SNI parse ──
        val sni = NativeEngine.parseTlsSni(payload)
        if (sni.isNotEmpty()) {
            Log.d(TAG, "SNI → $sni")
            if (NativeEngine.shouldBlock(sni)) {
                FilterManager.incrementBlocked()
                Log.d(TAG, "BLOCKED HTTPS SNI (RST): $sni")
                // ── TCP RST inject — connection forcefully close করা ──
                val rst = TcpRstInjector.buildRstPacket(raw, ihl)
                if (rst != null) {
                    try { out.write(rst) } catch (_: Exception) {}
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Build and send DNS response packet
    // ─────────────────────────────────────────────────────────────
    private fun sendDnsResponse(dnsResp: ByteArray, orig: ByteArray, ihl: Int, out: FileOutputStream) {
        try {
            val total = ihl + 8 + dnsResp.size
            val pkt = ByteArray(total)
            System.arraycopy(orig, 0, pkt, 0, ihl)
            pkt[2] = (total shr 8).toByte(); pkt[3] = (total and 0xFF).toByte()
            // swap src/dst IP
            System.arraycopy(orig, 12, pkt, 16, 4)
            System.arraycopy(orig, 16, pkt, 12, 4)
            // UDP header: swap ports
            pkt[ihl] = orig[ihl + 2]; pkt[ihl + 1] = orig[ihl + 3]
            pkt[ihl + 2] = orig[ihl]; pkt[ihl + 3] = orig[ihl + 1]
            val udpLen = 8 + dnsResp.size
            pkt[ihl + 4] = (udpLen shr 8).toByte(); pkt[ihl + 5] = (udpLen and 0xFF).toByte()
            pkt[ihl + 6] = 0; pkt[ihl + 7] = 0
            System.arraycopy(dnsResp, 0, pkt, ihl + 8, dnsResp.size)
            // ── C++ IP checksum ──
            NativeEngine.recalcIpChecksum(pkt, ihl)
            out.write(pkt)
        } catch (_: Exception) {}
    }

    private fun forwardDns(q: ByteArray): ByteArray? = try {
        val s = DatagramSocket(); protect(s)
        s.soTimeout = 3000
        s.send(DatagramPacket(q, q.size, upstreamDns, 53))
        val buf = ByteArray(4096)
        val pkt = DatagramPacket(buf, buf.size)
        s.receive(pkt); s.close()
        buf.copyOf(pkt.length)
    } catch (_: Exception) { null }

    private fun stopVpn() {
        running.set(false); isRunning = false
        NativeEngine.nativeSetEnabled(false)
        vpnInterface?.close(); vpnInterface = null
        stopForeground(true); stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ Ad Blocker Active")
            .setContentText("C++ engine • DNS + SNI blocking")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Ad Blocker VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() { stopVpn(); executor.shutdownNow(); super.onDestroy() }
}
