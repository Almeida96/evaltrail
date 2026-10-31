package io.github.almeida96.evaltrail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static io.github.almeida96.evaltrail.Fixtures.*;
import static io.github.almeida96.evaltrail.Results.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentClientTest {
    private HttpServer server;
    private ExecutorService executor;
    private URI uri;
    @BeforeEach void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor); server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/agent");
    }
    @AfterEach void close() { server.stop(0); executor.shutdownNow(); }
    private static void reply(HttpExchange exchange, int status, byte[] data, String contentType) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, data.length); exchange.getResponseBody().write(data);
        }
    }
    private Suite testSuite() throws IOException { return suite("{\"type\":\"text_contains\",\"value\":\"world\"}"); }

    @Test void sendsTheContractAndBearerTokenWithoutRetainingSecrets() throws Exception {
        var header = new AtomicReference<String>(); var input = new AtomicReference<String>();
        server.createContext("/agent", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("Authorization"));
            input.set(Json.parse(exchange.getRequestBody().readAllBytes()).get("input").get("query").textValue());
            var response = response().put("output", "Hello world PRIVATE_RESPONSE_MARKER");
            reply(exchange, 200, Json.MAPPER.writeValueAsBytes(response), "application/json; charset=utf-8");
        });
        try (var client = new AgentClient(uri, 5000, "private-test-key")) {
            var result = client.evaluate(testSuite().cases().getFirst());
            assertEquals(Status.PASS, result.status()); assertEquals("Bearer private-test-key", header.get()); assertEquals("hello", input.get());
            String stored = Json.MAPPER.writeValueAsString(result);
            assertFalse(stored.contains("private-test-key")); assertFalse(stored.contains("PRIVATE_RESPONSE_MARKER"));
            assertEquals(150, result.metrics().totalTokens());
        }
    }

    @Test void doesNotRetryFailuresOrExposeTheServerErrorBody() throws Exception {
        var count = new AtomicInteger();
        server.createContext("/agent", exchange -> { count.incrementAndGet(); reply(exchange, 503, "PRIVATE_ERROR_BODY".getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain"); });
        try (var client = new AgentClient(uri, 5000, null)) {
            var result = client.evaluate(testSuite().cases().getFirst());
            assertEquals(ErrorCode.HTTP_STATUS, result.error().code()); assertEquals(503, result.error().httpStatus());
            assertEquals(1, count.get()); assertFalse(Json.MAPPER.writeValueAsString(result).contains("PRIVATE_ERROR_BODY"));
        }
    }

    @Test void redirectsDoNotForwardCredentialsOrProduceASecondRequest() throws Exception {
        var redirected = new AtomicInteger();
        server.createContext("/agent", exchange -> {
            exchange.getResponseHeaders().set("Location", uri.resolve("/other").toString());
            try (exchange) { exchange.sendResponseHeaders(302, -1); }
        });
        server.createContext("/other", exchange -> { redirected.incrementAndGet(); reply(exchange, 200, Json.MAPPER.writeValueAsBytes(response()), "application/json"); });
        try (var client = new AgentClient(uri, 5000, "token")) {
            assertEquals(302, client.evaluate(testSuite().cases().getFirst()).error().httpStatus()); assertEquals(0, redirected.get());
        }
    }

    @Test void rejectsMalformedSuccessfulResponses() throws Exception {
        server.createContext("/agent", exchange -> reply(exchange, 200, "{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json"));
        try (var client = new AgentClient(uri, 5000, null)) {
            assertEquals(ErrorCode.INVALID_RESPONSE, client.evaluate(testSuite().cases().getFirst()).error().code());
        }
    }

    @Test void rejectsJsonServedUnderTheWrongContentType() throws Exception {
        server.createContext("/agent", exchange -> reply(exchange, 200, Json.MAPPER.writeValueAsBytes(response()), "text/html"));
        try (var client = new AgentClient(uri, 5000, null)) {
            assertEquals(ErrorCode.INVALID_RESPONSE, client.evaluate(testSuite().cases().getFirst()).error().code());
        }
    }

    @Test void boundsAnnouncedResponseBodies() throws Exception {
        server.createContext("/agent", exchange -> {
            try (exchange) { exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, AgentClient.MAX_RESPONSE_BYTES + 1); }
        });
        try (var client = new AgentClient(uri, 5000, null)) {
            assertEquals(ErrorCode.RESPONSE_TOO_LARGE, client.evaluate(testSuite().cases().getFirst()).error().code());
        }
    }

    @Test void boundsChunkedResponsesEvenWithoutContentLength() throws Exception {
        server.createContext("/agent", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(new byte[AgentClient.MAX_RESPONSE_BYTES + 1]);
            } catch (IOException ignored) { /* Client cancellation can close the stream before the fixture finishes. */ }
        });
        try (var client = new AgentClient(uri, 5000, null)) {
            assertEquals(ErrorCode.RESPONSE_TOO_LARGE, client.evaluate(testSuite().cases().getFirst()).error().code());
        }
    }

    @Test void deadlineCoversAStalledBodyAfterHeadersHaveArrived() throws Exception {
        server.createContext("/agent", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
                try { Thread.sleep(5000); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            } catch (IOException ignored) { /* The timeout intentionally closes this connection. */ }
        });
        try (var client = new AgentClient(uri, 200, null)) {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertEquals(ErrorCode.TIMEOUT, client.evaluate(testSuite().cases().getFirst()).error().code()));
        }
    }

    @Test void parallelExecutionStaysWithinItsLimitAndReportsInSuiteOrder() throws Exception {
        var active = new AtomicInteger(); var maximum = new AtomicInteger();
        server.createContext("/agent", exchange -> {
            int current = active.incrementAndGet(); maximum.accumulateAndGet(current, Math::max);
            try {
                try { Thread.sleep(80); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                reply(exchange, 200, Json.MAPPER.writeValueAsBytes(response()), "application/json");
            } finally { active.decrementAndGet(); }
        });
        var root = Json.MAPPER.createObjectNode().put("schemaVersion", 1).put("id", "parallel"); var cases = root.putArray("cases");
        for (int index = 0; index < 8; index++) {
            var row = cases.addObject().put("id", "case-" + index); row.putObject("input");
            row.putArray("checks").addObject().put("type", "text_contains").put("value", "world");
        }
        Suite suite = Suite.parse(root);
        try (var client = new AgentClient(uri, 5000, null)) {
            var report = Runner.run(suite, client, "parallel", 5000, 2);
            assertEquals(8, report.count(Status.PASS)); assertTrue(maximum.get() <= 2); assertTrue(maximum.get() > 1);
            assertEquals(suite.cases().stream().map(Suite.Case::id).toList(), report.cases().stream().map(CaseResult::id).toList());
        }
    }

    @Test void remotePlaintextUserInfoAndUnsafeHeadersAreRejectedBeforeRequests() {
        assertThrows(IllegalArgumentException.class, () -> new AgentClient(URI.create("http://example.com/agent"), 1000, null));
        assertThrows(IllegalArgumentException.class, () -> new AgentClient(URI.create("https://user:secret@example.com/agent"), 1000, null));
        assertThrows(IllegalArgumentException.class, () -> new AgentClient(uri, 1000, "token\r\nX-Injected: yes"));
        assertThrows(IllegalArgumentException.class, () -> new AgentClient(uri, 0, null));
    }
}
