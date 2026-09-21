package app.mahaam.sdk

/**
 * Reports uncaught throwables, then hands back to whatever handler was already installed.
 *
 * Chaining rather than replacing is the whole point. Crashlytics installs its own handler, and a
 * reporter that swallows the throwable instead of passing it on silently costs you every crash
 * report from the other tool. This one writes its envelope to disk and returns.
 *
 * This is [Thread.setDefaultUncaughtExceptionHandler] — **not** a signal handler. A native crash,
 * an NDK fault or an ANR never reaches the JVM handler and will not appear on your board through
 * this SDK. That is deliberate: see the README.
 *
 * Returns a function that restores the previous handler.
 */
@JvmOverloads
fun installUncaughtExceptionHandler(
    monitor: Monitor,
    extra: Map<String, Any?> = emptyMap(),
): () -> Unit {
    val previous = Thread.getDefaultUncaughtExceptionHandler()

    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        try {
            // Synchronous and network-free: the process is going down, and an envelope that
            // reaches the network at crash time is luck rather than design.
            monitor.captureCrash(error, extra + mapOf("thread" to thread.name))
        } catch (_: Throwable) {
            // Whatever happens here, the previous handler must still run. A reporter that throws
            // on the way out would turn a crash we could explain into one we cannot.
        }
        previous?.uncaughtException(thread, error)
    }

    return { Thread.setDefaultUncaughtExceptionHandler(previous) }
}
