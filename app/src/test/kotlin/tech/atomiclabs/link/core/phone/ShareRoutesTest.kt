package tech.atomiclabs.link.core.phone

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tech.atomiclabs.link.core.command.*
import tech.atomiclabs.link.core.fakes.*
import tech.atomiclabs.link.core.port.*
import tech.atomiclabs.link.core.protocol.*

@OptIn(ExperimentalCoroutinesApi::class)
class ShareRoutesTest {
    private class Fixture(scheduler: TestCoroutineScheduler) : MediaTransfer, OutboundFiles {
        val logger = FakeLogger()
        val clock = FakeClock(scheduler, 200_000_000)
        val tree = FakeUiTree().apply { connection = PortResult.Success(false) }
        val gate = FakeDisplayGate()
        val fetched = mutableListOf<String>()
        val writes = mutableListOf<Triple<List<Byte>, String?, String>>()
        val cutoffs = mutableListOf<Long>()
        val opened = mutableListOf<ShareRequest>()
        var fetchResult: PortResult<FetchedMedia> = PortResult.Success(FetchedMedia(byteArrayOf(1, 2), "image/png"))
        var pruneResult: PortResult<Unit> = PortResult.Success(Unit)
        var writeFailure: PortFailure? = null
        var outcome: ShareLaunchOutcome = ShareLaunchOutcome.Opened(null)
        var beforeFetch: suspend () -> Unit = {}
        var endpoint = "https://relay.invalid"
        var sequence = 0
        val routes = ShareRoutes(this, this, ShareLauncher { opened += it; outcome }, clock, logger) { endpoint }
        val router = CommandRouter(listOf(routes), tree, gate, logger)
        override suspend fun upload(httpBase: String, image: EncodedImage): PortResult<UploadedMedia> =
            error("Shares must not upload.")
        override suspend fun fetch(httpBase: String, token: String): PortResult<FetchedMedia> {
            assertEquals(endpoint, httpBase)
            fetched += token
            beforeFetch()
            return fetchResult
        }
        override suspend fun prune(olderThanEpochMs: Long): PortResult<Unit> {
            cutoffs += olderThanEpochMs
            return pruneResult
        }
        override suspend fun write(bytes: ByteArray, fileName: String?, contentType: String): PortResult<OutboundAttachment> {
            writes += Triple(bytes.toList(), fileName, contentType)
            return writeFailure?.let { PortResult.Failure(it) } ?: PortResult.Success(
                OutboundAttachment("content://tech.atomiclabs.link.files/outbound/${writes.size}", contentType, "prepared"))
        }
        suspend fun call(json: String = """{"media":"fixture-token"}""", path: String = "/share_media",
            id: String = "00000000-0000-0000-0000-${(++sequence).toString().padStart(12, '0')}"): BridgeResponse =
            router.dispatch(BridgeCommand(id, "POST", path, body = Json.parseToJsonElement(json).jsonObject))
    }

    private fun failure(response: BridgeResponse, code: ErrorCode) {
        assertEquals(200, response.status)
        assertEquals(JsonPrimitive(false), response.result["success"])
        assertEquals(JsonPrimitive(code.wireValue), response.result["code"])
        assertTrue(response.result["error"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test fun bothRoutesNeedOnlyDisplayAndReturnComposeMetadataFromActualOutcome() = runTest {
        val f = Fixture(testScheduler)
        f.outcome = ShareLaunchOutcome.Opened("actual.sms")
        val response = f.call("""{"to":"+15550000000","body":"private-body","package":"requested.sms",
            "attachments":[{"media":"MEDIA:hermes-relay://one","content_type":"image/jpeg","file_name":"photo.jpg"}]}""",
            "/send_mms")
        assertEquals(200, response.status)
        assertEquals(JsonPrimitive(true), response.result["success"])
        assertEquals(JsonPrimitive("compose_opened"), response.result["status"])
        assertEquals(JsonPrimitive("Send has not been tapped. Tap Send to deliver."), response.result["note"])
        assertEquals(JsonPrimitive("actual.sms"), response.result["package"])
        assertEquals(JsonPrimitive("+15550000000"), response.result["to"])
        assertEquals(JsonPrimitive(1), response.result["attachments"])
        assertEquals(JsonArray(listOf(JsonPrimitive("image/jpeg"))), response.result["content_types"])
        assertEquals(ShareRequest(listOf(OutboundAttachment("content://tech.atomiclabs.link.files/outbound/1",
            "image/jpeg", "prepared")), "private-body", null, "requested.sms", "+15550000000"), f.opened.single())
        assertEquals(listOf("one"), f.fetched)
        assertEquals(0, f.tree.connectionReads)
        assertEquals(1, f.gate.calls)
        assertTrue(f.routes.routes.all { it.method == "POST" && it.needs(BodyParams(JsonObject(emptyMap()))) == RouteNeeds(display = true) })
    }

    @Test fun chooserKeepsNullPackageUsesHeaderTypeAndPrunesOnEveryCall() = runTest {
        val f = Fixture(testScheduler)
        val first = f.call("""{"media_tokens":["one","two"],"text":"private-text","title":"Private title"}""")
        assertEquals(JsonNull, first.result["package"])
        assertEquals(JsonPrimitive(2), first.result["attachments"])
        assertFalse(first.result.containsKey("to"))
        assertEquals(listOf("image/png", "image/png"), f.writes.map { it.third })
        assertEquals("Private title", f.opened.single().title)
        f.call()
        assertEquals(listOf(113_600_000L, 113_600_000L), f.cutoffs)
    }

    @Test fun fetchFailureReturnsMediaFailureWithoutOpeningAndKeepsSafeCauseEvidence() = runTest {
        val f = Fixture(testScheduler)
        val cause = IllegalStateException("private-token-and-body")
        f.fetchResult = PortResult.Failure(PortFailure(FailureKind.REJECTED, cause, httpStatus = 403))
        val response = f.call()
        failure(response, ErrorCode.MEDIA_FETCH_FAILED)
        assertTrue(response.result["error"]!!.jsonPrimitive.content.contains("403"))
        assertTrue(f.writes.isEmpty())
        assertTrue(f.opened.isEmpty())
        val diagnostic = f.logger.entries.single().cause!!
        assertTrue(generateSequence(diagnostic) { it.cause }.any { it.stackTrace.contentEquals(cause.stackTrace) })
        assertFalse(diagnostic.stackTraceToString().contains("private-token-and-body"))
        assertFalse(response.result.toString().contains("private-token-and-body"))
    }

    @Test fun laterFetchFailureNeverOpensAPartiallyPreparedCompose() = runTest {
        val f = Fixture(testScheduler)
        f.beforeFetch = {
            if (f.fetched.size == 2) f.fetchResult =
                PortResult.Failure(PortFailure(FailureKind.IO, IllegalStateException("private")))
        }
        failure(f.call("""{"media_tokens":["one","two"]}"""), ErrorCode.MEDIA_FETCH_FAILED)
        assertEquals(1, f.writes.size)
        assertTrue(f.opened.isEmpty())
    }

    @Test fun missingContentTypeIsNotInventedAndExplicitTypeTakesPrecedence() = runTest {
        val f = Fixture(testScheduler)
        f.fetchResult = PortResult.Success(FetchedMedia(byteArrayOf(1), null))
        failure(f.call(), ErrorCode.MEDIA_FETCH_FAILED)
        assertTrue(f.writes.isEmpty())
        assertTrue(f.opened.isEmpty())
        assertEquals(JsonPrimitive(true), f.call("""{"media":"one","mime_type":"image/jpeg"}""").result["success"])
        assertEquals("image/jpeg", f.writes.single().third)
    }

    @Test fun pruneAndWriteFailuresPreventLaunchAndKeepOperationalCodes() = runTest {
        val f = Fixture(testScheduler)
        f.pruneResult = PortResult.Failure(PortFailure(FailureKind.IO, IllegalStateException("private-path")))
        failure(f.call(), ErrorCode.INTERNAL_ERROR)
        assertTrue(f.fetched.isEmpty())
        f.pruneResult = PortResult.Success(Unit)
        f.writeFailure = PortFailure(FailureKind.PERMISSION_DENIED, SecurityException("private-path"))
        failure(f.call(), ErrorCode.PERMISSION_DENIED)
        assertTrue(f.opened.isEmpty())
        assertEquals(2, f.logger.entries.count { it.level == "error" })
    }

    @Test fun noHandlerAndOperationalLaunchFailuresRemainDistinct() = runTest {
        val f = Fixture(testScheduler)
        f.outcome = ShareLaunchOutcome.NoHandler
        failure(f.call(), ErrorCode.NO_HANDLER)
        for ((kind, code) in listOf(FailureKind.PERMISSION_DENIED to ErrorCode.PERMISSION_DENIED,
            FailureKind.TIMED_OUT to ErrorCode.TIMEOUT, FailureKind.PLATFORM to ErrorCode.INTERNAL_ERROR)) {
            f.outcome = ShareLaunchOutcome.Failed(PortFailure(kind, IllegalStateException("private")))
            failure(f.call(), code)
        }
        assertEquals(3, f.logger.entries.size)
    }

    @Test fun invalidParametersAndMissingEndpointNeverFetchOrOpen() = runTest {
        val f = Fixture(testScheduler)
        for ((json, path) in listOf("{}" to "/share_media", """{"media":"one","package":""}""" to "/share_media",
            """{"media":"one","to":"","body":"x"}""" to "/send_mms", """{"media":"one","to":"123"}""" to "/send_mms")) {
            failure(f.call(json, path), ErrorCode.BAD_REQUEST)
        }
        f.endpoint = ""
        failure(f.call(), ErrorCode.MEDIA_FETCH_FAILED)
        assertTrue(f.fetched.isEmpty())
        assertTrue(f.opened.isEmpty())
    }

    @Test fun blockedDisplayPreventsAllShareWorkWithoutCheckingAccessibility() = runTest {
        val f = Fixture(testScheduler)
        f.gate.result = DisplayGateResult.Failed(CommandResult.Failure(ErrorCode.KEYGUARD_BLOCKED, "The display is locked."))
        failure(f.call(), ErrorCode.KEYGUARD_BLOCKED)
        assertTrue(f.cutoffs.isEmpty())
        assertTrue(f.fetched.isEmpty())
        assertEquals(0, f.tree.connectionReads)
    }

    @Test fun duplicateRequestDuringAndAfterFetchOpensExactlyOnce() = runTest {
        val f = Fixture(testScheduler)
        val release = CompletableDeferred<Unit>()
        f.beforeFetch = { release.await() }
        val first = async { f.call(id = "same-id") }
        runCurrent()
        val duplicate = async { f.call(id = "same-id") }
        runCurrent()
        release.complete(Unit)
        assertSame(first.await(), duplicate.await())
        assertSame(first.await(), f.call(id = "same-id"))
        assertEquals(1, f.fetched.size)
        assertEquals(1, f.writes.size)
        assertEquals(1, f.opened.size)
    }

    @Test fun fetchDeadlineReturnsMediaFailureAndReplaysWithoutNewWork() = runTest {
        val f = Fixture(testScheduler)
        f.beforeFetch = { awaitCancellation() }
        val response = async { f.call(id = "timeout-id") }
        advanceUntilIdle()
        failure(response.await(), ErrorCode.MEDIA_FETCH_FAILED)
        assertEquals(20_000, currentTime)
        assertTrue(f.opened.isEmpty())
        assertSame(response.await(), f.call(id = "timeout-id"))
        assertEquals(1, f.fetched.size)
    }

    @Test fun commandDeadlineStillCancelsCumulativeFetchesAt25Seconds() = runTest {
        val f = Fixture(testScheduler)
        f.beforeFetch = { delay(15_000) }
        val response = async { f.call("""{"media_tokens":["one","two"]}""", id = "overall-timeout") }
        advanceUntilIdle()
        failure(response.await(), ErrorCode.TIMEOUT)
        assertEquals(25_000, currentTime)
        assertEquals(2, f.fetched.size)
        assertTrue(f.opened.isEmpty())
        assertSame(response.await(), f.call(id = "overall-timeout"))
    }
}
