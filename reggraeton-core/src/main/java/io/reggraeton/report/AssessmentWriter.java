package io.reggraeton.report;

import io.reggraeton.model.Inventory;
import io.reggraeton.naming.TestNaming;
import io.reggraeton.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes all outputs of {@code reggraeton assess} into one directory. */
public final class AssessmentWriter {

    public static final String INVENTORY = "inventory.json";
    public static final String REPORT_HTML = "report.html";
    public static final String REPORT_MD = "report.md";
    public static final String CALLGRAPH_DOT = "callgraph.dot";
    public static final String CALLGRAPH_JSON = "callgraph.json";
    public static final String TRACEABILITY = "traceability.csv";

    private AssessmentWriter() {
    }

    /** @return the files written */
    public static List<Path> write(Inventory inventory, Path outputDirectory, String basePackage) {
        ReportData data = new ReportData(inventory, new TestNaming(inventory, basePackage));
        CallGraphExport.Export graph = CallGraphExport.export(inventory, data.usedNodes());
        Path out = outputDirectory.toAbsolutePath().normalize();

        Json.write(out.resolve(INVENTORY), inventory);
        Json.write(out.resolve(CALLGRAPH_JSON), graph);
        text(out.resolve(CALLGRAPH_DOT), CallGraphExport.toDot(inventory, graph));
        text(out.resolve(TRACEABILITY), Traceability.csv(data));
        text(out.resolve(REPORT_MD), MarkdownReport.render(data));
        text(out.resolve(REPORT_HTML), HtmlReport.render(data));
        return List.of(out.resolve(REPORT_HTML), out.resolve(REPORT_MD), out.resolve(INVENTORY),
                out.resolve(TRACEABILITY), out.resolve(CALLGRAPH_DOT), out.resolve(CALLGRAPH_JSON));
    }

    private static void text(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
