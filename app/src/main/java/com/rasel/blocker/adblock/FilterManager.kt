package com.rasel.blocker.adblock

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * FilterManager - YouTube, Website ও App Ads এর DNS block list
 */
object FilterManager {

    private val _blockedCount = AtomicInteger(0)
    val blockedCount: Int get() = _blockedCount.get()

    private val youtubeEnabled  = AtomicBoolean(true)
    private val websiteEnabled  = AtomicBoolean(true)
    private val appAdsEnabled   = AtomicBoolean(true)
    private val dnsEnabled      = AtomicBoolean(true)

    fun enableYoutubeFilter(v: Boolean) { youtubeEnabled.set(v) }
    fun enableWebsiteFilter(v: Boolean) { websiteEnabled.set(v) }
    fun enableAppAdsFilter(v: Boolean)  { appAdsEnabled.set(v) }
    fun enableDnsFilter(v: Boolean)     { dnsEnabled.set(v) }
    fun incrementBlocked()              { _blockedCount.incrementAndGet() }
    fun resetStats()                    { _blockedCount.set(0) }

    /** C++ engine এ push করার জন্য সব domains এক set এ */
    fun getAllDomains(): Set<String> = youtubeDomains + websiteDomains + appAdDomains

    // YouTube ad domains
    private val youtubeDomains = setOf(
        "ad.doubleclick.net", "ads.youtube.com", "pagead2.googlesyndication.com",
        "pagead.googlesyndication.com", "googleads.g.doubleclick.net",
        "pubads.g.doubleclick.net", "securepubads.g.doubleclick.net",
        "imasdk.googleapis.com", "ima3.googleapis.com",
        "www.googletagmanager.com", "googletagmanager.com",
        "www.googletagservices.com", "googletagservices.com",
        "gads.g.doubleclick.net", "static.doubleclick.net"
    )

    // Website ad + tracker domains
    private val websiteDomains = setOf(
        "doubleclick.net", "googlesyndication.com", "adservice.google.com",
        "ads.google.com", "adsense.google.com", "tpc.googlesyndication.com",
        "partner.googleadservices.com", "outbrain.com", "taboola.com",
        "ads.taboola.com", "trc.taboola.com", "amazon-adsystem.com",
        "advertising.amazon.com", "adform.net", "adsrvr.org",
        "openx.net", "rubiconproject.com", "pubmatic.com", "appnexus.com",
        "ib.adnxs.com", "criteo.com", "cas.criteo.com", "media.net",
        "sharethrough.com", "33across.com", "sovrn.com", "lijit.com",
        "google-analytics.com", "stats.g.doubleclick.net",
        "connect.facebook.net", "an.facebook.com", "hotjar.com",
        "static.hotjar.com", "fullstory.com", "mouseflow.com",
        "segment.com", "mixpanel.com", "amplitude.com",
        "bat.bing.com", "ads.linkedin.com", "ads.twitter.com",
        "analytics.tiktok.com", "ads.tiktok.com", "log.tiktokv.com"
    )

    // In-app ad SDK domains
    private val appAdDomains = setOf(
        // AdMob / Google
        "admob.googleapis.com", "mobileads.google.com", "googleads.googleapis.com",
        "pagead2.googlesyndication.com",
        // Facebook Audience Network
        "an.facebook.com", "edge-mqtt.facebook.com",
        // Unity Ads
        "auction.unityads.unity3d.com", "unityads.unity3d.com",
        "config.unityads.unity3d.com", "adserver.unityads.unity3d.com",
        // ironSource
        "central.ironsrc.mobi", "outcome.ironsrc.mobi", "api.ironsrc.com",
        // AppLovin
        "ms.applovin.com", "rt.applovin.com", "o.applovin.com", "a.applovin.com",
        // Chartboost
        "live.chartboost.com", "chartboost.com",
        // InMobi
        "c.inmobi.com", "b.inmobi.com", "api.inmobi.com",
        // Vungle
        "api.vungle.com", "vungle.com",
        // Startapp
        "infoevent.startapp.com", "rta.startapp.com", "startapp.com",
        // AdColony
        "ads.adcolony.com", "events.adcolony.com",
        // Fyber
        "ads.fyber.com", "fyber.com",
        // Flurry
        "data.flurry.com", "ads.flurry.com", "log.flurry.com",
        // Adjust & AppsFlyer (tracking)
        "app.adjust.com", "t.appsflyer.com", "impression.appsflyer.com",
        // Branch
        "api2.branch.io"
    )

    fun shouldBlock(domain: String): Boolean {
        if (!dnsEnabled.get()) return false
        val d = domain.lowercase().trimEnd('.')
        if (youtubeEnabled.get() && matchesDomainSet(d, youtubeDomains)) return true
        if (websiteEnabled.get() && matchesDomainSet(d, websiteDomains)) return true
        if (appAdsEnabled.get() && matchesDomainSet(d, appAdDomains)) return true
        return false
    }

    private fun matchesDomainSet(domain: String, set: Set<String>): Boolean {
        if (set.contains(domain)) return true
        var dot = domain.indexOf('.')
        while (dot != -1) {
            if (set.contains(domain.substring(dot + 1))) return true
            dot = domain.indexOf('.', dot + 1)
        }
        return false
    }
}

    // ── Kotlin fallback methods (used if C++ engine not available) ──

    fun parseDomainFallback(dns: ByteArray): String {
        return try {
            var pos = 12; val sb = StringBuilder()
            while (pos < dns.size) {
                val l = dns[pos].toInt() and 0xFF
                if (l == 0) break
                if (sb.isNotEmpty()) sb.append('.')
                pos++; if (pos + l > dns.size) break
                sb.append(String(dns, pos, l)); pos += l
            }
            sb.toString().lowercase()
        } catch (_: Exception) { "" }
    }

    fun buildNxDomainFallback(q: ByteArray): ByteArray {
        val r = q.copyOf()
        if (r.size >= 4) { r[2] = (r[2].toInt() or 0x80).toByte(); r[3] = (r[3].toInt() or 0x83).toByte() }
        return r
    }

    fun calcChecksumFallback(pkt: ByteArray, ihl: Int) {
        pkt[10] = 0; pkt[11] = 0
        var sum = 0
        for (i in 0 until ihl step 2)
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        sum = sum.inv() and 0xFFFF
        pkt[10] = (sum shr 8).toByte(); pkt[11] = (sum and 0xFF).toByte()
    }
