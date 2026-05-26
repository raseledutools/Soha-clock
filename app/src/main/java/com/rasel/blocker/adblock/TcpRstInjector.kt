package com.rasel.blocker.adblock

import android.util.Log

/**
 * TcpRstInjector
 * ─────────────────────────────────────────────────────────────────
 * SNI দেখে blocked domain হলে TCP RST packet পাঠায়।
 *
 * কীভাবে কাজ করে:
 *  1. TLS ClientHello packet এ SNI পড়া (C++ engine করে)
 *  2. Domain blocked → TCP RST packet বানানো
 *  3. RST packet TUN interface এ লেখা
 *  4. Client মনে করে server connection refuse করেছে
 *  5. HTTPS request fail হয় → ad load হয় না
 *
 * DNS blocking এর সাথে এটা দ্বিতীয় স্তরের protection।
 * DNS cache করা domain বা hard-coded IP তে যাওয়া ads ও block হবে।
 * ─────────────────────────────────────────────────────────────────
 */
object TcpRstInjector {

    private const val TAG = "TcpRstInjector"

    /**
     * TCP RST packet তৈরি করা।
     *
     * @param origPacket  — original TCP packet (raw bytes)
     * @param ihl         — IP header length (bytes)
     * @return RST packet bytes, অথবা null যদি fail হয়
     */
    fun buildRstPacket(origPacket: ByteArray, ihl: Int): ByteArray? {
        return try {
            if (origPacket.size < ihl + 20) return null

            val pkt = ByteArray(ihl + 20) // IP header + minimal TCP header

            // ── IP Header ──────────────────────────────────────────────
            // Version + IHL
            pkt[0] = ((4 shl 4) or (ihl / 4)).toByte()
            // DSCP/ECN
            pkt[1] = 0
            // Total length
            val totalLen = ihl + 20
            pkt[2] = (totalLen shr 8).toByte()
            pkt[3] = (totalLen and 0xFF).toByte()
            // Identification (0)
            pkt[4] = 0; pkt[5] = 0
            // Flags + Fragment offset (Don't Fragment)
            pkt[6] = 0x40; pkt[7] = 0
            // TTL
            pkt[8] = 64
            // Protocol: TCP
            pkt[9] = 6
            // Checksum (placeholder, calculated below)
            pkt[10] = 0; pkt[11] = 0
            // Src IP = orig Dst IP (swap)
            System.arraycopy(origPacket, 16, pkt, 12, 4)
            // Dst IP = orig Src IP (swap)
            System.arraycopy(origPacket, 12, pkt, 16, 4)

            // ── TCP Header ──────────────────────────────────────────────
            val tcpOff = ihl
            // Src Port = orig Dst Port
            pkt[tcpOff]     = origPacket[tcpOff + 2]
            pkt[tcpOff + 1] = origPacket[tcpOff + 3]
            // Dst Port = orig Src Port
            pkt[tcpOff + 2] = origPacket[tcpOff]
            pkt[tcpOff + 3] = origPacket[tcpOff + 1]

            // Sequence number = orig ACK number
            System.arraycopy(origPacket, tcpOff + 8, pkt, tcpOff + 4, 4)
            // ACK number = orig Seq + 1
            val origSeq = ((origPacket[tcpOff + 4].toInt() and 0xFF) shl 24) or
                          ((origPacket[tcpOff + 5].toInt() and 0xFF) shl 16) or
                          ((origPacket[tcpOff + 6].toInt() and 0xFF) shl 8)  or
                           (origPacket[tcpOff + 7].toInt() and 0xFF)
            val ackNum = origSeq + 1
            pkt[tcpOff + 8]  = (ackNum shr 24).toByte()
            pkt[tcpOff + 9]  = (ackNum shr 16).toByte()
            pkt[tcpOff + 10] = (ackNum shr 8).toByte()
            pkt[tcpOff + 11] = (ackNum and 0xFF).toByte()

            // Data offset (5 * 4 = 20 bytes, no options)
            pkt[tcpOff + 12] = (5 shl 4).toByte()
            // Flags: RST + ACK
            pkt[tcpOff + 13] = 0x14  // RST(0x04) + ACK(0x10)
            // Window size: 0
            pkt[tcpOff + 14] = 0; pkt[tcpOff + 15] = 0
            // Checksum (placeholder)
            pkt[tcpOff + 16] = 0; pkt[tcpOff + 17] = 0
            // Urgent pointer
            pkt[tcpOff + 18] = 0; pkt[tcpOff + 19] = 0

            // ── Checksums ───────────────────────────────────────────────
            // IP checksum
            calcIpChecksum(pkt, ihl)
            // TCP checksum
            calcTcpChecksum(pkt, ihl)

            pkt
        } catch (e: Exception) {
            Log.e(TAG, "buildRstPacket failed: ${e.message}")
            null
        }
    }

    // ── IP header checksum ─────────────────────────────────────────
    private fun calcIpChecksum(pkt: ByteArray, ihl: Int) {
        pkt[10] = 0; pkt[11] = 0
        var sum = 0
        for (i in 0 until ihl step 2)
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        sum = sum.inv() and 0xFFFF
        pkt[10] = (sum shr 8).toByte()
        pkt[11] = (sum and 0xFF).toByte()
    }

    // ── TCP checksum (pseudo-header দিয়ে) ─────────────────────────
    private fun calcTcpChecksum(pkt: ByteArray, ihl: Int) {
        val tcpOff  = ihl
        val tcpLen  = pkt.size - ihl
        pkt[tcpOff + 16] = 0; pkt[tcpOff + 17] = 0

        var sum = 0

        // Pseudo-header: src IP + dst IP + 0 + protocol(6) + TCP length
        for (i in 12..15)
            sum += if (i % 2 == 0)
                ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            else 0
        sum += ((pkt[12].toInt() and 0xFF) shl 8) or (pkt[13].toInt() and 0xFF)
        sum += ((pkt[14].toInt() and 0xFF) shl 8) or (pkt[15].toInt() and 0xFF)
        sum += ((pkt[16].toInt() and 0xFF) shl 8) or (pkt[17].toInt() and 0xFF)
        sum += ((pkt[18].toInt() and 0xFF) shl 8) or (pkt[19].toInt() and 0xFF)
        sum += 6           // TCP protocol
        sum += tcpLen      // TCP segment length

        // TCP segment
        var i = tcpOff
        while (i < pkt.size - 1) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (pkt.size % 2 != 0) sum += (pkt.last().toInt() and 0xFF) shl 8

        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        sum = sum.inv() and 0xFFFF

        pkt[tcpOff + 16] = (sum shr 8).toByte()
        pkt[tcpOff + 17] = (sum and 0xFF).toByte()
    }
}
