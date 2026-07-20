package com.achulkov.evomolior.cli.remote

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

class RemoteDashboardServerTest {
    @Test
    fun loginSessionAllowsDashboardAndFakeRunLaunch() = testApplication {
        val dataDir = Files.createTempDirectory("evomolior-dashboard-http-test")
        val config = RemoteDashboardConfig(
            host = "127.0.0.1",
            port = 0,
            username = "operator",
            password = "secret",
            dataDir = dataDir,
            cliExecutable = "/bin/echo"
        )
        val dashboard = RemoteDashboardServer(config)
        application { dashboard.configure(this) }
        val client = createClient { followRedirects = false }

        val unauthenticated = client.get("/")
        assertEquals(HttpStatusCode.Found, unauthenticated.status)
        assertEquals("/login", unauthenticated.headers[HttpHeaders.Location])

        val login = client.post("/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("username=operator&password=secret")
        }
        assertEquals(HttpStatusCode.Found, login.status)
        val setCookie = assertNotNull(login.headers[HttpHeaders.SetCookie])
        assertContains(setCookie, "HttpOnly")
        assertContains(setCookie, "SameSite=Lax")
        val sessionCookie = setCookie.substringBefore(";")

        val dashboardPage = client.get("/") { header(HttpHeaders.Cookie, sessionCookie) }
        assertEquals(HttpStatusCode.OK, dashboardPage.status)

        val launch = client.post("/runs") {
            header(HttpHeaders.Cookie, sessionCookie)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("type=run&backend=qwen-cloud&prompt=hello&fast=on")
        }
        assertEquals(HttpStatusCode.Found, launch.status)
        assertNotNull(launch.headers[HttpHeaders.Location])

        val runsJson = withTimeout(2_000) {
            var current = ""
            while (!current.contains("SUCCEEDED")) {
                current = client.get("/api/runs") { header(HttpHeaders.Cookie, sessionCookie) }.bodyAsText()
                delay(50)
            }
            current
        }
        assertContains(runsJson, "\"status\": \"SUCCEEDED\"")
        assertContains(runsJson, "\"exitCode\": 0")
        assertContains(runsJson, "\"echo\"")
        assertFalse(runsJson.contains("\"/bin/echo\""))
    }

    @Test
    fun missingCliBinaryMarksRunFailed() = testApplication {
        val dataDir = Files.createTempDirectory("evomolior-dashboard-http-failure-test")
        val missingBinary = dataDir.resolve("missing-evomolior").toString()
        val config = RemoteDashboardConfig(
            host = "127.0.0.1",
            port = 0,
            username = "operator",
            password = "secret",
            dataDir = dataDir,
            cliExecutable = missingBinary
        )
        val dashboard = RemoteDashboardServer(config)
        application { dashboard.configure(this) }
        val client = createClient { followRedirects = false }
        val sessionCookie = login(client)

        val launch = client.post("/runs") {
            header(HttpHeaders.Cookie, sessionCookie)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("type=run&backend=qwen-cloud&prompt=hello&fast=on")
        }
        assertEquals(HttpStatusCode.Found, launch.status)

        val runsJson = withTimeout(2_000) {
            var current = ""
            while (!current.contains("FAILED")) {
                current = client.get("/api/runs") { header(HttpHeaders.Cookie, sessionCookie) }.bodyAsText()
                delay(50)
            }
            current
        }
        assertContains(runsJson, "\"status\": \"FAILED\"")
        assertContains(runsJson, "\"exitCode\": $SUPERVISOR_CHILD_START_FAILURE_EXIT")
        assertContains(runsJson, "could not start")
        assertContains(runsJson, "missing-evomolior")
        assertFalse(runsJson.contains(dataDir.toString()))
        assertContains(runsJson, "\"error\"")
    }

    @Test
    fun dashboardExposesRealtimeOverviewDetailAndArtifactPreviewContracts() = testApplication {
        val dataDir = Files.createTempDirectory("evomolior-dashboard-contract-test")
        val config = RemoteDashboardConfig(
            host = "127.0.0.1",
            port = 0,
            username = "operator",
            password = "secret",
            dataDir = dataDir,
            cliExecutable = "/bin/echo"
        )
        val dashboard = RemoteDashboardServer(config)
        application { dashboard.configure(this) }
        val client = createClient { followRedirects = false }
        val sessionCookie = login(client)

        val page = client.get("/") { header(HttpHeaders.Cookie, sessionCookie) }.bodyAsText()
        assertContains(page, "data-dashboard-overview")
        assertContains(page, "data-status-chart")
        assertContains(page, "data-live-state")
        assertContains(page, "Throughput")
        assertContains(page, "EventSource")
        assertContains(page, "aria-label=\"Filter runs\"")
        assertFalse(page.contains("https://"))

        val launch = client.post("/runs") {
            header(HttpHeaders.Cookie, sessionCookie)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("type=run&backend=qwen-cloud&prompt=hello&fast=on")
        }
        val detailPath = assertNotNull(launch.headers[HttpHeaders.Location])
        val runId = detailPath.substringAfterLast("/")
        val outputDir = dataDir.resolve("runs/$runId/output")
        Files.createDirectories(outputDir)
        Files.createDirectories(outputDir.resolve("outputs"))
        Files.writeString(outputDir.resolve("outputs/report.md"), "# Verified result")

        val overview = client.get("/api/overview") { header(HttpHeaders.Cookie, sessionCookie) }
        assertEquals(HttpStatusCode.OK, overview.status)
        assertContains(overview.bodyAsText(), "\"totalRuns\": 1")

        val detail = client.get("/api/runs/$runId") { header(HttpHeaders.Cookie, sessionCookie) }
        assertEquals(HttpStatusCode.OK, detail.status)
        assertContains(detail.bodyAsText(), "\"artifacts\"")
        assertContains(detail.bodyAsText(), "\"metrics\"")
        assertContains(detail.bodyAsText(), "\"events\"")

        val detailPage = client.get(detailPath) { header(HttpHeaders.Cookie, sessionCookie) }
        assertEquals(HttpStatusCode.OK, detailPage.status)
        assertContains(detailPage.bodyAsText(), "Cumulative provider cost")
        assertContains(detailPage.bodyAsText(), "data-artifact-mix")
        assertContains(detailPage.bodyAsText(), "Benchmark &amp; comparison")
        assertContains(detailPage.bodyAsText(), "@media(max-width:720px)")

        val preview = client.get("/api/runs/$runId/preview?path=outputs%2Freport.md") {
            header(HttpHeaders.Cookie, sessionCookie)
        }
        assertEquals(HttpStatusCode.OK, preview.status)
        assertContains(preview.bodyAsText(), "# Verified result")

        val stream = client.get("/api/events?once=true") { header(HttpHeaders.Cookie, sessionCookie) }
        assertEquals(HttpStatusCode.OK, stream.status)
        assertEquals(ContentType.Text.EventStream, stream.contentType()?.withoutParameters())
        assertContains(stream.bodyAsText(), "event: overview")
        assertContains(stream.bodyAsText(), "data: ")

        val detailStream = client.get("/api/runs/$runId/events?once=true") {
            header(HttpHeaders.Cookie, sessionCookie)
        }
        assertEquals(HttpStatusCode.OK, detailStream.status)
        assertEquals(ContentType.Text.EventStream, detailStream.contentType()?.withoutParameters())
        assertContains(detailStream.bodyAsText(), "event: run")

        val unauthenticatedStream = client.get("/api/events?once=true")
        assertEquals(HttpStatusCode.Unauthorized, unauthenticatedStream.status)
    }

    @Test
    fun malformedLaunchInputReturnsBadRequestInsteadOfSilentlyChangingIntent() = testApplication {
        val dataDir = Files.createTempDirectory("evomolior-dashboard-validation-test")
        val dashboard = RemoteDashboardServer(
            RemoteDashboardConfig(
                host = "127.0.0.1",
                port = 0,
                username = "operator",
                password = "secret",
                dataDir = dataDir,
                cliExecutable = "/bin/echo"
            )
        )
        application { dashboard.configure(this) }
        val client = createClient { followRedirects = false }
        val sessionCookie = login(client)

        val response = client.post("/runs") {
            header(HttpHeaders.Cookie, sessionCookie)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("type=benchmark&backend=unknown&benchmark=frames&limit=999&offset=-1")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertContains(response.bodyAsText(), "backend")
        assertTrue(Files.list(dataDir.resolve("runs")).use { it.count() } == 0L)
    }

    @Test
    fun artifactDownloadsAreAttachedAndSensitiveReportsAreNotDownloadable() = testApplication {
        val dataDir = Files.createTempDirectory("evomolior-dashboard-download-test")
        val dashboard = RemoteDashboardServer(
            RemoteDashboardConfig("127.0.0.1", 0, "operator", "secret", dataDir, "/bin/echo")
        )
        application { dashboard.configure(this) }
        val client = createClient { followRedirects = false }
        val cookie = login(client)
        val launch = client.post("/runs") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("type=run&backend=qwen-cloud&prompt=hello&fast=on")
        }
        val runId = assertNotNull(launch.headers[HttpHeaders.Location]).substringAfterLast("/")
        val output = dataDir.resolve("runs/$runId/output")
        Files.createDirectories(output)
        Files.createDirectories(output.resolve("outputs"))
        Files.writeString(output.resolve("outputs/article.html"), "<script>document.cookie</script>")
        Files.writeString(output.resolve("summary.json"), """{"prompt":"private"}""")
        Files.writeString(output.resolve("transcript.txt"), "private transcript")
        Files.writeString(output.resolve("notes.md"), "competitor-private notes")

        val article = client.get("/outputs/$runId/outputs/article.html") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, article.status)
        assertContains(article.headers[HttpHeaders.ContentDisposition].orEmpty(), "attachment")
        assertEquals("nosniff", article.headers["X-Content-Type-Options"])
        assertContains(article.headers["Content-Security-Policy"].orEmpty(), "sandbox")

        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/outputs/$runId/summary.json") { header(HttpHeaders.Cookie, cookie) }.status
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/outputs/$runId/transcript.txt") { header(HttpHeaders.Cookie, cookie) }.status
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/runs/$runId/preview?path=transcript.txt") {
                header(HttpHeaders.Cookie, cookie)
            }.status
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/runs/$runId/preview?path=notes.md") {
                header(HttpHeaders.Cookie, cookie)
            }.status
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/outputs/$runId/outputs/%2e%2e/%2e%2e/notes.md") {
                header(HttpHeaders.Cookie, cookie)
            }.status
        )
    }

    @Test
    fun concurrentRunAdmissionIsBounded() = testApplication {
        val dataDir = Files.createTempDirectory("evomolior-dashboard-capacity-test")
        val executable = dataDir.resolve("slow-cli.sh")
        Files.writeString(executable, "#!/bin/sh\nsleep 0.5\n")
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        val dashboard = RemoteDashboardServer(
            RemoteDashboardConfig(
                "127.0.0.1",
                0,
                "operator",
                "secret",
                dataDir,
                executable.toString(),
                maxConcurrentRuns = 1
            )
        )
        application { dashboard.configure(this) }
        val client = createClient { followRedirects = false }
        val cookie = login(client)
        val body = "type=run&backend=qwen-cloud&prompt=hello&fast=on"

        val first = client.post("/runs") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }
        val second = client.post("/runs") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }

        assertEquals(HttpStatusCode.Found, first.status)
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
    }

    @Test
    fun realtimeStreamAdmissionIsGloballyBounded() = testApplication {
        val dataDir = Files.createTempDirectory("evomolior-dashboard-sse-capacity-test")
        val dashboard = RemoteDashboardServer(
            RemoteDashboardConfig(
                host = "127.0.0.1",
                port = 0,
                username = "operator",
                password = "secret",
                dataDir = dataDir,
                cliExecutable = "/bin/echo",
                maxSseConnections = 1
            ),
            sseRefreshMillis = 25
        )
        application { dashboard.configure(this) }
        val client = createClient { followRedirects = false }
        val cookie = login(client)

        coroutineScope {
            val firstConnected = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val firstStream = launch {
                client.prepareGet("/api/events") {
                    header(HttpHeaders.Cookie, cookie)
                }.execute { response ->
                    val channel = response.bodyAsChannel()
                    withTimeout(2_000) {
                        while (channel.readUTF8Line() != "event: overview") {
                            // Hold one admitted stream while testing the global limit.
                        }
                    }
                    firstConnected.complete(Unit)
                    releaseFirst.await()
                }
            }

            firstConnected.await()
            val second = client.get("/api/events?once=true") {
                header(HttpHeaders.Cookie, cookie)
            }
            assertEquals(HttpStatusCode.TooManyRequests, second.status)
            releaseFirst.complete(Unit)
            firstStream.join()
        }
    }

    @Test
    fun activeSseStreamClosesAfterLogout() = testApplication {
        val dataDir = Files.createTempDirectory("evomolior-dashboard-sse-logout-test")
        val dashboard = RemoteDashboardServer(
            RemoteDashboardConfig("127.0.0.1", 0, "operator", "secret", dataDir, "/bin/echo"),
            sseRefreshMillis = 25
        )
        application { dashboard.configure(this) }
        val client = createClient { followRedirects = false }
        val cookie = login(client)

        coroutineScope {
            val firstEvent = CompletableDeferred<Unit>()
            val streamClosed = CompletableDeferred<Unit>()
            val streamJob = launch {
                client.prepareGet("/api/events") {
                    header(HttpHeaders.Cookie, cookie)
                }.execute { response ->
                    val channel = response.bodyAsChannel()
                    withTimeout(2_000) {
                        while (channel.readUTF8Line() != "event: overview") {
                            // Wait for the first authorized snapshot before revoking the session.
                        }
                    }
                    firstEvent.complete(Unit)
                    withTimeout(2_000) {
                        while (channel.readUTF8Line() != null) {
                            // The server closes the stream when the session disappears.
                        }
                    }
                    streamClosed.complete(Unit)
                }
            }

            firstEvent.await()
            val logout = client.post("/logout") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.Found, logout.status)
            withTimeout(2_000) { streamClosed.await() }
            streamJob.join()
        }
    }

    @Test
    fun activeSseStreamClosesWhenTheSessionExpires() = testApplication {
        val dataDir = Files.createTempDirectory("evomolior-dashboard-sse-expiry-test")
        var now = Instant.parse("2026-07-18T10:00:00Z")
        val dashboard = RemoteDashboardServer(
            RemoteDashboardConfig("127.0.0.1", 0, "operator", "secret", dataDir, "/bin/echo"),
            sessionTtlSeconds = 60,
            sseRefreshMillis = 25,
            now = { now }
        )
        application { dashboard.configure(this) }
        val client = createClient { followRedirects = false }
        val cookie = login(client)

        coroutineScope {
            val firstEvent = CompletableDeferred<Unit>()
            val streamClosed = CompletableDeferred<Unit>()
            val streamJob = launch {
                client.prepareGet("/api/events") {
                    header(HttpHeaders.Cookie, cookie)
                }.execute { response ->
                    val channel = response.bodyAsChannel()
                    withTimeout(2_000) {
                        while (channel.readUTF8Line() != "event: overview") {
                            // Wait for the first authorized snapshot before expiring the session.
                        }
                    }
                    firstEvent.complete(Unit)
                    withTimeout(2_000) {
                        while (channel.readUTF8Line() != null) {
                            // The server closes the stream when the TTL check fails.
                        }
                    }
                    streamClosed.complete(Unit)
                }
            }

            firstEvent.await()
            now = now.plusSeconds(61)
            withTimeout(2_000) { streamClosed.await() }
            streamJob.join()
        }
    }

    private suspend fun login(client: HttpClient): String {
        val login = client.post("/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("username=operator&password=secret")
        }
        assertEquals(HttpStatusCode.Found, login.status)
        return assertNotNull(login.headers[HttpHeaders.SetCookie]).substringBefore(";")
    }
}
