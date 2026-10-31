package io.github.almeida96.evaltrail;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static io.github.almeida96.evaltrail.Results.*;

public final class Main {
    public static final String VERSION = "0.1.0";
    private Main() {}
    private static final String HELP = """
        EvalTrail 0.1.0 — contract checks for agent APIs

        run     --suite FILE --endpoint URL --out DIR [--label agent]
                [--timeout-ms 10000] [--parallel 2] [--token-env ENV_NAME]
        grade   --suite FILE --observations FILE --out DIR [--label recorded]
        compare --baseline FILE --candidate FILE --out DIR
        demo    --out DIR

        Output directories must not exist. run/grade emit JSON, Markdown, and JUnit XML.
        run/grade: exit 0 pass, 2 failed checks, 3 unavailable/invalid observations.
        compare: exit 0 no new regression, 2 regression, 3 changed/incomplete case set.
        Configuration and file errors return 1. demo runs synthetic local HTTP fixtures.
        """;

    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, java.nio.charset.StandardCharsets.UTF_8));
        int status;
        try { status = execute(args, System.getenv(), System.out); }
        catch (FileAlreadyExistsException exception) { System.err.println("Output directory exists. Choose a new --out directory."); status = 1; }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); System.err.println("Evaluation interrupted."); status = 1; }
        catch (Json.InputException exception) { System.err.println(exception.getMessage()); status = 1; }
        catch (IllegalArgumentException exception) { System.err.println("Invalid input or configuration. Check the documented schema and --help."); status = 1; }
        catch (IOException exception) { System.err.println("Could not read input, process the evaluation, or write reports."); status = 1; }
        catch (RuntimeException exception) { System.err.println("Command failed. No successful evaluation was recorded."); status = 1; }
        System.exit(status);
    }

    static int execute(String[] args, Map<String, String> environment, PrintStream out) throws IOException, InterruptedException {
        if (args.length == 0 || Arrays.equals(args, new String[]{"--help"})) { out.println(HELP); return 0; }
        if (Arrays.equals(args, new String[]{"--version"})) { out.println(VERSION); return 0; }
        Map<String, Set<String>> options = Map.of(
            "run", Set.of("suite", "endpoint", "out", "label", "timeout-ms", "parallel", "token-env"),
            "grade", Set.of("suite", "observations", "out", "label"),
            "compare", Set.of("baseline", "candidate", "out"), "demo", Set.of("out"));
        String command = args[0]; Json.require(options.containsKey(command) && args.length % 2 == 1, "Invalid command or option pairs");
        var values = new HashMap<String, String>();
        for (int index = 1; index < args.length; index += 2) {
            String flag = args[index];
            Json.require(flag.startsWith("--") && options.get(command).contains(flag.substring(2)), "Unknown option");
            Json.require(values.put(flag.substring(2), args[index + 1]) == null, "Duplicate option");
        }
        Path output = Path.of(required(values, "out")); Reports.requireNew(output);
        if (command.equals("demo")) return Demo.run(output, out);
        if (command.equals("compare")) {
            Comparison comparison = Comparison.compare(Reports.read(Path.of(required(values, "baseline"))), Reports.read(Path.of(required(values, "candidate"))));
            Reports.write(output, comparison); out.println(Json.MAPPER.writeValueAsString(comparison.counts())); return comparison.exitCode();
        }
        Suite suite = Suite.load(Path.of(required(values, "suite"))); Report report;
        if (command.equals("grade")) report = Runner.grade(suite, Path.of(required(values, "observations")), values.getOrDefault("label", "recorded"));
        else {
            int timeout = number(values, "timeout-ms", 10000), parallel = number(values, "parallel", 2);
            String token = null;
            if (values.containsKey("token-env")) {
                String key = required(values, "token-env"); Json.require(key.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"), "Invalid environment variable name");
                token = environment.get(key); Json.require(token != null && !token.isBlank(), "Token environment variable is missing");
            }
            try (var client = new AgentClient(URI.create(required(values, "endpoint")), timeout, token)) {
                report = Runner.run(suite, client, values.getOrDefault("label", "agent"), timeout, parallel);
            }
        }
        Reports.write(output, report);
        out.println(Json.MAPPER.writeValueAsString(Map.of("suite", report.suiteId(), "passed", report.count(Status.PASS), "failed", report.count(Status.FAIL), "errors", report.count(Status.ERROR))));
        return report.exitCode();
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key); Json.require(value != null && !value.isBlank(), "Required option is missing"); return value;
    }
    private static int number(Map<String, String> values, String key, int fallback) { return values.containsKey(key) ? Integer.parseInt(values.get(key)) : fallback; }
}
