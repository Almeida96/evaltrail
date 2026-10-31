package io.github.almeida96.evaltrail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CliTest {
    @TempDir Path dir;
    private record ProcessResult(int code, String stdout, String stderr) {}
    private ProcessResult invoke(String... args) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        var command = new ArrayList<String>(); command.add(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString());
        command.addAll(java.util.List.of("-cp", System.getProperty("evaltrail.classpath"), Main.class.getName())); command.addAll(Arrays.asList(args));
        Path stdout = Files.createTempFile(dir, "stdout-", ".txt"), stderr = Files.createTempFile(dir, "stderr-", ".txt");
        Process process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try { assertTrue(process.waitFor(20, TimeUnit.SECONDS), "CLI did not exit"); return new ProcessResult(process.exitValue(), Files.readString(stdout), Files.readString(stderr)); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    @Test void versionAndInvalidArgumentsHaveRealExitCodes() throws Exception {
        var version = invoke("--version"); assertEquals(0, version.code()); assertEquals("0.1.0", version.stdout().strip());
        var invalid = invoke("run", "--private-argument", "PRIVATE_CLI_VALUE");
        assertEquals(1, invalid.code()); assertFalse(invalid.stderr().contains("PRIVATE_CLI_VALUE")); assertFalse(invalid.stderr().contains("Exception"));
    }
    @Test void standaloneDemoAndComparisonProduceReviewableArtifacts() throws Exception {
        Path out = dir.resolve("demo"); var demo = invoke("demo", "--out", out.toString());
        assertEquals(0, demo.code(), demo.stderr()); assertTrue(demo.stdout().contains("\"regressions\" : 2"));
        assertEquals(5, Reports.read(out.resolve("baseline/report.json")).count(Results.Status.PASS));
        assertEquals(2, Reports.read(out.resolve("candidate/report.json")).count(Results.Status.FAIL));
        var comparison = invoke("compare", "--baseline", out.resolve("baseline/report.json").toString(), "--candidate", out.resolve("candidate/report.json").toString(), "--out", dir.resolve("diff").toString());
        assertEquals(2, comparison.code(), comparison.stderr()); assertTrue(Files.isRegularFile(dir.resolve("diff/comparison.md")));
    }
    @Test void recordedMissingCasesReturnThreeAndWriteJUnitErrors() throws Exception {
        Path suite = dir.resolve("suite.json"), observations = dir.resolve("observations.json");
        Files.writeString(suite, "{\"schemaVersion\":1,\"id\":\"suite\",\"cases\":[{\"id\":\"case\",\"input\":null,\"checks\":[{\"type\":\"max_tokens\",\"value\":5}]}]}");
        Files.writeString(observations, "{\"schemaVersion\":1,\"observations\":[]}");
        var result = invoke("grade", "--suite", suite.toString(), "--observations", observations.toString(), "--out", dir.resolve("graded").toString());
        assertEquals(3, result.code(), result.stderr()); assertTrue(Files.readString(dir.resolve("graded/junit.xml")).contains("MISSING_OBSERVATION"));
    }
    @Test void credentialBearingInvalidUrisNeverAppearInDiagnostics() throws Exception {
        Path suite = dir.resolve("suite.json");
        Files.copy(Path.of("examples/support-suite.json"), suite);
        var result = invoke("run", "--suite", suite.toString(), "--endpoint", "http://user:PRIVATE_URI_SECRET@example.com/agent", "--out", dir.resolve("bad").toString());
        assertEquals(1, result.code()); assertFalse(result.stderr().contains("PRIVATE_URI_SECRET")); assertFalse(Files.exists(dir.resolve("bad")));
    }
}
