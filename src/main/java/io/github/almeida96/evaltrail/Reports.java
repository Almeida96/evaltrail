package io.github.almeida96.evaltrail;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static io.github.almeida96.evaltrail.Results.*;

final class Reports {
    private Reports() {}
    static Report read(Path path) throws IOException {
        Report report = Json.MAPPER.treeToValue(Json.read(path, 8 * 1024 * 1024), Report.class);
        Json.require(report != null, "Report is empty"); report.validate(); return report;
    }
    static void requireNew(Path output) throws IOException {
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException("Output directory exists");
    }
    static void write(Path output, Report report) throws IOException {
        report.validate();
        bundle(output, Map.of("report.json", Json.MAPPER.writeValueAsBytes(report), "junit.xml", junit(report).getBytes(UTF_8), "report.md", markdown(report).getBytes(UTF_8)));
    }
    static void write(Path output, Comparison comparison) throws IOException {
        var text = new StringBuilder("# Evaluation comparison\n\n");
        text.append("Suite: `").append(comparison.suiteId()).append("` · Baseline: `").append(comparison.baselineLabel())
            .append("` · Candidate: `").append(comparison.candidateLabel()).append("`\n\n");
        text.append("| Case | Change | Before | After |\n| --- | --- | --- | --- |\n");
        for (var change : comparison.changes()) text.append("| ").append(change.caseId()).append(" | ").append(change.kind()).append(" | ")
            .append(change.before() == null ? "—" : change.before()).append(" | ").append(change.after() == null ? "—" : change.after()).append(" |\n");
        text.append("\nChanged or missing cases are not recoveries. A clear comparison means no new regression in comparable cases; it does not mean every case passed.\n");
        bundle(output, Map.of("comparison.json", Json.MAPPER.writeValueAsBytes(comparison), "comparison.md", text.toString().getBytes(UTF_8)));
    }

    private static void bundle(Path output, Map<String, byte[]> files) throws IOException {
        Path destination = output.toAbsolutePath().normalize(); requireNew(destination);
        Files.createDirectories(destination.getParent());
        Path staging = Files.createTempDirectory(destination.getParent(), ".evaltrail-");
        try {
            for (var file : files.entrySet()) {
                Json.require(file.getKey().matches("[A-Za-z0-9.-]+") && file.getValue().length <= 8 * 1024 * 1024, "Report exceeds output limits");
                Files.write(staging.resolve(file.getKey()), file.getValue());
            }
            Files.move(staging, destination);
        } finally {
            for (String name : files.keySet()) Files.deleteIfExists(staging.resolve(name));
            Files.deleteIfExists(staging);
        }
    }

    private static String markdown(Report report) {
        var text = new StringBuilder("# Evaluation report\n\n");
        text.append("Suite: `").append(report.suiteId()).append("` · Target: `").append(report.label()).append("`\n\n")
            .append(report.count(Status.PASS)).append(" passed, ").append(report.count(Status.FAIL)).append(" failed, ")
            .append(report.count(Status.ERROR)).append(" errors.\n\n| Case | Status | Failed checks | Latency (ms) |\n| --- | --- | --- | --- |\n");
        for (CaseResult test : report.cases()) text.append("| ").append(test.id()).append(" | ").append(test.status()).append(" | ")
            .append(test.error() == null ? test.checks().stream().filter(check -> !check.passed()).count() : test.error().code())
            .append(" | ").append(test.latencyMs()).append(" |\n");
        return text.append("\nChecks inspect the adapter's reported observations. Inputs, answers, tool arguments, credentials, and endpoint URLs are not stored in this report.\n").toString();
    }

    private static String junit(Report report) throws IOException {
        try {
            var target = new StringWriter(); var xml = XMLOutputFactory.newFactory().createXMLStreamWriter(target);
            xml.writeStartDocument("UTF-8", "1.0"); xml.writeStartElement("testsuite");
            xml.writeAttribute("name", report.suiteId()); xml.writeAttribute("tests", Integer.toString(report.cases().size()));
            xml.writeAttribute("failures", Integer.toString(report.count(Status.FAIL))); xml.writeAttribute("errors", Integer.toString(report.count(Status.ERROR)));
            for (CaseResult test : report.cases()) {
                xml.writeStartElement("testcase"); xml.writeAttribute("classname", report.suiteId()); xml.writeAttribute("name", test.id());
                xml.writeAttribute("time", String.format(Locale.ROOT, "%.3f", test.latencyMs() / 1000.0));
                if (test.error() != null) {
                    xml.writeStartElement("error"); xml.writeAttribute("type", test.error().code().name());
                    xml.writeCharacters("Agent observation unavailable"); xml.writeEndElement();
                } else if (test.status() == Status.FAIL) {
                    xml.writeStartElement("failure"); xml.writeAttribute("type", "EvaluationFailure");
                    for (Check check : test.checks()) if (!check.passed()) xml.writeCharacters("Check " + check.index() + " (" + check.type() + "): " + check.code() + "\n");
                    xml.writeEndElement();
                }
                xml.writeEndElement();
            }
            xml.writeEndElement(); xml.writeEndDocument(); xml.close(); return target.toString();
        } catch (XMLStreamException exception) { throw new IOException("Could not serialize JUnit report", exception); }
    }
}
