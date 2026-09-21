# mahaam-sdk (Kotlin / Android)

Uncaught throwables in your Android or JVM app become issues on your [Mahaam](https://mahaam.app)
project board — with an owner, a priority and an occurrence count, rather than a line in logcat.

```kotlin
dependencies {
    implementation("app.mahaam:mahaam-sdk:0.1.0")
}
```

**No dependencies.** Not Retrofit, not OkHttp, not a JSON library — just the JDK. An error reporter
that drags in a transitive conflict is an error reporter that does not get installed.

It is a plain Kotlin/JVM library with no Android dependency, so it works in a Gradle module, a
server, or a CLI just as well as in an app.

## Quick start

```kotlin
class App : Application() {
    lateinit var monitor: Monitor

    override fun onCreate() {
        super.onCreate()

        monitor = Monitor(
            dsn = BuildConfig.MAHAAM_DSN,
            environment = if (BuildConfig.DEBUG) "development" else "production",
            release = BuildConfig.VERSION_NAME,
            device = Build.MODEL,
            store = FileEnvelopeStore(File(filesDir, "mahaam-queue.ndjson")),
            tags = mapOf(
                "os" to "android",
                "os_version" to Build.VERSION.RELEASE,
                "app_version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            ),
        )

        installUncaughtExceptionHandler(monitor)

        // Anything the last run could not deliver. Off the main thread: app start must never
        // block on the network.
        Thread { monitor.flush() }.start()
    }
}
```

Get the DSN from your project's **Settings → Monitoring**. It looks like
`https://mdsn_…@mcp.mahaam.app/monitor/<project id>`.

Reporting something you handled yourself:

```kotlin
try {
    syncInvoices()
} catch (e: IOException) {
    monitor.captureException(e, extra = mapOf("queued" to pending.size))
    showRetryBanner()
}
```

That second case is most of the value. A handled failure your app recovered from never reaches a
crash reporter at all, and it is exactly the kind of thing worth a ticket with an owner.

`capture` and `captureException` return immediately and send on a background daemon thread —
Android throws `NetworkOnMainThreadException` otherwise, and even where it would be allowed, a
report is never worth a dropped frame.

## What it does not do

**It does not report native crashes or ANRs, and it installs no signal handlers.** A `SIGSEGV` or
an NDK fault never reaches the JVM handler and will not appear on your board through this SDK.

That is deliberate. A signal handler may only call async-signal-safe functions — no allocation, no
JNI, no network — and getting it subtly wrong deadlocks the app instead of reporting. Only one
handler can be last, so a second reporter fighting Crashlytics for `SIGSEGV` loses reports. Use
Crashlytics for native crashes and ANRs; this owns the JVM layer above, and the two do not collide.

`installUncaughtExceptionHandler` **chains** to whatever handler was already installed rather than
replacing it, so Crashlytics still sees every throwable. It returns a function that restores the
previous handler.

## Crashes, and the next launch

When the handler fires, the process is going down. `captureCrash` serialises the envelope and
writes it to disk **synchronously**, with no network call at all — an envelope that reaches the
network at crash time is luck rather than design, since the socket rarely completes before the
process dies. `flush()` on the next launch sends it.

`FileEnvelopeStore` keeps one envelope per line rather than a JSON array, so a write cut short by
the process dying costs one envelope instead of the whole queue — which is exactly the moment the
earlier reports are worth most.

The queue is capped at `maxQueued` (default 200) and drops oldest-first, so an app crashing in a
loop offline cannot fill the device.

| response | what happens |
| --- | --- |
| 201 | delivered, dropped from the queue |
| 403 | key unknown, revoked, or for another project — dropped, since retrying cannot help |
| 429 | rate limited (60/min per IP) — kept for later |
| 5xx / network | kept for later |

## Grouping, and obfuscation

The server groups repeats of one fault by hashing the **first non-empty line of the stack**, and a
repeat within 14 days bumps an existing issue rather than filing a new one.

R8 and ProGuard obfuscate that frame, and this SDK does **not** upload mapping files, so an issue
title may read `a.b.c(SourceFile)`. Grouping still works — an obfuscated frame is stable across
occurrences — the title is just unhelpful. Retrace it with the mapping file for that build, or
disable obfuscation for the classes you care about.

What would break grouping is a frame carrying an address, a line number or a thread id, so
`captureException` strips those from the top frame before sending.

## Configuration

| option | meaning |
| --- | --- |
| `dsn` | from Settings → Monitoring. Missing or malformed **disables the client silently** — wiring monitoring in is never why an app fails to start |
| `environment` | `production`, `staging`, … |
| `release` | marketing version; put the version code in `tags["app_version"]` |
| `device` | `Build.MODEL`. Sent as `server_name` |
| `enabled` | set `false` to silence a valid DSN, e.g. in debug builds |
| `tags` | string → string, attached to every event and filterable on the board |
| `store` | `FileEnvelopeStore` for offline and crash delivery; without one, crash reports have nowhere to survive |
| `maxQueued` | queue cap (default 200) |
| `transport` | swap the HTTP client, mainly for tests |
| `executor` | where sending happens; pass `Runnable::run` to send on the calling thread |

Do not put a user identifier, an email or a location in `tags` or `extra` unless the person using
your app has opted in. These are error reports from someone's phone.

## Feedback is a different product

The Mahaam **Feedback** widget (`pfk_` key) reports what a person chose to report, with a
screenshot they approved. This reports what broke. A project holds one key of each kind and they
are not interchangeable.

## License

MIT
