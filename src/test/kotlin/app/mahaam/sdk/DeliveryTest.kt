package app.mahaam.sdk

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val DSN = "https://mdsn_abc@mcp.mahaam.app/monitor/proj-1"
private val direct = Executor { it.run() }

private class Scripted(vararg script: Int?) : Transport {
    private val script = script.toList()
    val bodies = mutableListOf<String>()
    private var i = 0
    override fun post(url: String, body: String): Int? {
        bodies.add(body)
        return script.getOrElse(i++) { script.lastOrNull() }
    }
}

class DeliveryTest {

    @Test
    fun `a failed send is kept so it can go out on the next launch`() {
        val store = MemoryEnvelopeStore()
        Monitor(dsn = DSN, transport = Scripted(500), store = store, executor = direct)
            .capture(message = "kept")
        assertEquals(1, store.envelopes.size)
        assertContains(store.envelopes[0], """"message":"kept"""")
    }

    @Test
    fun `a request that never completed is kept too`() {
        val store = MemoryEnvelopeStore()
        Monitor(dsn = DSN, transport = Scripted(null), store = store, executor = direct).capture(message = "x")
        assertEquals(1, store.envelopes.size)
    }

    @Test
    fun `429 is kept - the report is fine, we were just too fast`() {
        val store = MemoryEnvelopeStore()
        Monitor(dsn = DSN, transport = Scripted(429), store = store, executor = direct).capture(message = "x")
        assertEquals(1, store.envelopes.size)
    }

    @Test
    fun `403 is dropped - retrying a revoked key can never succeed`() {
        val store = MemoryEnvelopeStore()
        Monitor(dsn = DSN, transport = Scripted(403), store = store, executor = direct).capture(message = "x")
        assertTrue(store.envelopes.isEmpty())
    }

    @Test
    fun `a success is not kept`() {
        val store = MemoryEnvelopeStore()
        Monitor(dsn = DSN, transport = Scripted(201), store = store, executor = direct).capture(message = "x")
        assertTrue(store.envelopes.isEmpty())
    }

    @Test
    fun `without a store a failed send is dropped rather than throwing`() {
        Monitor(dsn = DSN, transport = Scripted(500), executor = direct).capture(message = "x")
    }

    @Test
    fun `the queue is capped oldest-first so a crash loop cannot fill the device`() {
        val store = MemoryEnvelopeStore()
        val m = Monitor(dsn = DSN, transport = Scripted(500), store = store, maxQueued = 3, executor = direct)
        repeat(6) { m.capture(message = "e$it") }
        assertEquals(
            listOf("e3", "e4", "e5"),
            store.envelopes.map { Regex(""""message":"([^"]*)"""").find(it)!!.groupValues[1] },
        )
    }

    @Test
    fun `flush sends what an earlier run kept and empties the queue`() {
        val store = MemoryEnvelopeStore()
        store.write(listOf("""{"message":"a"}""", """{"message":"b"}"""))
        val t = Scripted(201, 201)
        val sent = Monitor(dsn = DSN, transport = t, store = store, executor = direct).flush()
        assertEquals(2, sent)
        assertEquals(2, t.bodies.size)
        assertTrue(store.envelopes.isEmpty())
    }

    @Test
    fun `flush keeps what still fails and drops what is permanently rejected`() {
        val store = MemoryEnvelopeStore()
        store.write(listOf("""{"message":"gone"}""", """{"message":"kept"}""", """{"message":"sent"}"""))
        val sent = Monitor(dsn = DSN, transport = Scripted(403, 500, 201), store = store, executor = direct).flush()
        assertEquals(2, sent) // the 403 counts as handled: it will never succeed
        assertEquals(listOf("""{"message":"kept"}"""), store.envelopes)
    }

    @Test
    fun `flush is a no-op with an empty queue, no store, or no DSN`() {
        val t = Scripted(201)
        assertEquals(0, Monitor(dsn = DSN, transport = t, store = MemoryEnvelopeStore(), executor = direct).flush())
        assertEquals(0, Monitor(dsn = DSN, transport = t, executor = direct).flush())
        assertEquals(0, Monitor(transport = t, store = MemoryEnvelopeStore(), executor = direct).flush())
        assertTrue(t.bodies.isEmpty())
    }

    @Test
    fun `concurrent flushes do not send the same envelope twice`() {
        val store = MemoryEnvelopeStore()
        store.write(listOf("""{"message":"a"}"""))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Hold the first flush inside the transport so the second one starts while it is running.
        val slow = Transport { _, _ ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            201
        }
        val m = Monitor(dsn = DSN, transport = slow, store = store, executor = direct)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Int> { m.flush() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = pool.submit<Int> { m.flush() }
            release.countDown()
            assertEquals(1, first.get(5, TimeUnit.SECONDS))
            assertEquals(0, second.get(5, TimeUnit.SECONDS)) // the second one bows out
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `capture sends off the calling thread by default`() {
        // On Android, network I/O on the main thread throws NetworkOnMainThreadException.
        val caller = Thread.currentThread()
        val seen = arrayOfNulls<Thread>(1)
        val done = CountDownLatch(1)
        val t = Transport { _, _ ->
            seen[0] = Thread.currentThread()
            done.countDown()
            201
        }
        Monitor(dsn = DSN, transport = t).capture(message = "x") // default executor
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertFalse(seen[0] === caller)
    }
}

class CrashPathTest {

    @Test
    fun `captureCrash writes to disk without touching the network`() {
        // The process is going down. An envelope that reaches the network at crash time is luck.
        val store = MemoryEnvelopeStore()
        val t = Scripted(201)
        Monitor(dsn = DSN, transport = t, store = store, executor = direct)
            .captureCrash(IllegalStateException("we are going down"))
        assertTrue(t.bodies.isEmpty())
        assertEquals(1, store.envelopes.size)
        assertContains(store.envelopes[0], """"level":"fatal"""")
        assertContains(store.envelopes[0], "we are going down")
    }

    @Test
    fun `captureCrash without a store has nowhere to put the report and says so by doing nothing`() {
        Monitor(dsn = DSN, transport = Scripted(201), executor = direct)
            .captureCrash(IllegalStateException("lost"))
    }

    @Test
    fun `installUncaughtExceptionHandler chains to the previous handler`() {
        val previousSeen = mutableListOf<Throwable>()
        val previous = Thread.UncaughtExceptionHandler { _, e -> previousSeen.add(e) }
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(previous)
        try {
            val store = MemoryEnvelopeStore()
            val monitor = Monitor(dsn = DSN, transport = Scripted(201), store = store, executor = direct)
            val restore = installUncaughtExceptionHandler(monitor)

            val boom = IllegalStateException("crash")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)

            // Crashlytics installs its own handler. Swallowing the throwable would silently cost
            // every crash report from the other tool.
            assertEquals(listOf<Throwable>(boom), previousSeen)
            assertEquals(1, store.envelopes.size)
            assertContains(store.envelopes[0], """"level":"fatal"""")
            assertContains(store.envelopes[0], """"thread":"""")

            restore()
            assertSame(previous, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun `the previous handler still runs even if reporting blows up`() {
        val previousSeen = mutableListOf<Throwable>()
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> previousSeen.add(e) }
        try {
            val exploding = object : EnvelopeStore {
                override fun read(): List<String> = throw RuntimeException("store is broken")
                override fun write(envelopes: List<String>) = throw RuntimeException("store is broken")
            }
            val monitor = Monitor(dsn = DSN, transport = Scripted(201), store = exploding, executor = direct)
            val restore = installUncaughtExceptionHandler(monitor)
            val boom = IllegalStateException("crash")
            // A reporter that throws on the way out turns a crash we could explain into one we
            // cannot.
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
            assertEquals(listOf<Throwable>(boom), previousSeen)
            restore()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}

class FileEnvelopeStoreTest {

    private fun tempDir(): File = File.createTempFile("mahaam", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    @Test
    fun `round-trips envelopes across instances, which is the whole point`() {
        val dir = tempDir()
        val path = File(dir, "queue.ndjson")
        FileEnvelopeStore(path).write(listOf("""{"message":"a"}""", """{"message":"b"}"""))
        // A new instance stands in for the next launch after the process died.
        assertEquals(listOf("""{"message":"a"}""", """{"message":"b"}"""), FileEnvelopeStore(path).read())
    }

    @Test
    fun `a missing file reads as empty rather than throwing`() {
        assertTrue(FileEnvelopeStore(File(tempDir(), "never-written.ndjson")).read().isEmpty())
    }

    @Test
    fun `writing an empty queue removes the file`() {
        val path = File(tempDir(), "queue.ndjson")
        val store = FileEnvelopeStore(path)
        store.write(listOf("""{"message":"a"}"""))
        store.write(emptyList())
        assertFalse(path.exists())
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun `a truncated tail costs one envelope, not the whole queue`() {
        // The usual reason a write is cut short is the process dying mid-crash — exactly when the
        // earlier reports are worth most. A JSON array would not parse at all.
        val path = File(tempDir(), "queue.ndjson")
        path.writeText("""{"message":"a"}""" + "\n" + """{"message":"b"}""" + "\n" + """{"message":"tr""")
        assertEquals(3, FileEnvelopeStore(path).read().size)
    }

    @Test
    fun `creates the directory it was pointed at`() {
        val nested = File(tempDir(), "a/b/queue.ndjson")
        FileEnvelopeStore(nested).write(listOf("""{"message":"a"}"""))
        assertEquals(listOf("""{"message":"a"}"""), FileEnvelopeStore(nested).read())
    }

    @Test
    fun `an unwritable path does not crash the app`() {
        // Losing the queue is bad; taking the app down trying to save it is worse.
        val store = FileEnvelopeStore(tempDir()) // a directory, not a file
        store.write(listOf("""{"message":"a"}"""))
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun `survives a full crash-then-relaunch cycle`() {
        val path = File(tempDir(), "queue.ndjson")
        // The crash: persisted synchronously, no network.
        Monitor(dsn = DSN, transport = Scripted(201), store = FileEnvelopeStore(path), executor = direct)
            .captureCrash(IllegalStateException("from the crash"))
        assertEquals(1, FileEnvelopeStore(path).read().size)

        // The next launch.
        val t = Scripted(201)
        val sent = Monitor(dsn = DSN, transport = t, store = FileEnvelopeStore(path), executor = direct).flush()
        assertEquals(1, sent)
        assertContains(t.bodies[0], "from the crash")
        assertTrue(FileEnvelopeStore(path).read().isEmpty())
    }
}
