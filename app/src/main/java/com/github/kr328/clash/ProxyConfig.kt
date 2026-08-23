package com.github.kr328.clash

import java.io.File

/** Profile proxy HTTP upstream (spec D' 3.3). Pass chi song trong RAM + config.yaml thu muc rieng. */
data class ProxyProfile(
    val host: String,
    val port: Int,
    val user: String,
    val pass: String,
    val localPort: Int,
) {
    /** `A.B.***.***` — che 2 octet cuoi (spec 3.5). Host khong phai IPv4 thi che tu ky tu thu 3. */
    val maskedHost: String
        get() {
            val parts = host.split(".")
            return if (parts.size == 4) "${parts[0]}.${parts[1]}.***.***"
            else host.take(2) + "***"
        }

    /** `u***` — ky tu dau + ***. */
    val maskedUser: String
        get() = if (user.isEmpty()) "" else user.take(1) + "***"
}

object ProxyConfig {
    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Template spec D' 3.4. Lech co chu dich: `mode: rule` thay vi `global` vi mihomo bo qua
     * `rules` khi global -> dong chan UDP khong co tac dung; `rule` + `MATCH,up` cho cung ket qua
     * "moi TCP qua up, moi UDP REJECT". Khong `tun:` (CMFA tu dung VpnService), khong
     * external-controller (core tu strip).
     */
    fun render(p: ProxyProfile): String = buildString {
        appendLine("mode: rule")
        appendLine("ipv6: false")
        appendLine("log-level: warning")
        appendLine("dns:")
        appendLine("  enable: true")
        appendLine("  enhanced-mode: fake-ip")
        appendLine("  fake-ip-range: 198.18.0.1/16")
        appendLine("  nameserver:")
        appendLine("    - https://1.1.1.1/dns-query")
        appendLine("proxies:")
        appendLine("  - name: up")
        appendLine("    type: http")
        appendLine("    server: ${q(p.host)}")
        appendLine("    port: ${p.port}")
        appendLine("    username: ${q(p.user)}")
        appendLine("    password: ${q(p.pass)}")
        appendLine("rules:")
        appendLine("  - AND,((NETWORK,udp)),REJECT")
        appendLine("  - MATCH,up")
    }

    fun write(dir: File, p: ProxyProfile) {
        dir.mkdirs()
        val tmp = dir.resolve("config.yaml.tmp")
        tmp.writeText(render(p))
        if (!tmp.renameTo(dir.resolve("config.yaml"))) {
            dir.resolve("config.yaml").writeText(render(p))
            tmp.delete()
        }
    }

    /** DISCONNECT: xoa het dau vet pass tren dia (spec 3.3). */
    fun wipe(dir: File) {
        dir.deleteRecursively()
    }
}
