package com.rasel.blocker.adblock.https

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.*
import javax.security.auth.x500.X500Principal

/**
 * CaManager
 * ─────────────────────────────────────────────────────────────────
 * Custom CA Certificate তৈরি করে এবং device এ install করে।
 *
 * এই CA দিয়ে আমরা HTTPS traffic মাঝখানে "decrypt" করতে পারব
 * (Man-in-the-Middle on our own device, for ad blocking only).
 *
 * কাজের ধাপ:
 *  1. EC key pair generate করা (RSA এর চেয়ে দ্রুত)
 *  2. Self-signed CA certificate তৈরি করা
 *  3. User কে system trust store এ install করতে বলা
 *  4. VPN service এ এই cert দিয়ে HTTPS decrypt করা
 *
 * ⚠️ Root ছাড়া User CA হিসেবে install হবে —
 *    এতে সব app এ কাজ না-ও করতে পারে (network_security_config ব্যবহার করা apps)
 * ─────────────────────────────────────────────────────────────────
 */
object CaManager {

    private const val TAG = "CaManager"
    private const val PREFS = "adblock_ca_prefs"
    private const val KEY_CA_CERT_PEM = "ca_cert_pem"
    private const val KEY_CA_KEY_PEM  = "ca_key_pem"
    private const val CA_ALIAS = "RaselAdBlockCA"

    // ── Generate a new EC CA key pair + self-signed cert ────────────
    fun generateCa(context: Context): Boolean {
        return try {
            // 1. Generate EC key pair (P-256)
            val keyGen = KeyPairGenerator.getInstance("EC")
            keyGen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val keyPair = keyGen.generateKeyPair()

            // 2. Build self-signed X.509 certificate
            val cert = buildSelfSignedCert(keyPair)

            // 3. Save PEM to SharedPreferences
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_CA_CERT_PEM, toPem(cert.encoded, "CERTIFICATE"))
                .putString(KEY_CA_KEY_PEM,  toPem(keyPair.private.encoded, "PRIVATE KEY"))
                .apply()

            Log.i(TAG, "CA generated successfully: ${cert.subjectDN}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "CA generation failed", e)
            false
        }
    }

    /**
     * Build a minimal self-signed X.509 v3 CA certificate.
     *
     * আমরা এখানে Java এর standard library ব্যবহার করছি।
     * Production এ Bouncy Castle ব্যবহার করলে আরও complete হবে।
     */
    private fun buildSelfSignedCert(keyPair: KeyPair): X509Certificate {
        // Use Android's internal sun.security.x509 via reflection
        // (এটা সব Android version এ কাজ করে)
        val certClass   = Class.forName("sun.security.x509.X509CertImpl")
        val infoClass   = Class.forName("sun.security.x509.X509CertInfo")
        val intervalClass = Class.forName("sun.security.x509.CertificateValidity")
        val bigIntClass   = Class.forName("sun.security.x509.CertificateSerialNumber")
        val algIdClass    = Class.forName("sun.security.x509.AlgorithmId")
        val nameClass     = Class.forName("sun.security.x509.X500Name")

        val now     = Date()
        val notAfter = Date(now.time + 10L * 365 * 24 * 60 * 60 * 1000) // 10 years
        val validity = intervalClass.getConstructor(Date::class.java, Date::class.java)
            .newInstance(now, notAfter)
        val serial   = bigIntClass.getConstructor(Int::class.java, Random::class.java)
            .newInstance(64, Random())
        val owner    = nameClass.getConstructor(String::class.java)
            .newInstance("CN=Rasel AdBlock CA, O=RaselBlocker, C=BD")
        val algId    = algIdClass.getMethod("get", String::class.java)
            .invoke(null, "SHA256withECDSA")

        val info = infoClass.newInstance()
        val SET = { field: String, value: Any ->
            infoClass.getMethod("set", String::class.java, Any::class.java)
                .invoke(info, field, value)
        }
        SET("version",  Class.forName("sun.security.x509.CertificateVersion").getConstructor(Int::class.java).newInstance(2))
        SET("serialNumber", serial)
        SET("algorithmID", Class.forName("sun.security.x509.CertificateAlgorithmId").getConstructor(algIdClass).newInstance(algId))
        SET("subject",  Class.forName("sun.security.x509.CertificateSubjectName").getConstructor(nameClass).newInstance(owner))
        SET("issuer",   Class.forName("sun.security.x509.CertificateIssuerName").getConstructor(nameClass).newInstance(owner))
        SET("validity", Class.forName("sun.security.x509.CertificateValidity").cast(validity))
        SET("key",      Class.forName("sun.security.x509.CertificateX509Key").getConstructor(PublicKey::class.java).newInstance(keyPair.public))

        val cert = certClass.getConstructor(infoClass).newInstance(info)
        certClass.getMethod("sign", PrivateKey::class.java, String::class.java)
            .invoke(cert, keyPair.private, "SHA256withECDSA")

        return cert as X509Certificate
    }

    // ── Get saved cert PEM ───────────────────────────────────────────
    fun getCaCertPem(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CA_CERT_PEM, null)
    }

    fun getCaKeyPem(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CA_KEY_PEM, null)
    }

    fun hasCa(context: Context): Boolean = getCaCertPem(context) != null

    // ── Get X509Certificate object from saved PEM ────────────────────
    fun getCaCert(context: Context): X509Certificate? {
        val pem = getCaCertPem(context) ?: return null
        return try {
            val der = Base64.decode(
                pem.replace("-----BEGIN CERTIFICATE-----", "")
                   .replace("-----END CERTIFICATE-----", "")
                   .replace("\\s".toRegex(), ""),
                Base64.DEFAULT
            )
            CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()) as X509Certificate
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse CA cert", e)
            null
        }
    }

    /**
     * User কে CA certificate install করার জন্য system dialog খোলা।
     *
     * Android এ User CA install হলে:
     *  - Browser এ HTTPS ads block হবে
     *  - Network Security Config ব্যবহার না করা apps এ কাজ করবে
     *
     * Root ছাড়া System CA হিসেবে install করা যায় না।
     */
    fun promptInstallCa(context: Context): Intent? {
        val pem = getCaCertPem(context) ?: return null
        val der = Base64.decode(
            pem.replace("-----BEGIN CERTIFICATE-----", "")
               .replace("-----END CERTIFICATE-----", "")
               .replace("\\s".toRegex(), ""),
            Base64.DEFAULT
        )
        return KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, der)
            putExtra(KeyChain.EXTRA_NAME, CA_ALIAS)
        }
    }

    // ── DER → PEM conversion ─────────────────────────────────────────
    private fun toPem(der: ByteArray, type: String): String {
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
        val wrapped = b64.chunked(64).joinToString("\n")
        return "-----BEGIN $type-----\n$wrapped\n-----END $type-----\n"
    }

    // ── Export CA cert as DER bytes (for asset embedding) ───────────
    fun getCaDerBytes(context: Context): ByteArray? {
        val pem = getCaCertPem(context) ?: return null
        return Base64.decode(
            pem.replace("-----BEGIN CERTIFICATE-----", "")
               .replace("-----END CERTIFICATE-----", "")
               .replace("\\s".toRegex(), ""),
            Base64.DEFAULT
        )
    }
}
