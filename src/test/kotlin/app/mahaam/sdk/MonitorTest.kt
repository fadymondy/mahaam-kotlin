package app.mahaam.sdk

import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DSN = "https://mdsn_abc@mcp.mahaam.app/monitor/proj-1"

/** Runs work on the calling thread, so a test can assert straight after capture. */
private val direct = Executor { it.run() }

/**
 * Records requests and replies with a scripted sequence of statuses. A null in the script stands
 * in for a request that never completed — being offline.
 */
private class Fake(vararg script: Int?) : Transport {
    private val script = script.toList()
    val urls = mutableListOf<String>()
    val bodies = mutableListOf<String>()
    private var i = 0

    override fun post(url: String, body: String): Int? {
        urls.add(url)
        bodies.add(body)
        return script.getOrElse(i++) { script.lastOrNull() }
    }

    /**
     * Reads one string field back out. Scanned by hand rather than matched with a regex: the
     * obvious `"((?:[^"\\]|\\.)*)"` pattern backtracks catastrophically on a long stacktrace and
     * overflows the stack, which is a slow way to learn that a test helper needs to be boring.
     */
    fun field(call: Int, name: String): String? {
        val body = bodies[call]
        val at = body.indexOf("\"$name\":\"")
        if (at < 0) return null
        var i = at + name.length + 4
        val sb = StringBuilder()
        while (i < body.length) {
            val c = body[i]
            when {
                c == '"' -> return sb.toString()
                c == '\\' && i + 1 < body.length -> {
                    sb.append(c).append(body[i + 1]); i += 2
                }
                else -> {
                    sb.append(c); i++
                }
            }
        }
        return null
    }

    val calls: Int get() = urls.size
}

class DsnTest {
    @Test
    fun `builds the ingest URL`() {
        val p = parseDsn(DSN)!!
        assertEquals("mdsn_abc", p.publicKey)
        assertEquals("https://mcp.mahaam.app/api/monitor/proj-1/envelope", p.ingestUrl)
    }

    @Test
    fun `accepts a DSN without the monitor prefix, per the contract`() {
        assertEquals("https://h.test/api/monitor/proj-1/envelope", parseDsn("https://k@h.test/proj-1")!!.ingestUrl)
    }

    @Test
    fun `keeps a non-default port`() {
        assertEquals(
            "http://localhost:8110/api/monitor/p/envelope",
            parseDsn("http://k@localhost:8110/monitor/p")!!.ingestUrl,
        )
    }

    @Test
    fun `returns null rather than throwing for anything unusable`() {
        for (bad in listOf(
            null,
            "",
            "   ",
            "https://mcp.mahaam.app/monitor/p", // no public key
            "https://k@h.test/", // no project
            "not a url",
        )) {
            assertNull(parseDsn(bad), "expected to be rejected: $bad")
        }
    }
}

class MonitorTest {
    @Test
    fun `is inert without a usable DSN, and capture is a safe no-op`() {
        val fake = Fake(201)
        val m = Monitor(dsn = "not a url", transport = fake, executor = direct)
        assertFalse(m.isEnabled)
        m.capture(message = "should not be sent")
        assertEquals(0, fake.calls)
    }

    @Test
    fun `enabled false silences a valid DSN`() {
        val fake = Fake(201)
        Monitor(dsn = DSN, enabled = false, transport = fake, executor = direct).capture(message = "x")
        assertEquals(0, fake.calls)
    }

    @Test
    fun `posts the envelope with the public key in the body`() {
        val fake = Fake(201)
        Monitor(
            dsn = DSN,
            transport = fake,
            environment = "production",
            release = "1.4.2",
            device = "Pixel 8",
            tags = mapOf("os" to "android"),
            executor = direct,
        ).capture(level = Level.FATAL, type = "IllegalStateException", value = "boom", tags = mapOf("network" to "wifi"))

        assertEquals(1, fake.calls)
        assertEquals("https://mcp.mahaam.app/api/monitor/proj-1/envelope", fake.urls[0])
        assertEquals("mdsn_abc", fake.field(0, "public_key"))
        assertEquals("fatal", fake.field(0, "level"))
        assertEquals("IllegalStateException", fake.field(0, "exception_type"))
        assertEquals("boom", fake.field(0, "exception_value"))
        assertEquals("1.4.2", fake.field(0, "release"))
        // The device model, per the mobile contract — not a hostname.
        assertEquals("Pixel 8", fake.field(0, "server_name"))
        // Per-event tags merge over the defaults.
        assertContains(fake.bodies[0], """"tags":{"os":"android","network":"wifi"}""")
        // Omitted so the server groups.
        assertFalse(fake.bodies[0].contains("fingerprint"))
    }

    @Test
    fun `level defaults to error and an explicit fingerprint is sent through`() {
        val fake = Fake(201, 201)
        val m = Monitor(dsn = DSN, transport = fake, executor = direct)
        m.capture(message = "hm")
        assertEquals("error", fake.field(0, "level"))
        m.capture(message = "x", fingerprint = "pinned")
        assertEquals("pinned", fake.field(1, "fingerprint"))
    }

    @Test
    fun `captureException carries the type, message and stack`() {
        val fake = Fake(201)
        Monitor(dsn = DSN, transport = fake, executor = direct)
            .captureException(IllegalStateException("x is not ready"))
        assertEquals("java.lang.IllegalStateException", fake.field(0, "exception_type"))
        assertEquals("x is not ready", fake.field(0, "exception_value"))
        assertTrue(fake.field(0, "stacktrace")!!.isNotEmpty())
    }

    @Test
    fun `a throwable with no message still reports something useful`() {
        val fake = Fake(201)
        Monitor(dsn = DSN, transport = fake, executor = direct).captureException(IllegalStateException())
        assertEquals("IllegalStateException", fake.field(0, "exception_value"))
    }

    @Test
    fun `capture does not throw when the transport does`() {
        val exploding = Transport { _, _ -> throw RuntimeException("transport is broken") }
        // Monitoring failing must never be why a screen fails to draw.
        Monitor(dsn = DSN, transport = exploding, executor = direct).capture(message = "x")
    }
}

class StackTest {
    @Test
    fun `stableTopFrame strips what varies between occurrences of one fault`() {
        // The server fingerprints the first stack line; anything varying there files a new issue
        // per crash instead of bumping the existing one.
        assertEquals("at app.Foo.bar(Foo.kt)", stableTopFrame("  at app.Foo.bar(Foo.kt:42)  "))
        assertEquals("0x… native", stableTopFrame("0x0000000102a3b4c5 native"))
        assertEquals("tid  frame", stableTopFrame("tid 1699999999999 frame"))
        assertEquals("", stableTopFrame(null))
        assertEquals("at real()", stableTopFrame("\n\n  at real()")) // the first *non-empty* line
    }

    @Test
    fun `an obfuscated frame still groups, it is just unhelpful`() {
        // R8 output is stable across occurrences, so grouping survives without a mapping pipeline.
        assertEquals("at a.b.c(SourceFile)", stableTopFrame("at a.b.c(SourceFile:1)"))
    }

    @Test
    fun `the same fault twice sends the same first stack line`() {
        val fake = Fake(201, 201)
        val m = Monitor(dsn = DSN, transport = fake, executor = direct)
        repeat(2) { m.captureException(IllegalStateException("boom")) }
        fun top(i: Int) = fake.field(i, "stacktrace")!!.substringBefore("\\n")
        assertEquals(top(0), top(1))
    }

    @Test
    fun `formatStack includes the cause chain`() {
        val root = IllegalArgumentException("the real reason")
        val wrapper = RuntimeException("something failed", root)
        val text = formatStack(wrapper)
        assertContains(text, "java.lang.RuntimeException: something failed")
        assertContains(text, "Caused by: java.lang.IllegalArgumentException: the real reason")
    }

    @Test
    fun `formatStack does not hang on a cause cycle`() {
        // Rare, but a reporter that spins forever on one is worse than no reporter.
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertContains(formatStack(b), "Caused by: java.lang.RuntimeException: a")
    }
}
