package io.reggraeton.compare;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.reggraeton.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Writes {@code compare.html}, {@code compare.md} and {@code compare.json}. */
public final class ComparisonWriter {

    private ComparisonWriter() {
    }

    public static void write(RunComparison c, Path out) {
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve("compare.html"), html(c), StandardCharsets.UTF_8);
            Files.writeString(out.resolve("compare.md"), markdown(c), StandardCharsets.UTF_8);
            Files.writeString(out.resolve("compare.json"),
                    Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json(c)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One line per outcome, for the console. */
    public static String summary(RunComparison c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.labelA()).append(": ").append(c.runA().results().size()).append(" tests, ")
                .append(c.labelB()).append(": ").append(c.runB().results().size()).append(" tests\n");
        c.counts().forEach((o, n) -> {
            if (n > 0) {
                sb.append(String.format("  %-18s %5d  %s%n", label(o), n, o.description()));
            }
        });
        return sb.toString();
    }

    static String label(RunComparison.Outcome o) {
        return o.name().toLowerCase().replace('_', ' ');
    }

    // ---------------------------------------------------------------- JSON

    static ObjectNode json(RunComparison c) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("labelA", c.labelA());
        root.put("labelB", c.labelB());
        root.put("locationA", c.runA().location().toString());
        root.put("locationB", c.runB().location().toString());
        ObjectNode counts = root.putObject("counts");
        c.counts().forEach((o, n) -> counts.put(o.name().toLowerCase(), n));
        ArrayNode rows = root.putArray("tests");
        for (RunComparison.Row r : c.rows()) {
            ObjectNode row = rows.addObject();
            row.put("outcome", r.outcome().name().toLowerCase());
            row.put("key", r.key());
            row.put("name", r.name());
            if (r.testCaseId() != null) {
                row.put("testCaseId", r.testCaseId());
            }
            side(row, "a", r.a());
            side(row, "b", r.b());
        }
        return root;
    }

    private static void side(ObjectNode row, String name, TestRun.Result r) {
        if (r == null) {
            return;
        }
        ObjectNode s = row.putObject(name);
        s.put("status", r.status().name().toLowerCase());
        s.put("seconds", r.seconds());
        if (r.message() != null) {
            s.put("message", r.message());
        }
    }

    // ---------------------------------------------------------------- Markdown

    static String markdown(RunComparison c) {
        StringBuilder md = new StringBuilder();
        md.append("# Run comparison: ").append(c.labelA()).append(" vs ").append(c.labelB()).append("\n\n");
        md.append("- **A** ").append(c.labelA()).append(": `").append(c.runA().location()).append("` (")
                .append(c.runA().results().size()).append(" tests)\n");
        md.append("- **B** ").append(c.labelB()).append(": `").append(c.runB().location()).append("` (")
                .append(c.runB().results().size()).append(" tests)\n\n");
        md.append("| Outcome | Tests | Meaning |\n|---|---:|---|\n");
        c.counts().forEach((o, n) -> md.append("| ").append(label(o)).append(" | ").append(n).append(" | ")
                .append(o.description()).append(" |\n"));
        md.append('\n');
        for (RunComparison.Outcome o : RunComparison.Outcome.values()) {
            List<RunComparison.Row> rows = c.rows().stream().filter(r -> r.outcome() == o).toList();
            if (rows.isEmpty() || o == RunComparison.Outcome.PASSED) {
                continue;
            }
            md.append("## ").append(capitalise(label(o))).append(" (").append(rows.size()).append(")\n\n");
            md.append("| Test | Scenario | ").append(cell(c.labelA())).append(" | ").append(cell(c.labelB())).append(" |\n");
            md.append("|---|---|---|---|\n");
            for (RunComparison.Row r : rows) {
                md.append("| ").append(cell(r.name())).append(" | ").append(r.testCaseId() == null ? "" : "`" + r.testCaseId() + "`")
                        .append(" | ").append(cell(describe(r.a()))).append(" | ").append(cell(describe(r.b()))).append(" |\n");
            }
            md.append('\n');
        }
        return md.toString();
    }

    private static String describe(TestRun.Result r) {
        if (r == null) {
            return "not run";
        }
        String status = r.status().name().toLowerCase();
        return r.message() == null || r.message().isBlank() ? status : status + ": " + r.message();
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replace("\r", "").replace("\n", "<br>");
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------------------------------------------------------- HTML

    private static final Map<RunComparison.Outcome, String> TONE = Map.of(
            RunComparison.Outcome.REGRESSION, "bad",
            RunComparison.Outcome.DIFFERENT_FAILURE, "bad",
            RunComparison.Outcome.FIXED, "good",
            RunComparison.Outcome.ONLY_IN_A, "warn",
            RunComparison.Outcome.ONLY_IN_B, "warn",
            RunComparison.Outcome.SKIPPED, "muted",
            RunComparison.Outcome.SAME_FAILURE, "muted",
            RunComparison.Outcome.PASSED, "good");

    static String html(RunComparison c) {
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Run comparison</title><style>").append(CSS).append("</style></head><body><main>");
        h.append("<h1>Run comparison: ").append(esc(c.labelA())).append(" vs ").append(esc(c.labelB())).append("</h1>");
        h.append("<p class=\"muted\"><b>A</b> ").append(esc(c.labelA())).append(": <code>").append(esc(c.runA().location().toString()))
                .append("</code> (").append(c.runA().results().size()).append(" tests)<br><b>B</b> ").append(esc(c.labelB()))
                .append(": <code>").append(esc(c.runB().location().toString())).append("</code> (")
                .append(c.runB().results().size()).append(" tests)</p>");
        h.append("<div class=\"cards\">");
        c.counts().forEach((o, n) -> h.append("<a class=\"card ").append(n == 0 ? "zero" : TONE.get(o)).append("\" href=\"#")
                .append(o.name().toLowerCase()).append("\"><span class=\"n\">").append(n).append("</span><span>")
                .append(esc(label(o))).append("</span><small>").append(esc(o.description())).append("</small></a>"));
        h.append("</div>");
        for (RunComparison.Outcome o : RunComparison.Outcome.values()) {
            List<RunComparison.Row> rows = c.rows().stream().filter(r -> r.outcome() == o).toList();
            if (rows.isEmpty()) {
                continue;
            }
            boolean open = o != RunComparison.Outcome.PASSED && o != RunComparison.Outcome.SAME_FAILURE && rows.size() <= 500;
            h.append("<details class=\"section\" id=\"").append(o.name().toLowerCase()).append("\"").append(open ? " open" : "")
                    .append("><summary><h2>").append(esc(capitalise(label(o)))).append(" <span class=\"badge ")
                    .append(TONE.get(o)).append("\">").append(rows.size()).append("</span></h2></summary>");
            h.append("<div class=\"scroll\"><table><thead><tr><th>Test</th><th>Scenario</th><th>")
                    .append(esc(c.labelA())).append("</th><th>").append(esc(c.labelB())).append("</th></tr></thead><tbody>");
            for (RunComparison.Row r : rows) {
                h.append("<tr><td>").append(esc(r.name())).append("</td><td><code>")
                        .append(r.testCaseId() == null ? "" : esc(r.testCaseId())).append("</code></td>")
                        .append(cellHtml(r.a())).append(cellHtml(r.b())).append("</tr>");
            }
            h.append("</tbody></table></div></details>");
        }
        h.append("<p class=\"muted\">Failure messages are compared with numbers, ids and timings masked, so ")
                .append("\"Timeout 30000ms\" and \"Timeout 5000ms\" count as the same reason.</p>");
        h.append("</main></body></html>\n");
        return h.toString();
    }

    private static String cellHtml(TestRun.Result r) {
        if (r == null) {
            return "<td class=\"muted\">not run</td>";
        }
        String status = r.status().name().toLowerCase();
        StringBuilder td = new StringBuilder("<td><span class=\"st ").append(status).append("\">").append(status)
                .append("</span> <span class=\"muted\">").append(String.format("%.1fs", r.seconds())).append("</span>");
        if (r.message() != null && !r.message().isBlank()) {
            td.append("<pre>").append(esc(r.message())).append("</pre>");
        }
        return td.append("</td>").toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String CSS = """
            :root{--bg:#fff;--fg:#1b1f24;--muted:#656d76;--line:#d0d7de;--card:#f6f8fa;
              --bad:#cf222e;--good:#1a7f37;--warn:#9a6700}
            @media (prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--muted:#8d96a0;--line:#30363d;
              --card:#161b22;--bad:#f85149;--good:#3fb950;--warn:#d29922}}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);
              font:14px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif}
            main{padding:16px 24px}h1{font-size:22px;margin:0 0 8px}h2{display:inline;font-size:17px}
            code,pre{font:12px ui-monospace,Consolas,monospace}pre{white-space:pre-wrap;margin:4px 0 0;max-width:70ch}
            .muted{color:var(--muted)}
            .cards{display:flex;flex-wrap:wrap;gap:8px;margin:16px 0}
            .card{display:flex;flex-direction:column;min-width:150px;padding:10px 12px;border:1px solid var(--line);
              border-radius:8px;background:var(--card);color:inherit;text-decoration:none}
            .card .n{font-size:24px;font-weight:600}.card small{color:var(--muted)}
            .card.bad .n{color:var(--bad)}.card.good .n{color:var(--good)}.card.warn .n{color:var(--warn)}
            .card.zero{opacity:.55}
            details.section{border-top:1px solid var(--line);padding:10px 0}summary{cursor:pointer}
            .badge{font-size:12px;padding:1px 8px;border-radius:10px;border:1px solid currentColor}
            .badge.bad{color:var(--bad)}.badge.good{color:var(--good)}.badge.warn{color:var(--warn)}.badge.muted{color:var(--muted)}
            .scroll{overflow-x:auto}table{border-collapse:collapse;margin-top:8px;width:100%}
            th,td{border-bottom:1px solid var(--line);padding:6px 8px;text-align:left;vertical-align:top}
            th{position:sticky;top:0;background:var(--bg)}
            .st{font-weight:600}.st.passed{color:var(--good)}.st.failed,.st.error{color:var(--bad)}.st.skipped{color:var(--muted)}
            """;
}
