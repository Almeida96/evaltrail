package io.github.almeida96.evaltrail;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import static io.github.almeida96.evaltrail.Results.*;

final class Demo {
    private Demo() {}
    static Suite suite() throws IOException {
        try (var stream = Demo.class.getResourceAsStream("/examples/support-suite.json")) {
            if (stream == null) throw new IOException("Bundled demo suite is missing");
            return Suite.parse(Json.parse(stream.readAllBytes()));
        }
    }

    static int run(Path output, PrintStream out) throws IOException, InterruptedException {
        Suite suite = suite(); Reports.requireNew(output);
        Files.createDirectories(output.toAbsolutePath().getParent()); Files.createDirectory(output);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405, -1); return; }
                byte[] input = exchange.getRequestBody().readNBytes(131_073);
                if (input.length > 131_072) { exchange.sendResponseHeaders(413, -1); return; }
                try {
                    var request = Json.parse(input);
                    String id = Json.id(request.get("caseId"));
                    byte[] bytes = Json.MAPPER.writeValueAsBytes(response(id, exchange.getRequestURI().getPath().equals("/candidate")));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (IllegalArgumentException exception) { exchange.sendResponseHeaders(400, -1); }
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Report baseline, candidate;
            try (var client = new AgentClient(URI.create(base + "/baseline"), 5000, null)) { baseline = Runner.run(suite, client, "baseline", 5000, 2); }
            try (var client = new AgentClient(URI.create(base + "/candidate"), 5000, null)) { candidate = Runner.run(suite, client, "candidate", 5000, 2); }
            Comparison comparison = Comparison.compare(baseline, candidate);
            Reports.write(output.resolve("baseline"), baseline); Reports.write(output.resolve("candidate"), candidate); Reports.write(output.resolve("comparison"), comparison);
            Json.require(baseline.count(Status.PASS) == 5 && candidate.count(Status.FAIL) == 2 && comparison.counts().get(Comparison.Kind.REGRESSION) == 2, "Demo produced unexpected results");
            out.println(Json.MAPPER.writeValueAsString(Map.of("fixture", "synthetic HTTP agent", "cases", 5, "baselinePassed", 5, "candidateFailed", 2, "regressions", 2)));
            return 0;
        } finally { server.stop(0); executor.shutdownNow(); }
    }

    static ObjectNode response(String caseId, boolean candidate) {
        ObjectNode root = Json.MAPPER.createObjectNode().put("schemaVersion", 1);
        var tools = root.putArray("toolCalls"); var citations = root.putArray("citations");
        root.putObject("usage").put("inputTokens", 120).put("outputTokens", 60);
        switch (caseId) {
            case "returns-answer" -> {
                root.put("output", "Unused items can be returned within 30 days.");
                tools.addObject().put("name", "knowledge.search").putObject("arguments").put("query", "returns");
                tools.addObject().put("name", "knowledge.read").putObject("arguments").put("sourceId", "returns-policy");
                citations.addObject().put("sourceId", candidate ? "unrelated-policy" : "returns-policy");
            }
            case "injection-resistance" -> {
                root.put("output", "Request refused.");
                if (candidate) tools.addObject().put("name", "support.export").putObject("arguments").put("destination", "example.invalid");
            }
            case "refund-preview" -> root.putObject("output").put("amount", 42).put("currency", "USD").put("submitted", false);
            case "retrieval-order" -> {
                root.put("output", "Shipping policy retrieved.");
                tools.addObject().put("name", "knowledge.search").putObject("arguments").put("query", "shipping");
                tools.addObject().put("name", "knowledge.read").putObject("arguments").put("sourceId", "shipping-policy");
                citations.addObject().put("sourceId", "shipping-policy");
            }
            case "short-summary" -> root.put("output", "Summary ready.");
            default -> throw new IllegalArgumentException("Unknown demo case");
        }
        return root;
    }
}
