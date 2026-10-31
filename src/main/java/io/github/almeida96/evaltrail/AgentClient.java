package io.github.almeida96.evaltrail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static io.github.almeida96.evaltrail.Results.*;

final class AgentClient implements AutoCloseable {
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final HttpClient client;
    private final URI endpoint;
    private final int timeoutMs;
    private final String token;

    AgentClient(URI endpoint, int timeoutMs, String token) {
        validateEndpoint(endpoint);
        Json.require(timeoutMs >= 100 && timeoutMs <= 120_000, "Timeout must be 100–120000 ms");
        Json.require(token == null || (!token.isBlank() && token.length() <= 8192 && token.chars().allMatch(c -> c >= 33 && c <= 126)), "Invalid bearer token");
        this.endpoint = endpoint; this.timeoutMs = timeoutMs; this.token = token;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static void validateEndpoint(URI endpoint) {
        String scheme = endpoint.getScheme(), host = endpoint.getHost();
        Json.require(scheme != null && host != null && endpoint.getUserInfo() == null && endpoint.getFragment() == null, "Invalid endpoint URI");
        boolean local = List.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host.toLowerCase(Locale.ROOT));
        Json.require(scheme.equalsIgnoreCase("https") || (scheme.equalsIgnoreCase("http") && local), "Use HTTPS or a loopback HTTP endpoint");
    }

    CaseResult evaluate(Suite.Case test) throws InterruptedException {
        long start = System.nanoTime();
        var requestBody = Json.MAPPER.createObjectNode().put("schemaVersion", 1).put("caseId", test.id());
        requestBody.set("input", test.input());
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), java.nio.charset.StandardCharsets.UTF_8));
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = client.sendAsync(request.build(), info -> new BoundedBody(MAX_RESPONSE_BYTES,
            info.headers().firstValueAsLong("Content-Length").orElse(-1)));
        try {
            HttpResponse<byte[]> result = response.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (result.statusCode() < 200 || result.statusCode() >= 300) return CaseResult.error(test, elapsed(start), ErrorCode.HTTP_STATUS, result.statusCode());
            String mime = result.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip();
            if (!mime.equalsIgnoreCase("application/json")) return CaseResult.error(test, elapsed(start), ErrorCode.INVALID_RESPONSE, null);
            try { return Grader.grade(test, Observation.parse(Json.parse(result.body())), elapsed(start)); }
            catch (IOException | IllegalArgumentException exception) { return CaseResult.error(test, elapsed(start), ErrorCode.INVALID_RESPONSE, null); }
        } catch (TimeoutException exception) {
            response.cancel(true); return CaseResult.error(test, elapsed(start), ErrorCode.TIMEOUT, null);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            ErrorCode code = cause instanceof HttpTimeoutException ? ErrorCode.TIMEOUT : cause instanceof BodyTooLarge ? ErrorCode.RESPONSE_TOO_LARGE : ErrorCode.TRANSPORT_ERROR;
            return CaseResult.error(test, elapsed(start), code, null);
        } catch (InterruptedException exception) {
            response.cancel(true); Thread.currentThread().interrupt(); throw exception;
        }
    }

    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    @Override public void close() { client.shutdownNow(); }

    private static final class BodyTooLarge extends IOException {
        private static final long serialVersionUID = 1L;
        BodyTooLarge() { super("Response body exceeds the size limit"); }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private final long announcedSize;
        private Flow.Subscription subscription;
        BoundedBody(int limit, long announcedSize) { this.limit = limit; this.announcedSize = announcedSize; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription next) {
            subscription = next;
            if (announcedSize > limit) fail(); else next.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) { subscription.cancel(); return; }
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > limit) { fail(); return; }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        private void fail() { subscription.cancel(); result.completeExceptionally(new BodyTooLarge()); }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
