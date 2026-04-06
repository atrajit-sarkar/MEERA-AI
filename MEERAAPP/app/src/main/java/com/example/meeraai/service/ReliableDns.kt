package com.example.meeraai.service

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Custom DNS resolver that uses DNS-over-HTTPS (DoH) to bypass flaky system DNS.
 * Tries Cloudflare and Google DoH if system DNS fails.
 */
object ReliableDns : Dns {

    private val bootstrapClient = OkHttpClient.Builder().build()

    private val dnsProviders = listOf(
        // Cloudflare DoH
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://1.1.1.1/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1")))
            .build(),
        // Google DoH
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://8.8.8.8/resolve".toHttpUrl())
            .bootstrapDnsHosts(listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4")))
            .build()
    )

    override fun lookup(hostname: String): List<InetAddress> {
        // 1. Try System DNS
        try {
            val result = Dns.SYSTEM.lookup(hostname)
            if (result.isNotEmpty()) return result
        } catch (e: Exception) {
            // Log or ignore
        }

        // 2. Try DoH Providers
        for (provider in dnsProviders) {
            try {
                val result = provider.lookup(hostname)
                if (result.isNotEmpty()) return result
            } catch (e: Exception) {
                continue
            }
        }

        throw UnknownHostException("Could not resolve $hostname using system or DoH")
    }
}
