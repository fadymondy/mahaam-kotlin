package app.mahaam.sdk

import java.net.URI
import java.net.URISyntaxException

/** A parsed DSN: where to post, and the key to post with. */
data class ParsedDsn(val ingestUrl: String, val publicKey: String)

/**
 * Parses `https://<public_key>@<host>/monitor/<project_id>` into an ingest URL.
 *
 * Returns null rather than throwing for anything it cannot parse. Wiring monitoring in must never
 * be the reason an app fails to start, and an unset DSN in development is the normal case.
 *
 * The `monitor/` path prefix is optional, so both forms resolve to the same project.
 */
fun parseDsn(dsn: String?): ParsedDsn? {
    val raw = dsn?.trim().orEmpty()
    if (raw.isEmpty()) return null

    val uri = try {
        URI(raw)
    } catch (_: URISyntaxException) {
        return null
    }

    val scheme = uri.scheme ?: return null
    val host = uri.host ?: return null
    val key = uri.userInfo?.substringBefore(':').orEmpty()
    if (key.isEmpty() || host.isEmpty()) return null

    var project = uri.path.orEmpty().trim('/')
    if (project.startsWith("monitor/")) project = project.removePrefix("monitor/")
    if (project.isEmpty()) return null

    val authority = if (uri.port > 0) "$host:${uri.port}" else host
    return ParsedDsn(ingestUrl = "$scheme://$authority/api/monitor/$project/envelope", publicKey = key)
}
