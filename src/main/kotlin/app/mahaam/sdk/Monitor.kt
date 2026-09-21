package app.mahaam.sdk

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A single daemon thread. Daemon so a pending report can never be the reason a JVM refuses to
 * exit, and one thread so reports keep their order and cannot swamp a struggling app.
 */
private fun defaultExecutor(): Executor =
    Executors.newSingleThreadExecutor { r -> Thread(r, "mahaam-monitor").apply { isDaemon = true } }

/** Maps to issue priority on the board: FATAL is highest, DEBUG is lowest. */
enum class Level {
    FATAL, ERROR, WARNING, INFO, DEBUG;

    internal val wire: String get() = name.lowercase()
}

/** Posts an envelope. Returns the HTTP status, or null if the request never completed. */
fun interface Transport {
    fun post(url: String, body: String): Int?
}

/**
 * Durable storage for envelopes that could not be sent.
 *
 * A phone is offline far more often than a server, and a report that only exists in memory is lost
 * by definition — in the uncaught-handler case the process is about to die. [FileEnvelopeStore]
 * is the one you want.
 */
interface EnvelopeStore {
    fun read(): List<String>
    fun write(envelopes: List<String>)
}

/**
 * One envelope per line, rather than a JSON array.
 *
 * A write cut short by the process dying costs one envelope instead of the whole queue — and that
 * is exactly the moment the earlier reports are worth most.
 *
 * On Android, give it a path under `context.filesDir`.
 */
class FileEnvelopeStore(private val file: File) : EnvelopeStore {
    constructor(path: String) : this(File(path))

    override fun read(): List<String> = try {
        if (!file.exists()) emptyList() else file.readLines().filter { it.isNotBlank() }
    } catch (_: Exception) {
        // An unreadable queue must not brick every future report.
        emptyList()
    }

    override fun write(envelopes: List<String>) {
        try {
            if (envelopes.isEmpty()) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            // An envelope is one JSON object with no literal newlines, so a line is a record.
            file.writeText(envelopes.joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {
            // Losing the queue is bad; taking the app down trying to save it is worse.
        }
    }
}

/** Keeps envelopes in memory. Honest about what it is: these die with the process. */
class MemoryEnvelopeStore : EnvelopeStore {
    val envelopes: MutableList<String> = mutableListOf()
    override fun read(): List<String> = envelopes.toList()
    override fun write(envelopes: List<String>) {
        this.envelopes.clear()
        this.envelopes.addAll(envelopes)
    }
}

/**
 * Reports errors to a Mahaam project board.
 *
 * Deliberately not a native crash reporter: it installs no signal handlers, so an NDK crash will
 * not appear here. Crashlytics owns that layer. This owns uncaught JVM throwables and the handled
 * failures your app noticed and recovered from — the second of which never reaches a crash
 * reporter at all.
 *
 * Every method is safe to call from any thread and none of them throw.
 */
class Monitor
@JvmOverloads
constructor(
    dsn: String? = null,
    /** `production`, `staging`, … */
    private val environment: String = "",
    /** Marketing version. The build number belongs in `tags["app_version"]`. */
    private val release: String = "",
    /** Device model, e.g. `Pixel 8`. Sent as `server_name`, per the wire contract. */
    private val device: String = "",
    enabled: Boolean = true,
    /** Attached to every event, and filterable on the board. */
    private val tags: Map<String, String> = emptyMap(),
    /** Queue cap. An app crashing in a loop offline must not fill the device. */
    private val maxQueued: Int = 200,
    private val store: EnvelopeStore? = null,
    private val transport: Transport = HttpUrlTransport(),
    /**
     * Where sending happens. The default is a single daemon thread, because Android throws
     * `NetworkOnMainThreadException` if you do network I/O on the main thread — and even where it
     * would be allowed, a report is never worth a frame drop. Pass `Runnable::run` in tests.
     */
    private val executor: Executor = defaultExecutor(),
) {
    private val parsed: ParsedDsn? = parseDsn(dsn)
    private val switchedOn = enabled
    private val flushing = AtomicBoolean(false)

    /** False when the DSN is missing or malformed, or monitoring was switched off. */
    val isEnabled: Boolean get() = switchedOn && parsed != null

    /** Never throws. Monitoring failing must not be why a screen fails to draw. */
    @JvmOverloads
    fun capture(
        level: Level = Level.ERROR,
        message: String = "",
        type: String = "",
        value: String = "",
        stacktrace: String = "",
        fingerprint: String? = null,
        tags: Map<String, String> = emptyMap(),
        extra: Map<String, Any?> = emptyMap(),
    ) {
        val dsn = parsed ?: return
        if (!isEnabled) return
        val fields = buildList {
            add("public_key" to Json.str(dsn.publicKey))
            add("level" to Json.str(level.wire))
            add("message" to Json.str(message))
            add("exception_type" to Json.str(type))
            add("exception_value" to Json.str(value))
            add("stacktrace" to Json.str(stacktrace))
            add("environment" to Json.str(environment))
            add("release" to Json.str(release))
            add("server_name" to Json.str(device))
            if (!fingerprint.isNullOrEmpty()) add("fingerprint" to Json.str(fingerprint))
            add("tags" to Json.strMap(this@Monitor.tags + tags))
            add("extra" to Json.anyMap(extra))
        }
        val envelope = Json.obj(fields)
        try {
            executor.execute { send(envelope, queueOnFailure = true) }
        } catch (_: RejectedExecutionException) {
            // Shutting down. Keep it rather than lose it, if we have somewhere to put it.
            enqueue(envelope)
        }
    }

    /**
     * Writes a report for a crash that is taking the process down with it, and returns once it is
     * on disk. Flushed on the next launch by [flush].
     *
     * Synchronous and network-free on purpose. An envelope that reaches the network at crash time
     * is luck, not design — the socket rarely completes before the process dies — and a terminal
     * handler has no time to wait for one to find out.
     *
     * Needs a [store]; without one there is nowhere for the report to survive.
     */
    fun captureCrash(error: Throwable, extra: Map<String, Any?> = emptyMap()) {
        val dsn = parsed ?: return
        if (!isEnabled || store == null) return
        val fields = listOf(
            "public_key" to Json.str(dsn.publicKey),
            "level" to Json.str(Level.FATAL.wire),
            "message" to Json.str(""),
            "exception_type" to Json.str(error.javaClass.name),
            "exception_value" to Json.str(error.message ?: error.javaClass.simpleName),
            "stacktrace" to Json.str(withStableTopFrame(formatStack(error))),
            "environment" to Json.str(environment),
            "release" to Json.str(release),
            "server_name" to Json.str(device),
            "tags" to Json.strMap(tags),
            "extra" to Json.anyMap(extra),
        )
        enqueue(Json.obj(fields))
    }

    /** Reports a thrown throwable with its stack trace. */
    @JvmOverloads
    fun captureException(
        error: Throwable,
        level: Level = Level.ERROR,
        extra: Map<String, Any?> = emptyMap(),
    ) = capture(
        level = level,
        type = error.javaClass.name,
        value = error.message ?: error.javaClass.simpleName,
        // Normalise the top frame so repeats of one fault group into one issue.
        stacktrace = withStableTopFrame(formatStack(error)),
        extra = extra,
    )

    /**
     * Sends anything an earlier run could not deliver, and returns how many are now dealt with.
     *
     * Safe to call on every launch. Call it off the main thread — it does network I/O, and app
     * start must never block on the network.
     */
    fun flush(): Int {
        val s = store
        if (!isEnabled || s == null) return 0
        if (!flushing.compareAndSet(false, true)) return 0
        try {
            val queued = s.read()
            if (queued.isEmpty()) return 0
            val kept = mutableListOf<String>()
            var sent = 0
            for (body in queued) {
                if (send(body, queueOnFailure = false)) sent++ else kept.add(body)
            }
            s.write(kept)
            return sent
        } finally {
            flushing.set(false)
        }
    }

    /** True when the envelope is done with — delivered, or rejected in a way retrying cannot fix. */
    private fun send(body: String, queueOnFailure: Boolean): Boolean {
        val url = parsed?.ingestUrl ?: return true
        val status = try {
            transport.post(url, body)
        } catch (_: Exception) {
            null // offline, DNS, TLS — all transient from here
        }
        // 403 means the key is unknown, revoked, or for another project. Queueing would pile up
        // envelopes that can never be delivered.
        if (status == 403) return true
        if (status != null && status in 200..299) return true
        if (queueOnFailure) enqueue(body)
        return false
    }

    private fun enqueue(body: String) {
        val s = store ?: return
        synchronized(s) {
            val queued = s.read() + body
            // Drop oldest-first.
            s.write(if (queued.size > maxQueued) queued.takeLast(maxQueued) else queued)
        }
    }
}

/**
 * The server fingerprints the first non-empty stack line, so anything varying between occurrences
 * of one fault — addresses, line numbers, thread ids — must come off it, or every crash files a
 * new issue instead of bumping the existing one.
 *
 * An obfuscated R8 frame like `a.b.c(SourceFile:1)` is stable across occurrences, so grouping
 * still works unsymbolicated. The issue title is just unhelpful.
 */
fun stableTopFrame(stack: String?): String {
    val line = stack.orEmpty().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
    return line
        .replace(Regex("0x[0-9a-fA-F]+"), "0x…")
        .replace(Regex(":\\d+\\)"), ")")
        .replace(Regex("\\b\\d{10,}\\b"), "")
        .trim()
}

/** Renders a throwable and its causes the way a JVM stack trace reads. */
internal fun formatStack(error: Throwable): String = buildString {
    var current: Throwable? = error
    var depth = 0
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) { // a cause cycle must not hang the reporter
        if (depth > 0) append("Caused by: ")
        append(current.javaClass.name)
        current.message?.let { append(": ").append(it) }
        append('\n')
        for (frame in current.stackTrace) append("\tat ").append(frame).append('\n')
        current = current.cause
        depth++
    }
}.trimEnd('\n')

private fun withStableTopFrame(stack: String): String {
    if (stack.isEmpty()) return ""
    val lines = stack.split("\n")
    return (listOf(stableTopFrame(stack)) + lines.drop(1)).joinToString("\n")
}

/** The default transport: the JDK's own client, so the library stays dependency-free. */
class HttpUrlTransport(private val timeoutMillis: Int = 10_000) : Transport {
    override fun post(url: String, body: String): Int? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
