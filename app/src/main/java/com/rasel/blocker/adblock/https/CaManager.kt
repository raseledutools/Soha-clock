package com.rasel.blocker.adblock.https

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.*

/**
 * CaManager — Bouncy Castle দিয়ে CA cert তৈরি
 *
 * পুরনো sun.security.x509 reflection বাদ দিয়ে
 * Bouncy Castle ব্যবহার করা হয়েছে।
 * সব Android version এ কাজ করবে।
 */
object CaManager {

    private const val TAG = "CaManager"
    private const val PREFS = "adblock_ca_prefs"
    private const val KEY_CA_CERT_PEM = "ca_cert_pem"
    private const val KEY_CA_KEY_PEM  = "ca_key_pem"
    const val CA_ALIAS = "RaselAdBlockCA"

    // ── Generate EC key pair + self-signed CA cert (Bouncy Castle) ──
    fun generateCa(context: Context): Boolean {
        return try {
            // 1. EC key pair (P-256 — দ্রুত এবং secure)
            val keyGen = KeyPairGenerator.getInstance("EC")
            keyGen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            val kp = keyGen.generateKeyPair()

            // 2. Bouncy Castle দিয়ে proper X.509 CA cert build
            val cert = buildCaWithBouncyCastle(kp)

            // 3. SharedPrefs এ save করা
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_CA_CERT_PEM, toPem(cert.encoded, "CERTIFICATE"))
                .putString(KEY_CA_KEY_PEM,  toPem(kp.private.encoded, "PRIVATE KEY"))
                .apply()

            Log.i(TAG, "✅ CA generated: ${cert.subjectDN}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "CA generation failed", e)
            false
        }
    }

    /**
     * Bouncy Castle দিয়ে CA certificate তৈরি।
     *
     * পুরনো sun.security reflection বাদ — এটা সব Android এ কাজ করে।
     * CA flag set আছে (BasicConstraints = true) তাই sub-cert sign করতে পারবে।
     */
    private fun buildCaWithBouncyCastle(kp: KeyPair): X509Certificate {
        val now     = Date()
        val notAfter = Date(now.time + 10L * 365 * 24 * 60 * 60 * 1000) // 10 বছর

        val subject = X500Name("CN=Rasel AdBlock CA, O=RaselBlocker, C=BD")

        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            subject,                            // issuer (self-signed)
            BigInteger.valueOf(SecureRandom().nextLong().and(Long.MAX_VALUE)),
            now,
            notAfter,
            subject,                            // subject
            kp.public
        )

        // CA:TRUE — এটা CA certificate
        builder.addExtension(
            Extension.basicConstraints, true,
            BasicConstraints(true)
        )

        // Key Usage: keyCertSign + cRLSign
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )

        val signer = JcaContentSignerBuilder("SHA256withECDSA")
            .build(kp.private)

        return JcaX509CertificateConverter()
            .getCertificate(builder.build(signer))
    }

    // ── Saved cert / key access ──────────────────────────────────────
    fun getCaCertPem(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CA_CERT_PEM, null)

    fun getCaKeyPem(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CA_KEY_PEM, null)

    fun hasCa(context: Context): Boolean = getCaCertPem(context) != null

    fun getCaCert(context: Context): X509Certificate? {
        val pem = getCaCertPem(context) ?: return null
        return try {
            val der = pemToDer(pem, "CERTIFICATE")
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse CA cert", e)
            null
        }
    }

    fun getCaPrivateKey(context: Context): PrivateKey? {
        val pem = getCaKeyPem(context) ?: return null
        return try {
            val der = pemToDer(pem, "PRIVATE KEY")
            // EC key
            java.security.KeyFactory.getInstance("EC")
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse CA private key", e)
            null
        }
    }

    /**
     * Per-domain fake certificate বানানো (CA দিয়ে sign করা)।
     * HttpsInterceptor এটা ব্যবহার করবে।
     */
    fun signFakeCert(
        context: Context,
        targetHost: String,
        realCert: X509Certificate?
    ): X509Certificate? {
        val caCert  = getCaCert(context)   ?: return null
        val caKey   = getCaPrivateKey(context) ?: return null

        return try {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()

            val subject = try {
                X500Name(realCert?.subjectDN?.name ?: "CN=$targetHost")
            } catch (_: Exception) {
                X500Name("CN=$targetHost")
            }
            val issuer = X500Name(caCert.subjectDN.name)
            val now    = Date()
            val expiry = Date(now.time + 365L * 24 * 3600 * 1000)

            val builder = JcaX509v3CertificateBuilder(
                issuer,
                BigInteger.valueOf(SecureRandom().nextLong().and(Long.MAX_VALUE)),
                now, expiry,
                subject,
                kp.public
            )

            // SAN — Subject Alternative Name
            val sanExt = org.bouncycastle.asn1.x509.GeneralNamesBuilder()
                .addName(
                    org.bouncycastle.asn1.x509.GeneralName(
                        org.bouncycastle.asn1.x509.GeneralName.dNSName,
                        targetHost
                    )
                ).build()
            builder.addExtension(Extension.subjectAlternativeName, false, sanExt)

            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(caKey)
            JcaX509CertificateConverter().getCertificate(builder.build(signer))
        } catch (e: Exception) {
            Log.e(TAG, "signFakeCert failed for $targetHost", e)
            null
        }
    }

    // ── User কে CA install করতে dialog দেখানো ────────────────────────
    fun promptInstallCa(context: Context): Intent? {
        val pem = getCaCertPem(context) ?: return null
        val der = pemToDer(pem, "CERTIFICATE")
        return KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, der)
            putExtra(KeyChain.EXTRA_NAME, CA_ALIAS)
        }
    }

    fun getCaDerBytes(context: Context): ByteArray? {
        val pem = getCaCertPem(context) ?: return null
        return try { pemToDer(pem, "CERTIFICATE") } catch (_: Exception) { null }
    }

    // ── Helpers ───────────────────────────────────────────────────────
    private fun toPem(der: ByteArray, type: String): String {
        val b64     = Base64.encodeToString(der, Base64.NO_WRAP)
        val wrapped = b64.chunked(64).joinToString("\n")
        return "-----BEGIN $type-----\n$wrapped\n-----END $type-----\n"
    }

    private fun pemToDer(pem: String, type: String): ByteArray {
        val clean = pem
            .replace("-----BEGIN $type-----", "")
            .replace("-----END $type-----", "")
            .replace("\\s".toRegex(), "")
        return Base64.decode(clean, Base64.DEFAULT)
    }
}
