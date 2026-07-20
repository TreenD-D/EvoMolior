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
            val result = runCatching {
                JavaNetSourceFetcher().fetch("http://127.0.0.1:${server.address.port}/start")
            }

            assertTrue(result.isFailure)
            assertTrue(!redirectedDestinationReached)
        } finally {
            server.stop(0)
        }
    }
}
