/**
 * packet_engine.cpp
 * ─────────────────────────────────────────────────────────────────
 * Native C++ Ad Block Packet Engine
 *
 * কাজ:
 *  1. IPv4 packet parse করে (IP header, TCP/UDP header)
 *  2. DNS query থেকে domain name extract করে
 *  3. SNI (Server Name Indication) TLS packet থেকে বের করে
 *     যাতে HTTPS traffic ও identify করা যায় certificate ছাড়া
 *  4. Block list match করে (exact + subdomain)
 *  5. NXDOMAIN response build করে
 *
 * এই engine টা JNI দিয়ে Kotlin থেকে call করা হয়
 * ─────────────────────────────────────────────────────────────────
 */

#include <jni.h>
#include <string>
#include <vector>
#include <unordered_set>
#include <algorithm>
#include <cstring>
#include <arpa/inet.h>
#include <android/log.h>

#define LOG_TAG "AdBlockEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────
// Global block list (unordered_set = O(1) lookup)
// ─────────────────────────────────────────────
static std::unordered_set<std::string> g_blockList;
static bool g_enabled = true;

// ─────────────────────────────────────────────
// Utility: lowercase a string in place
// ─────────────────────────────────────────────
static void to_lower(std::string &s) {
    std::transform(s.begin(), s.end(), s.begin(), ::tolower);
}

// ─────────────────────────────────────────────
// Domain match: exact OR subdomain
// e.g. "sub.doubleclick.net" matches "doubleclick.net"
// ─────────────────────────────────────────────
static bool domain_matches(const std::string &domain) {
    if (!g_enabled) return false;
    std::string d = domain;
    // strip trailing dot
    if (!d.empty() && d.back() == '.') d.pop_back();
    to_lower(d);

    // exact match
    if (g_blockList.count(d)) return true;

    // walk up parent domains
    size_t pos = 0;
    while ((pos = d.find('.', pos)) != std::string::npos) {
        ++pos;
        std::string parent = d.substr(pos);
        if (g_blockList.count(parent)) return true;
    }
    return false;
}

// ─────────────────────────────────────────────
// Parse DNS query domain from raw DNS payload
// DNS wire format: length-prefixed labels ending with 0x00
// ─────────────────────────────────────────────
static std::string parse_dns_domain(const uint8_t *dns, int len) {
    if (len < 13) return "";
    std::string result;
    int pos = 12; // skip 12-byte DNS header
    while (pos < len) {
        int label_len = dns[pos] & 0xFF;
        if (label_len == 0) break;
        // pointer compression (0xC0 prefix) — skip for queries
        if ((label_len & 0xC0) == 0xC0) break;
        ++pos;
        if (pos + label_len > len) break;
        if (!result.empty()) result += '.';
        result.append(reinterpret_cast<const char*>(dns + pos), label_len);
        pos += label_len;
    }
    to_lower(result);
    return result;
}

// ─────────────────────────────────────────────
// Build NXDOMAIN response
// Copy query, flip QR bit, set RCODE = 3
// ─────────────────────────────────────────────
static std::vector<uint8_t> build_nxdomain(const uint8_t *query, int len) {
    std::vector<uint8_t> resp(query, query + len);
    if (resp.size() >= 4) {
        resp[2] = resp[2] | 0x80; // QR = 1 (response)
        resp[3] = resp[3] | 0x83; // RA = 1, RCODE = 3 (NXDOMAIN)
    }
    return resp;
}

// ─────────────────────────────────────────────
// Parse SNI from TLS ClientHello (TCP port 443)
//
// TLS record:  [0]=0x16 (handshake), [1..2]=version, [3..4]=length
// Handshake:   [5]=0x01 (ClientHello), [6..8]=length
// ClientHello: ... extensions → SNI extension type=0x0000
//
// This lets us block HTTPS domains WITHOUT a CA cert
// by killing the connection at TCP level (return BLOCK signal)
// ─────────────────────────────────────────────
static std::string parse_tls_sni(const uint8_t *data, int len) {
    // Minimum TLS record + ClientHello
    if (len < 43) return "";
    // TLS Handshake record
    if (data[0] != 0x16) return "";            // Content type: Handshake
    if (data[5] != 0x01) return "";            // Handshake type: ClientHello

    int pos = 9;  // Skip: record header(5) + handshake header(4)

    // ProtocolVersion (2)
    pos += 2;
    // Random (32)
    pos += 32;
    if (pos >= len) return "";

    // Session ID length (1)
    int sid_len = data[pos++] & 0xFF;
    pos += sid_len;
    if (pos + 2 >= len) return "";

    // Cipher suites length (2)
    int cs_len = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
    pos += 2 + cs_len;
    if (pos + 1 >= len) return "";

    // Compression methods length (1)
    int cm_len = data[pos++] & 0xFF;
    pos += cm_len;
    if (pos + 2 >= len) return "";

    // Extensions length (2)
    int ext_total = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
    pos += 2;
    int ext_end = pos + ext_total;

    // Walk extensions
    while (pos + 4 <= ext_end && pos + 4 <= len) {
        int ext_type = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
        int ext_len  = ((data[pos+2] & 0xFF) << 8) | (data[pos+3] & 0xFF);
        pos += 4;

        if (ext_type == 0x0000) { // SNI extension
            // ServerNameList length (2)
            if (pos + 2 > len) break;
            pos += 2;
            // ServerName type (1) = 0x00 for hostname
            if (pos + 1 > len) break;
            int sni_type = data[pos++] & 0xFF;
            if (sni_type != 0x00) break;
            // HostName length (2)
            if (pos + 2 > len) break;
            int name_len = ((data[pos] & 0xFF) << 8) | (data[pos+1] & 0xFF);
            pos += 2;
            if (pos + name_len > len) break;
            std::string sni(reinterpret_cast<const char*>(data + pos), name_len);
            to_lower(sni);
            return sni;
        }
        pos += ext_len;
    }
    return "";
}

// ─────────────────────────────────────────────
// Recalculate IPv4 header checksum
// ─────────────────────────────────────────────
static void recalc_ip_checksum(uint8_t *pkt, int ihl) {
    pkt[10] = 0; pkt[11] = 0;
    uint32_t sum = 0;
    for (int i = 0; i < ihl; i += 2)
        sum += ((uint32_t)(pkt[i] & 0xFF) << 8) | (pkt[i+1] & 0xFF);
    while (sum >> 16) sum = (sum & 0xFFFF) + (sum >> 16);
    sum = ~sum & 0xFFFF;
    pkt[10] = (sum >> 8) & 0xFF;
    pkt[11] = sum & 0xFF;
}

// ═════════════════════════════════════════════
//  JNI EXPORTS — called from Kotlin
// ═════════════════════════════════════════════

extern "C" {

/**
 * nativeSetEnabled(enabled: Boolean)
 * Engine on/off switch
 */
JNIEXPORT void JNICALL
Java_com_rasel_blocker_adblock_NativeEngine_nativeSetEnabled(
        JNIEnv *, jobject, jboolean enabled) {
    g_enabled = (bool)enabled;
    LOGI("Engine enabled: %d", (int)g_enabled);
}

/**
 * nativeSetBlockList(domains: Array<String>)
 * Load the block list into native memory (fast hash set)
 */
JNIEXPORT void JNICALL
Java_com_rasel_blocker_adblock_NativeEngine_nativeSetBlockList(
        JNIEnv *env, jobject, jobjectArray domains) {
    g_blockList.clear();
    jsize count = env->GetArrayLength(domains);
    g_blockList.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto jstr = (jstring)env->GetObjectArrayElement(domains, i);
        const char *str = env->GetStringUTFChars(jstr, nullptr);
        if (str) {
            g_blockList.insert(std::string(str));
            env->ReleaseStringUTFChars(jstr, str);
        }
        env->DeleteLocalRef(jstr);
    }
    LOGI("Block list loaded: %zu domains", g_blockList.size());
}

/**
 * nativeParseDnsDomain(dnsPayload: ByteArray): String
 * Extract domain name from raw DNS query bytes
 */
JNIEXPORT jstring JNICALL
Java_com_rasel_blocker_adblock_NativeEngine_nativeParseDnsDomain(
        JNIEnv *env, jobject, jbyteArray payload) {
    jsize len = env->GetArrayLength(payload);
    auto *buf = (uint8_t*)env->GetByteArrayElements(payload, nullptr);
    std::string domain = parse_dns_domain(buf, (int)len);
    env->ReleaseByteArrayElements(payload, (jbyte*)buf, JNI_ABORT);
    return env->NewStringUTF(domain.c_str());
}

/**
 * nativeShouldBlock(domain: String): Boolean
 * Check if domain is in block list (O(1) average)
 */
JNIEXPORT jboolean JNICALL
Java_com_rasel_blocker_adblock_NativeEngine_nativeShouldBlock(
        JNIEnv *env, jobject, jstring jdomain) {
    const char *d = env->GetStringUTFChars(jdomain, nullptr);
    bool blocked = domain_matches(std::string(d));
    env->ReleaseStringUTFChars(jdomain, d);
    return (jboolean)blocked;
}

/**
 * nativeBuildNxDomain(dnsQuery: ByteArray): ByteArray
 * Build a NXDOMAIN response for a blocked DNS query
 */
JNIEXPORT jbyteArray JNICALL
Java_com_rasel_blocker_adblock_NativeEngine_nativeBuildNxDomain(
        JNIEnv *env, jobject, jbyteArray query) {
    jsize len = env->GetArrayLength(query);
    auto *buf = (uint8_t*)env->GetByteArrayElements(query, nullptr);
    auto resp = build_nxdomain(buf, (int)len);
    env->ReleaseByteArrayElements(query, (jbyte*)buf, JNI_ABORT);
    jbyteArray result = env->NewByteArray((jsize)resp.size());
    env->SetByteArrayRegion(result, 0, (jsize)resp.size(), (jbyte*)resp.data());
    return result;
}

/**
 * nativeParseTlsSni(tcpPayload: ByteArray): String
 * Extract SNI hostname from TLS ClientHello (no cert needed)
 * Returns "" if not a TLS ClientHello or SNI absent
 */
JNIEXPORT jstring JNICALL
Java_com_rasel_blocker_adblock_NativeEngine_nativeParseTlsSni(
        JNIEnv *env, jobject, jbyteArray payload) {
    jsize len = env->GetArrayLength(payload);
    auto *buf = (uint8_t*)env->GetByteArrayElements(payload, nullptr);
    std::string sni = parse_tls_sni(buf, (int)len);
    env->ReleaseByteArrayElements(payload, (jbyte*)buf, JNI_ABORT);
    return env->NewStringUTF(sni.c_str());
}

/**
 * nativeRecalcIpChecksum(packet: ByteArray, ihl: Int)
 * Fix IP header checksum after we modify src/dst
 */
JNIEXPORT void JNICALL
Java_com_rasel_blocker_adblock_NativeEngine_nativeRecalcIpChecksum(
        JNIEnv *env, jobject, jbyteArray packet, jint ihl) {
    jsize len = env->GetArrayLength(packet);
    auto *buf = (uint8_t*)env->GetByteArrayElements(packet, nullptr);
    if (ihl <= len) recalc_ip_checksum(buf, ihl);
    env->ReleaseByteArrayElements(packet, (jbyte*)buf, 0); // 0 = write back
}

} // extern "C"
