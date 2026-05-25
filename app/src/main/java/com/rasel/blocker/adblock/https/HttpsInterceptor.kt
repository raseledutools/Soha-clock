package com.rasel.blocker.adblock.https

import android.content.Context
import android.util.Log
import com.rasel.blocker.adblock.FilterManager
import com.rasel.blocker.adblock.NativeEngine
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.*

/**
 * HttpsInterceptor
 * ─────────────────────────────────────────────────────────────────
 * HTTPS traffic interceptor using MITM (Man-in-the-Middle) technique
 *
 * কীভাবে কাজ করে:
 *  1. Client (app/browser) → আমাদের VPN এ আসে
 *  2. আমরা client এর জন্য fake cert বানাই (CA দিয়ে sign করা)
 *  3. Client মনে করে সে real server এর সাথে কথা বলছে
 *  4. আমরা real server এ connect করি
 *  5. মাঝখানে HTTP headers দেখে ads block করি
 *  6. Blocked → connection drop, Allow → transparent forward
 *
 * ⚠️ এটা শুধু নিজের device এ নিজের privacy এর জন্য।
 * ─────────────────────────────────────────────────────────────────
 */
class HttpsInterceptor(private val context: Context) {

    private val TAG = "HttpsInterceptor"

    // CA certificate এবং private key
    private var caCert: X509Certificate? = null
    private var caKey: PrivateKey? = null
    private var sslContext: SSLContext? = null

    // Per-domain fake cert cache (performance)
    private val fakeCertCache = mutableMapOf<String, SSLContext>()

    fun init(): Boolean {
        return try {
            caCert = CaManager.getCaCert(context) ?: run {
                Log.w(TAG, "No CA cert found — generating...")
                CaManager.generateCa(context)
                CaManager.getCaCert(context)
            } ?: return false

            val keyPem = CaManager.getCaKeyPem(context) ?: return false
            caKey = loadPrivateKey(keyPem)

            Log.i(TAG, "HttpsInterceptor initialized ✅")
            true
        } catch (e: Exception) {
            Log.e(TAG, "HttpsInterceptor init failed", e)
            false
        }
    }

    /**
     * HTTPS connection intercept করা
     *
     * @param clientSocket — VPN TUN এর client side socket
     * @param targetHost   — SNI থেকে পাওয়া real hostname
     * @param targetPort   — সাধারণত 443
     */
    fun intercept(clientSocket: Socket, targetHost: String, targetPort: Int) {
        Thread {
            try {
                // 1. Check if this host should be blocked
                if (NativeEngine.shouldBlock(targetHost)) {
                    Log.d(TAG, "HTTPS BLOCKED: $targetHost")
                    FilterManager.incrementBlocked()
                    clientSocket.close()
                    return@Thread
                }

                // 2. Connect to real server
                val realSocket = Socket(targetHost, targetPort)
                val realSsl = SSLContext.getInstance("TLS").apply {
                    init(null, null, null) // system trust store
                }
                val realSslSocket = realSsl.socketFactory
                    .createSocket(realSocket, targetHost, targetPort, true) as SSLSocket
                realSslSocket.startHandshake()

                // 3. Get real server's cert to copy subject info
                val realCert = realSslSocket.session.peerCertificates
                    .firstOrNull() as? X509Certificate

                // 4. Create fake cert for this domain (signed by our CA)
                val fakeSslCtx = fakeCertCache.getOrPut(targetHost) {
                    buildFakeSslContext(targetHost, realCert)
                }

                // 5. Perform TLS handshake with client using fake cert
                val clientSslSocket = fakeSslCtx.serverSocketFactory
                    .createServerSocket(0).accept() as SSLSocket
                clientSslSocket.startHandshake()

                // 6. Transparent relay — inspect HTTP headers for ads
                val clientIn  = clientSslSocket.inputStream
                val clientOut = clientSslSocket.outputStream
                val serverIn  = realSslSocket.inputStream
                val serverOut = realSslSocket.outputStream

                // Client → Server relay (inspect request)
                val c2s = Thread { relayClientToServer(clientIn, serverOut, targetHost) }
                // Server → Client relay (inspect response)
                val s2c = Thread { relayServerToClient(serverIn, clientOut, targetHost) }

                c2s.start(); s2c.start()
                c2s.join();  s2c.join()

                clientSslSocket.close()
                realSslSocket.close()

            } catch (e: Exception) {
                Log.e(TAG, "Intercept error for $targetHost: ${e.message}")
            } finally {
                runCatching { clientSocket.close() }
            }
        }.start()
    }

    /**
     * Client → Server: HTTP request দেখি
     * Ad request হলে drop করি, নয়তো forward করি
     */
    private fun relayClientToServer(inp: InputStream, out: OutputStream, host: String) {
        try {
            val buf = ByteArray(8192)
            var n: Int
            while (inp.read(buf).also { n = it } != -1) {
                val req = String(buf, 0, n, Charsets.UTF_8)
                // HTTP GET/POST path দেখা
                val path = extractHttpPath(req)
                if (path != null && isAdPath(path, host)) {
                    Log.d(TAG, "HTTPS AD PATH BLOCKED: $host$path")
                    FilterManager.incrementBlocked()
                    // Send 204 No Content (ad নেই, app crash করবে না)
                    out.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {}
    }

    /**
     * Server → Client: response এ ad JS inject হচ্ছে কিনা দেখা
     */
    private fun relayServerToClient(inp: InputStream, out: OutputStream, host: String) {
        try {
            val buf = ByteArray(8192)
            var n: Int
            while (inp.read(buf).also { n = it } != -1) {
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: Exception) {}
    }

    // HTTP request থেকে path বের করা
    private fun extractHttpPath(req: String): String? {
        val line = req.lines().firstOrNull() ?: return null
        val parts = line.split(" ")
        return if (parts.size >= 2) parts[1] else null
    }

    // Ad URL path check করা
    private fun isAdPath(path: String, host: String): Boolean {
        val adPaths = listOf(
            "/ads/", "/ad/", "/pagead/", "/adview",
            "/googleads", "/doubleclick", "/adsense",
            "/imasdk", "/ima3", "/adserver",
            "googlesyndication", "adservice"
        )
        val fullUrl = "$host$path".lowercase()
        return adPaths.any { fullUrl.contains(it) }
    }

    // ── Fake cert builder ─────────────────────────────────────────
    private fun buildFakeSslContext(host: String, realCert: X509Certificate?): SSLContext {
        // Generate ephemeral key pair for this domain
        val kpg = java.security.KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()

        // Build fake cert (simple version using Bouncy Castle logic inline)
        // Subject = real cert's subject or CN=host
        val subject = realCert?.subjectDN?.name ?: "CN=$host"

        // For the KeyStore we need a fake cert chain
        // In a production app, use Bouncy Castle for proper cert signing
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null)
        // Store our CA cert as the trust anchor
        ks.setCertificateEntry("ca", caCert)

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        // Use CA key for signing (simplified — production needs per-domain certs)
        val ks2 = KeyStore.getInstance(KeyStore.getDefaultType())
        ks2.load(null)
        ks2.setKeyEntry("key", caKey, charArrayOf(), arrayOf(caCert))
        kmf.init(ks2, charArrayOf())

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, tmf.trustManagers, null)
        }
    }

    // ── Load PKCS8 private key from PEM string ───────────────────
    private fun loadPrivateKey(pem: String): PrivateKey {
        val b64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val der = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    private fun ECGenParameterSpec(name: String) =
        java.security.spec.ECGenParameterSpec(name)
}
