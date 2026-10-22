package com.achulkov.evomolior.agents.tools

import com.sun.net.httpserver.HttpServer
import com.achulkov.evomolior.sandbox.network.SourceAccessDecisionStore
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FetchSourceToolTest {
    @Test
    fun `allowed source is fetched sanitised and audited`() = runTest {
        val auditFile = createTempDirectory("fetch-source-tool-").toFile()
            .resolve(SourceAccessDecisionStore.FILE_NAME)
        val tool = FetchSourceTool(
            runId = "run-source-fetch",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 1,
            runObjective = "Improve Qwen DashScope integration.",
            currentAllowedHosts = setOf("help.aliyun.com"),
            auditFilePath = auditFile.absolutePath,
            fetcher = SourceFetcher {
                FetchedSource(
                    statusCode = 200,
                    contentType = "text/html",
                    body = "<html><script>bad()</script><body><h1>DashScope evidence</h1><p>Pricing docs mention token billing.</p></body></html>"
                )
            }
        )

        val result = tool.execute(
            FetchSourceTool.Args(
                url = "https://help.aliyun.com/document_detail/test",
                purpose = "Verify DashScope source details for the Qwen backend."
            )
        )

        assertTrue(result.startsWith("SOURCE_FETCHED"))
        assertTrue(result.contains("DashScope evidence"))
        assertTrue(!result.contains("bad()"))
        val records = SourceAccessDecisionStore(auditFile).readAll()
        assertEquals(1, records.size)
        assertTrue(records.single().allowed)
        assertEquals("help.aliyun.com", records.single().host)
    }

    @Test
    fun `subject relevant source must be approved before fetch`() = runTest {
        val auditFile = createTempDirectory("fetch-source-tool-needs-approval-").toFile()
            .resolve(SourceAccessDecisionStore.FILE_NAME)
        var fetched = false
        val tool = FetchSourceTool(
            runId = "run-source-fetch",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 1,
            runObjective = "Improve Qwen DashScope integration.",
            currentAllowedHosts = emptySet(),
            auditFilePath = auditFile.absolutePath,
            fetcher = SourceFetcher {
                fetched = true
                FetchedSource(statusCode = 200, contentType = "text/plain", body = "should not fetch")
            }
        )

        val result = tool.execute(
            FetchSourceTool.Args(
                url = "https://help.aliyun.com/document_detail/test",
                purpose = "Verify DashScope source details for the Qwen backend."
            )
        )

        assertTrue(result.startsWith("BLOCKED"))
        assertTrue(result.contains("request_source_access"))
        assertTrue(!fetched)
        assertTrue(!SourceAccessDecisionStore(auditFile).readAll().single().allowed)
    }

    @Test
    fun `blocked source is not fetched and is audited`() = runTest {
        val auditFile = createTempDirectory("fetch-source-tool-block-").toFile()
            .resolve(SourceAccessDecisionStore.FILE_NAME)
        var fetched = false
        val tool = FetchSourceTool(
            runId = "run-source-fetch",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 1,
            runObjective = "Improve Qwen DashScope integration.",
            currentAllowedHosts = emptySet(),
            auditFilePath = auditFile.absolutePath,
            fetcher = SourceFetcher {
                fetched = true
                FetchedSource(statusCode = 200, contentType = "text/plain", body = "should not fetch")
            }
        )

        val result = tool.execute(
            FetchSourceTool.Args(
                url = "file:///etc/passwd",
                purpose = "Try to inspect a local file as a source."
            )
        )

        assertTrue(result.startsWith("BLOCKED"))
        assertTrue(!fetched)
        val records = SourceAccessDecisionStore(auditFile).readAll()
        assertEquals(1, records.size)
        assertTrue(!records.single().allowed)
    }

    @Test
    fun `wrapped koog value arguments are accepted`() = runTest {
        val auditFile = createTempDirectory("fetch-source-tool-wrapped-").toFile()
            .resolve(SourceAccessDecisionStore.FILE_NAME)
        val tool = FetchSourceTool(
            runId = "run-source-fetch",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Outlier",
            generation = 1,
            runObjective = "Find fast micro-SaaS and app product hypotheses.",
            currentAllowedHosts = setOf("www.reddit.com"),
            auditFilePath = auditFile.absolutePath,
            fetcher = SourceFetcher {
                FetchedSource(
                    statusCode = 200,
                    contentType = "text/html",
                    body = "<main>Users complain about habit apps requiring too much setup.</main>"
                )
            }
        )

        val result = tool.execute(
            FetchSourceTool.Args(
                value = FetchSourceTool.ArgsValue(
                    url = "https://www.reddit.com/r/ADHD/comments/example",
                    purpose = "Inspect ADHD productivity app complaint evidence."
                )
            )
        )

        assertTrue(result.startsWith("SOURCE_FETCHED"))
        assertTrue(result.contains("habit apps"))
        assertTrue(SourceAccessDecisionStore(auditFile).readAll().single().allowed)
    }

    @Test
    fun `http fetcher does not follow redirects before destination authorization`() = runTest {
        var redirectedDestinationReached = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/start") { exchange ->
                exchange.responseHeaders.add(
                    "Location",
                    "http://127.0.0.1:${address.port}/destination"
                )
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            createContext("/destination") { exchange ->
                redirectedDestinationReached = true
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("ok".toByteArray()) }
            }
            start()
        }

        try {
            val result = JavaNetSourceFetcher().fetch("http://127.0.0.1:${server.address.port}/start")

            assertEquals(302, result.statusCode)
            assertEquals("http://127.0.0.1:${server.address.port}/destination", result.redirectLocation)
            assertTrue(!redirectedDestinationReached)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `http fetcher resolves relative redirect locations and bounds large bodies`() = runTest {
        val largeBody = ByteArray(MAX_SOURCE_BODY_BYTES + 1024) { 'a'.code.toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/docs/start") { exchange ->
                exchange.responseHeaders.add("Location", "../final?x=1")
                exchange.sendResponseHeaders(301, -1)
                exchange.close()
            }
            createContext("/large") { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                exchange.sendResponseHeaders(200, largeBody.size.toLong())
                exchange.responseBody.use { it.write(largeBody) }
            }
            start()
        }

        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val redirect = JavaNetSourceFetcher().fetch("$base/docs/start")
            assertEquals("$base/final?x=1", redirect.redirectLocation)

            val large = JavaNetSourceFetcher().fetch("$base/large")
            assertEquals(200, large.statusCode)
            assertEquals(MAX_SOURCE_BODY_BYTES, large.body.length)
            assertTrue(large.bodyTruncated)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `approved redirect destination is authorized audited and then fetched`() = runTest {
        val auditFile = createTempDirectory("fetch-source-tool-redirect-").toFile()
            .resolve(SourceAccessDecisionStore.FILE_NAME)
        val requested = mutableListOf<String>()
        val tool = redirectTool(
            auditFile = auditFile,
            allowedHosts = setOf("geoservices.ign.fr"),
            responses = mapOf(
                "https://geoservices.ign.fr/rgealti" to FetchedSource(
                    statusCode = 301,
                    contentType = null,
                    body = "",
                    redirectLocation = "https://geoservices.ign.fr/documentation/donnees/alti/rgealti"
                ),
                "https://geoservices.ign.fr/documentation/donnees/alti/rgealti" to FetchedSource(
                    statusCode = 200,
                    contentType = "text/html",
                    body = "<main>RGE ALTI 1 m and 5 m grids.</main>"
                )
            ),
            requested = requested
        )

        val result = tool.execute(
            FetchSourceTool.Args(
                url = "https://geoservices.ign.fr/rgealti",
                purpose = "Inspect official RGE ALTI terrain documentation."
            )
        )

        assertTrue(result.startsWith("SOURCE_FETCHED"), result)
        assertTrue(result.contains("Redirected from: https://geoservices.ign.fr/rgealti"))
        assertTrue(result.contains("RGE ALTI 1 m and 5 m grids."))
        assertEquals(2, requested.size)
        val records = SourceAccessDecisionStore(auditFile).readAll()
        assertEquals(2, records.size)
        assertTrue(records.all { it.allowed })
        assertTrue(records.last().purpose.startsWith("Redirect from https://geoservices.ign.fr/rgealti"))
    }

    @Test
    fun `unapproved redirect destination is never requested and is reported`() = runTest {
        val auditFile = createTempDirectory("fetch-source-tool-redirect-block-").toFile()
            .resolve(SourceAccessDecisionStore.FILE_NAME)
        val requested = mutableListOf<String>()
        val tool = redirectTool(
            auditFile = auditFile,
            allowedHosts = setOf("docs.example.org"),
            responses = mapOf(
                "https://docs.example.org/start" to FetchedSource(
                    statusCode = 302,
                    contentType = null,
                    body = "",
                    redirectLocation = "https://tracker.example.net/collect"
                )
            ),
            requested = requested
        )

        val result = tool.execute(
            FetchSourceTool.Args(
                url = "https://docs.example.org/start",
                purpose = "Inspect official product documentation."
            )
        )

        assertTrue(result.startsWith("REDIRECT_BLOCKED"), result)
        assertTrue(result.contains("https://tracker.example.net/collect"))
        assertEquals(listOf("https://docs.example.org/start"), requested)
        val records = SourceAccessDecisionStore(auditFile).readAll()
        assertEquals(listOf(true, false), records.map { it.allowed })
    }

    @Test
    fun `redirect loops and excessive hops stop without unbounded requests`() = runTest {
        val auditFile = createTempDirectory("fetch-source-tool-redirect-loop-").toFile()
            .resolve(SourceAccessDecisionStore.FILE_NAME)
        val loopRequests = mutableListOf<String>()
        val loopTool = redirectTool(
            auditFile = auditFile,
            allowedHosts = setOf("docs.example.org"),
            responses = mapOf(
                "https://docs.example.org/a" to FetchedSource(301, null, "", "https://docs.example.org/b"),
                "https://docs.example.org/b" to FetchedSource(301, null, "", "https://docs.example.org/a")
            ),
            requested = loopRequests
        )
        val loop = loopTool.execute(
            FetchSourceTool.Args(url = "https://docs.example.org/a", purpose = "Inspect looping documentation.")
        )
        assertTrue(loop.startsWith("FETCH_FAILED") && loop.contains("loop"), loop)
        assertEquals(2, loopRequests.size)

        val chainRequests = mutableListOf<String>()
        val chain = (0..MAX_REDIRECT_HOPS + 1).associate { index ->
            "https://docs.example.org/hop$index" to
                FetchedSource(302, null, "", "https://docs.example.org/hop${index + 1}")
        }
        val chainTool = redirectTool(
            auditFile = createTempDirectory("fetch-source-tool-redirect-chain-").toFile()
                .resolve(SourceAccessDecisionStore.FILE_NAME),
            allowedHosts = setOf("docs.example.org"),
            responses = chain,
            requested = chainRequests
        )
        val longChain = chainTool.execute(
            FetchSourceTool.Args(url = "https://docs.example.org/hop0", purpose = "Inspect long redirect chain.")
        )
        assertTrue(longChain.startsWith("FETCH_FAILED") && longChain.contains("redirects"), longChain)
        assertEquals(MAX_REDIRECT_HOPS + 1, chainRequests.size)
    }

    private fun redirectTool(
        auditFile: java.io.File,
        allowedHosts: Set<String>,
        responses: Map<String, FetchedSource>,
        requested: MutableList<String>
    ) = FetchSourceTool(
        runId = "run-source-redirect",
        groupId = "group-0",
        agentId = "agent-1",
        role = "Researcher",
        generation = 1,
        runObjective = "Compare official terrain and weather data sources.",
        currentAllowedHosts = allowedHosts,
        auditFilePath = auditFile.absolutePath,
        fetcher = SourceFetcher { url ->
            requested += url
            responses[url] ?: error("unexpected request to $url")
        }
    )
}
