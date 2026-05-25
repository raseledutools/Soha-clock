package com.rasel.blocker.adblock

import android.util.Log

/**
 * NativeEngine
 * ─────────────────────────────────────────────────────────────────
 * Kotlin bridge to the C++ packet_engine.so
 *
 * C++ এ লেখা functions গুলো JNI দিয়ে এখান থেকে call করা হয়।
 * C++ engine অনেক দ্রুত কারণ:
 *  - JVM overhead নেই
 *  - O(1) hash set lookup
 *  - Low-level byte manipulation
 * ─────────────────────────────────────────────────────────────────
 */
object NativeEngine {

    private const val TAG = "NativeEngine"
    private var loaded = false

    init {
        try {
            System.loadLibrary("adblock_engine")
            loaded = true
            Log.i(TAG, "✅ Native engine loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ Native engine load failed — falling back to Kotlin: ${e.message}")
        }
    }

    val isAvailable: Boolean get() = loaded

    // ── Native function declarations ───────────────────────────────

    /** Engine চালু/বন্ধ করা */
    external fun nativeSetEnabled(enabled: Boolean)

    /** Block list C++ এ load করা (fast hash set) */
    external fun nativeSetBlockList(domains: Array<String>)

    /** DNS query থেকে domain extract করা */
    external fun nativeParseDnsDomain(dnsPayload: ByteArray): String

    /** Domain block হবে কিনা check (O(1) average) */
    external fun nativeShouldBlock(domain: String): Boolean

    /** NXDOMAIN response build করা */
    external fun nativeBuildNxDomain(dnsQuery: ByteArray): ByteArray

    /** TLS ClientHello থেকে SNI বের করা (CA cert ছাড়া HTTPS identify) */
    external fun nativeParseTlsSni(tcpPayload: ByteArray): String

    /** IP header checksum recalculate করা */
    external fun nativeRecalcIpChecksum(packet: ByteArray, ihl: Int)

    // ── Kotlin fallback (যদি native load না হয়) ──────────────────

    fun parseDnsDomain(payload: ByteArray): String =
        if (loaded) nativeParseDnsDomain(payload)
        else FilterManager.parseDomainFallback(payload)

    fun shouldBlock(domain: String): Boolean =
        if (loaded) nativeShouldBlock(domain)
        else FilterManager.shouldBlock(domain)

    fun buildNxDomain(query: ByteArray): ByteArray =
        if (loaded) nativeBuildNxDomain(query)
        else FilterManager.buildNxDomainFallback(query)

    fun parseTlsSni(tcpPayload: ByteArray): String =
        if (loaded) nativeParseTlsSni(tcpPayload)
        else ""

    fun recalcIpChecksum(packet: ByteArray, ihl: Int) {
        if (loaded) nativeRecalcIpChecksum(packet, ihl)
        else FilterManager.calcChecksumFallback(packet, ihl)
    }

    /** Block list কে C++ এ push করা */
    fun loadBlockList(domains: Set<String>) {
        if (loaded) {
            nativeSetBlockList(domains.toTypedArray())
            Log.i(TAG, "Block list pushed to C++: ${domains.size} domains")
        }
    }
}
