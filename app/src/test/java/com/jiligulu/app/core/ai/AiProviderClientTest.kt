package com.jiligulu.app.core.ai

import android.app.Application
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AiProviderClientTest {
    @Test fun endpointResolutionPreservesPrefixesFullPathsAndQueriesWithoutDuplicateV1() {
        assertEquals("https://example.invalid/v1/chat/completions", resolve(" https://example.invalid "))
        assertEquals("https://example.invalid/v1/chat/completions", resolve("https://example.invalid/v1"))
        assertEquals("https://example.invalid/v1/chat/completions", resolve("https://example.invalid/v1/"))
        assertEquals("https://example.invalid/proxy/openai/chat/completions", resolve("https://example.invalid/proxy/openai"))
        assertEquals("https://example.invalid/v1/chat/completions?api-version=2026", resolve("https://example.invalid/v1/chat/completions?api-version=2026"))
        assertEquals("https://example.invalid/gateway/completion?api-version=2026", AiProviderEndpoint.resolve(
            "https://example.invalid/gateway/completion?api-version=2026", AiEndpointKind.CHAT_ENDPOINT))
        assertEquals("https://example.invalid/v1/chat/completions/", resolve("https://example.invalid/v1/chat/completions/"))
        for (address in listOf("", "file:///private/file", "ftp://example.invalid", "https://user:secret@example.invalid/v1", "https://example.invalid/v1#fragment")) {
            assertTrue(address, runCatching { resolve(address) }.isFailure)
        }
        assertTrue(runCatching { AiProviderEndpoint.validate(custom().copy(model = "model\nsecret")) }.isFailure)
    }

    @Test fun deepSeekPresetKeepsDefaultModelAndOnlyItsSupportedParameters() = runBlocking {
        val wire = FakeHttp()
        val client = DeepSeekClient("synthetic-deepseek-key", wire.client)
        assertTrue(client.parseBill("system", "synthetic input").isSuccess)
        val request = wire.requests.single()
        val json = request.body
        assertEquals("deepseek-flash", json["model"]!!.jsonPrimitive.content)
        assertEquals(AiConfig.MODEL, json["model"]!!.jsonPrimitive.content)
        assertEquals(AiConfig.BASE_URL, request.request.url.toString())
        assertEquals("Bearer synthetic-deepseek-key", request.request.header("Authorization"))
        assertEquals("disabled", json["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("json_object", json["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(.7, json["temperature"]!!.jsonPrimitive.double, .000001)
        assertFalse("max_tokens" in json)
    }

    @Test fun customProviderUsesItsOwnEndpointModelAndKeyWithoutDeepSeekSpecificFields() = runBlocking {
        val wire = FakeHttp()
        val profile = custom()
        val client = DeepSeekClient("synthetic-custom-key", profile, wire.client)
        assertTrue(client.parseBill("system", "synthetic input").isSuccess)
        val request = wire.requests.single()
        assertEquals("https://fixture.invalid/proxy/v1/chat/completions", request.request.url.toString())
        assertEquals("Bearer synthetic-custom-key", request.request.header("Authorization"))
        assertEquals("fixture-model", request.body["model"]!!.jsonPrimitive.content)
        for (field in listOf("thinking", "response_format", "temperature", "max_tokens", "max_completion_tokens"))
            assertFalse("Custom defaults must omit $field", field in request.body)
        assertFalse(profile.disablesDeepSeekThinking)
        assertFalse(profile.supportsLegacyTokenLimit)
        assertFalse(AiProviderConnection(profile, "synthetic-custom-key").toString().contains("synthetic-custom-key"))
    }

    @Test fun customWithoutAKeySendsNoAuthorizationAndExplicitCapabilitiesAreHonoured() = runBlocking {
        val wire = FakeHttp()
        val profile = custom().copy(jsonMode = true, sendsTemperature = true,
            address = "https://fixture.invalid/complete?version=1", endpointKind = AiEndpointKind.CHAT_ENDPOINT)
        assertTrue(DeepSeekClient("", profile, wire.client).parseBill("system", "input").isSuccess)
        val request = wire.requests.single()
        assertNull(request.request.header("Authorization"))
        assertEquals("https://fixture.invalid/complete?version=1", request.request.url.toString())
        assertEquals("json_object", request.body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue("temperature" in request.body)
        assertFalse("thinking" in request.body)
    }

    @Test fun fencedJsonAndArrayTextAreAcceptedWithOneObserverEventPerRequest() = runBlocking {
        val payloads = listOf(
            JsonPrimitive("```json\n{\"bills\":[],\"reply\":\"fixture\"}\n```"),
            buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "{\"bills\":[],") })
                add(buildJsonObject { put("type", "text"); put("text", "\"reply\":\"fixture\"}") })
            })
        for (content in payloads) {
            val wire = FakeHttp(body = response(content, buildJsonObject { put("prompt_tokens", 100); put("completion_tokens", 20) }))
            val reports = mutableListOf<AiTokenUsage?>()
            val client = DeepSeekClient("custom", custom(), wire.client) { reports += it }
            assertEquals("fixture", client.parseBill("system", "input").getOrThrow().reply)
            assertEquals(1, wire.requests.size)
            assertEquals(1, reports.size)
            assertEquals(100L, reports.single()!!.unclassifiedInput)
            assertFalse(reports.single()!!.cacheReported)
        }
    }

    @Test fun absentOrNullUsageDoesNotRejectValidContentOrInventTokens() = runBlocking {
        for (usage in listOf<JsonElement?>(null, JsonNull, JsonPrimitive("not-reported"))) {
            val wire = FakeHttp(body = response(JsonPrimitive("{\"bills\":[],\"reply\":\"ok\"}"), usage))
            val reports = mutableListOf<AiTokenUsage?>()
            val client = DeepSeekClient("custom", custom(), wire.client) { reports += it }
            assertEquals("ok", client.parseBill("system", "input").getOrThrow().reply)
            assertEquals(listOf<AiTokenUsage?>(null), reports)
            assertEquals(1, wire.requests.size)
        }
    }

    @Test fun unauthorizedReplyIsNotRetriedAndCannotEchoTheKeyIntoItsError() = runBlocking {
        val key = "synthetic-secret-key-401"
        val wire = FakeHttp(status = 401, body = "{\"error\":\"invalid $key\"}")
        val reports = mutableListOf<AiTokenUsage?>()
        val client = DeepSeekClient(key, custom(), wire.client) { reports += it }
        val error = client.parseBill("system", "input").exceptionOrNull() as DeepSeekHttpException
        assertEquals(401, error.status)
        assertFalse(error.detail.contains(key))
        assertFalse(error.message.orEmpty().contains(key))
        assertEquals(1, wire.requests.size)
        assertEquals(listOf<AiTokenUsage?>(null), reports)
    }

    @Test fun localUsageObserverFailureDoesNotCauseAnotherPaidRequest() = runBlocking {
        val wire = FakeHttp()
        var observations = 0
        val client = DeepSeekClient("custom", custom(), wire.client) { observations++; error("local persistence") }
        assertTrue(client.parseBill("system", "input").isSuccess)
        assertEquals(1, observations)
        assertEquals(1, wire.requests.size)
    }

    @Test fun imageCapabilityBlocksHttpAndSupportedImagesShareTheSelectedConnection() = runBlocking {
        val blocked = FakeHttp()
        var observations = 0
        assertTrue(DeepSeekClient("custom", custom(), blocked.client) { observations++ }
            .recognizeImage("system", "image context", "ZmFrZQ==").isFailure)
        assertTrue(blocked.requests.isEmpty())
        assertEquals(0, observations)
        val wire = FakeHttp()
        val reports = mutableListOf<AiTokenUsage?>()
        val profile = custom().copy(supportsImages = true)
        val client = DeepSeekClient("synthetic-image-key", profile, wire.client) { reports += it }
        assertTrue(client.recognizeImage("system", "image context", "ZmFrZQ==").isSuccess)
        val request = wire.requests.single()
        assertEquals(profile.endpoint, request.request.url.toString())
        assertEquals("Bearer synthetic-image-key", request.request.header("Authorization"))
        assertEquals(profile.model, request.body["model"]!!.jsonPrimitive.content)
        assertFalse("max_tokens" in request.body)
        val parts = request.body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals("image_url", parts.last().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,ZmFrZQ==", parts.last().jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals(1, reports.size)
    }

    private fun custom() = AiProviderProfile.custom().copy(name = "Fixture", address = "https://fixture.invalid/proxy/v1", model = "fixture-model")
    private fun resolve(address: String) = AiProviderEndpoint.resolve(address, AiEndpointKind.BASE_URL)
    private data class Captured(val request: Request, val body: JsonObject)
    private class FakeHttp(status: Int = 200, body: String = response(JsonPrimitive("{\"bills\":[],\"reply\":\"ok\"}"), null)) {
        val requests = Collections.synchronizedList(mutableListOf<Captured>())
        // Returning here means these tests never reach DNS, a socket or an external service.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            requests += Captured(request, Json.parseToJsonElement(buffer.readUtf8()).jsonObject)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }
    companion object {
        private fun response(content: JsonElement, usage: JsonElement?) = buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("content", content) }) }) })
            if (usage != null) put("usage", usage)
        }.toString()
    }
}
