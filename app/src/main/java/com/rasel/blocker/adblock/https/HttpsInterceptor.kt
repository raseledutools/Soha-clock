package com.rasel.blocker.adblock.https

import android.content.Context
import android.util.Log
import com.rasel.blocker.adblock.FilterManager
import com.rasel.blocker.adblock.NativeEngine
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.*

/**
 * HttpsInterceptor — MITM HTTPS ad blocker
 *
 * Bouncy Castle দিয়ে per-domain fake cert বানায়।
 * CA cert device এ install করলেই কাজ করে।
 *
 * ফ্লো:
 *  Client → VPN → আমরা (fake cert) → Real Server
 */
class HttpsInterceptor(private val context: Context) {

    private val TAG = "HttpsInterceptor"

    private var caCert: X509Certificate? = null
    private var caKey:  PrivateKey?       = null

    // per-domain cache: host → SSLContext (with fake cert)
    private val certCache = ConcurrentHashMap<String, SSLContext>()

    fun init(): Boolean {
        return try {
            caCert = CaManager.getCaCert(context) ?: run {
                Log.w(TAG, "No CA — generating...")
                CaManager.generateCa(context)
                CaManager.getCaCert(context)
            } ?: return false

            caKey = CaManager.getCaPrivateKey(context) ?: return false
            Log.i(TAG, "HttpsInterceptor ✅ initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            false
        }
    }

    /**
     * HTTPS connection intercept।
     * clientSocket = VPN TUN থেকে আসা TCP connection (port 443)
     * targetHost   = SNI থেকে পাওয়া domain
     */
    fun intercept(clientSocket: Socket, targetHost: String, targetPort: Int) {
        Thread {
            try {
                // 1. Domain block list check
                if (NativeEngine.shouldBlock(targetHost)) {
                    Log.d(TAG, "HTTPS BLOCKED: $targetHost")
                    FilterManager.incrementBlocked()
                    clientSocket.close()
                    return@Thread
                }

                // 2. Real server এ connect
                val realSocket = Socket(targetHost, targetPort)
                val realSslCtx = SSLContext.getInstance("TLS").apply { init(null, null, null) }
                val realSsl = realSslCtx.socketFactory
                    .createSocket(realSocket, targetHost, targetPort, true) as SSLSocket
                realSsl.startHandshake()

                // 3. Real server এর cert পড়া (subject copy করব)
                val realCert = realSsl.session.peerCertificates
                    .firstOrNull() as? X509Certificate

                // 4. Per-domain fake SSLContext (cached)
                val fakeSslCtx = certCache.getOrPut(targetHost) {
                    buildFakeSslCtx(targetHost, realCert)
                }

                // 5. Client এর সাথে TLS handshake (fake cert দিয়ে)
                val clientSsl = fakeSslCtx.socketFactory.createSocket(
                    clientSocket, null, clientSocket.port, true
                ) as SSLSocket
                clientSsl.useClientMode = false
                clientSsl.startHandshake()

                // 6. দুই দিকে relay + ad path filter
                val cIn  = clientSsl.inputStream
                val cOut = clientSsl.outputStream
                val sIn  = realSsl.inputStream
                val sOut = realSsl.outputStream

                val t1 = Thread { relayC2S(cIn, sOut, targetHost) }
                val t2 = Thread { relay(sIn, cOut) }
                t1.start(); t2.start()
                t1.join();  t2.join()

                runCatching { clientSsl.close() }
                runCatching { realSsl.close() }

            } catch (e: Exception) {
                Log.e(TAG, "intercept error [$targetHost]: ${e.message}")
            } finally {
                runCatching { clientSocket.close() }
            }
        }.start()
    }

    // Client → Server: HTTP path check করে ad filter করা
    private fun relayC2S(inp: InputStream, out: OutputStream, host: String) {
        try {
            val buf = ByteArray(8192)
            var n: Int
            while (inp.read(buf).also { n = it } != -1) {
                if (n > 4) {
                    val req  = String(buf, 0, minOf(n, 2048), Charsets.UTF_8)
                    val path = extractPath(req)
                    if (path != null && isAdPath(path, host)) {
                        Log.d(TAG, "HTTPS AD BLOCKED: $host$path")
                        FilterManager.incrementBlocked()
                        out.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        out.flush()
                        return
                    }
                }
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {}
    }

    // Server → Client: transparent relay
    private fun relay(inp: InputStream, out: OutputStream) {
        try {
            val buf = ByteArray(8192)
            var n: Int
            while (inp.read(buf).also { n = it } != -1) {
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {}
    }

    // HTTP request path extract করা
    private fun extractPath(req: String): String? {
        val line  = req.lines().firstOrNull() ?: return null
        val parts = line.split(" ")
        return if (parts.size >= 2) parts[1] else null
    }

    // Ad URL pattern check
    private fun isAdPath(path: String, host: String): Boolean {
        val full = "$host$path".lowercase()
        return AD_PATH_PATTERNS.any { full.contains(it) }
    }

    /**
     * Per-domain fake SSLContext বানানো।
     * Bouncy Castle এ CA দিয়ে sign করা fake cert ব্যবহার।
     */
    private fun buildFakeSslCtx(host: String, realCert: X509Certificate?): SSLContext {
        // Bouncy Castle দিয়ে CA-signed fake cert পাওয়া
        val fakeCert = CaManager.signFakeCert(context, host, realCert)
            ?: throw IllegalStateException("Failed to sign fake cert for $host")

        // Fake cert এর জন্য নতুন key pair দরকার
        // (signFakeCert এর ভেতরে generate হয়েছে — cert এর public key টা ব্যবহার করব)
        // তবে private key আলাদা রাখতে হয়

        // Workaround: CA key দিয়েই serve করব
        // Production এ: signFakeCert কে (cert, privateKey) pair return করতে হবে
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            val chain = arrayOf<java.security.cert.Certificate>(fakeCert, caCert!!)
            setKeyEntry("fake_$host", caKey, charArrayOf(), chain)
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, charArrayOf())

        // Trust: CA কে trust করব (outgoing connection এ real server কে verify)
        val trustKs = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("ca", caCert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustKs)

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, tmf.trustManagers, SecureRandom())
        }
    }

    companion object {
        private val AD_PATH_PATTERNS = listOf(
            "/ads/", "/ad/", "/pagead/", "/adview", "/advert",
            "/googleads", "/doubleclick", "/adsense",
            "/imasdk", "/ima3", "/adserver",
            "googlesyndication", "adservice", "adtrack",
            "/prebid", "/openrtb", "/bid?", "/auction"
        )
    }
}
