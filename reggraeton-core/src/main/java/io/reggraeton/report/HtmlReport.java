package io.reggraeton.report;

import io.reggraeton.model.Finding;
import io.reggraeton.model.ProjectLayout;
import io.reggraeton.model.StepDefinition;
import io.reggraeton.model.StepMatch;
import io.reggraeton.model.TestCaseAssessment;
import io.reggraeton.model.TestStep;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Self-contained HTML assessment report (no external resources, works offline and as a Jenkins artifact). */
public final class HtmlReport {

    private HtmlReport() {
    }

    public static String render(ReportData data) {
        ReportData.Totals t = data.totals();
        ProjectLayout layout = data.inventory().layout();
        Map<String, StepDefinition> definitions = data.inventory().stepDefinitions().stream()
                .collect(Collectors.toMap(StepDefinition::id, Function.identity(), (a, b) -> a));

        StringBuilder h = new StringBuilder(64 * 1024);
        h.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>reggraeton assessment</title><style>").append(CSS).append("</style></head><body><main>");

        h.append("<header><h1>reggraeton assessment</h1><p class=\"muted\">")
                .append(esc(layout.root())).append(" &middot; generated ").append(esc(data.inventory().generatedAt()))
                .append("</p></header>");

        // summary tiles
        h.append("<section class=\"tiles\">");
        tile(h, "Test cases", t.testCases(), "one Playwright test each");
        tile(h, "Ready", t.ready(), "fully translatable", "ok");
        tile(h, "Partial", t.partial(), "some TODO stubs", "warn");
        tile(h, "Blocked", t.blocked(), "undefined / ambiguous steps", "bad");
        tile(h, "Feature files", t.features(), plural(t.scenarios(), "scenario") + ", " + plural(t.outlines(), "outline"));
        tile(h, "Step definitions", t.stepDefinitions(), plural(t.hooks(), "hook"));
        tile(h, "Methods reached", t.reachableMethods(), "page objects &amp; helpers");
        tile(h, "Manual call sites", t.manualSites(), plural(t.errors(), "error") + ", " + plural(t.warnings(), "warning"), t.manualSites() > 0 ? "warn" : "ok");
        h.append("</section>");

        if (t.testCases() > 0) {
            h.append("<div class=\"bar\" role=\"img\" aria-label=\"readiness\">");
            bar(h, t.ready(), t.testCases(), "ok", "ready");
            bar(h, t.partial(), t.testCases(), "warn", "partial");
            bar(h, t.blocked(), t.testCases(), "bad", "blocked");
            h.append("</div><p class=\"muted small\"><b>ready</b>: every reachable Selenium call has a translation rule. ")
                    .append("<b>partial</b>: generated, but some calls become <code>TODO(reggraeton)</code> stubs that fail until finished. ")
                    .append("<b>blocked</b>: undefined or ambiguous steps; generated as a failing test stating the reason.</p>");
        }

        // test cases
        h.append("<section><h2>Test cases</h2><input class=\"filter\" placeholder=\"Filter test cases…\" data-target=\"tests\">");
        h.append("<div class=\"scroll\"><table id=\"tests\"><thead><tr><th>Status</th><th>Scenario</th><th>Tags</th>")
                .append("<th>Generated test</th><th class=\"num\">Manual</th></tr></thead><tbody>");
        for (ReportData.TestRow r : data.tests()) {
            TestCaseAssessment a = r.assessment();
            h.append("<tr><td><span class=\"pill ").append(pillClass(a.status())).append("\">").append(a.status()).append("</span></td><td>")
                    .append("<details><summary>").append(esc(r.testCase().name()))
                    .append(" <span class=\"muted\">").append(esc(r.testCase().id())).append("</span></summary>");
            h.append("<ol class=\"steps\">");
            for (TestStep step : r.testCase().steps()) {
                h.append("<li><b>").append(esc(step.keyword())).append("</b> ").append(esc(step.text()));
                StepMatch m = step.match();
                if (m != null && StepMatch.MATCHED.equals(m.status())) {
                    StepDefinition d = definitions.get(m.stepDefinitionIds().get(0));
                    h.append("<br><span class=\"muted small\">&rarr; ").append(esc(d == null ? m.stepDefinitionIds().get(0)
                            : d.className() + (d.methodName() != null ? "." + d.methodName() + "()" : " (lambda)")
                            + "  " + d.file() + ":" + d.line())).append("</span>");
                } else if (m != null) {
                    h.append(" <span class=\"pill bad\">").append(m.status()).append("</span>");
                }
                h.append("</li>");
            }
            h.append("</ol>");
            if (!a.issues().isEmpty()) {
                h.append("<ul class=\"issues\">");
                a.issues().forEach(i -> h.append("<li>").append(esc(i)).append("</li>"));
                h.append("</ul>");
            }
            h.append("<p class=\"small\">Run (native): <code>").append(esc(r.generated().nativeCommand()))
                    .append("</code><br>Run (bdd): <code>").append(esc(r.generated().bddCommand())).append("</code></p>");
            h.append("</details></td><td>").append(esc(String.join(" ", r.testCase().tags()))).append("</td><td><code>")
                    .append(esc(r.generated().nativeName())).append("</code></td><td class=\"num\">")
                    .append(a.manualItems()).append("</td></tr>");
        }
        h.append("</tbody></table></div></section>");

        // features
        h.append("<section><h2>Features</h2><div class=\"scroll\"><table><thead><tr><th>Feature</th><th>File</th>")
                .append("<th class=\"num\">Tests</th><th class=\"num\">Ready</th><th class=\"num\">Partial</th><th class=\"num\">Blocked</th></tr></thead><tbody>");
        for (ReportData.FeatureRow f : data.features()) {
            h.append("<tr><td>").append(f.error() != null ? "<span class=\"pill bad\">unparseable</span> " + esc(f.error()) : esc(f.name()))
                    .append("</td><td><code>").append(esc(f.path())).append("</code></td><td class=\"num\">").append(f.testCases())
                    .append("</td><td class=\"num\">").append(f.ready()).append("</td><td class=\"num\">").append(f.partial())
                    .append("</td><td class=\"num\">").append(f.blocked()).append("</td></tr>");
        }
        h.append("</tbody></table></div></section>");

        // findings
        h.append("<section><h2>Findings</h2>");
        if (data.findings().isEmpty()) {
            h.append("<p>None.</p>");
        } else {
            h.append("<input class=\"filter\" placeholder=\"Filter findings…\" data-target=\"findings\">")
                    .append("<div class=\"scroll\"><table id=\"findings\"><thead><tr><th>Severity</th><th>Code</th><th>Location</th><th>Message</th></tr></thead><tbody>");
            for (Finding f : data.findings()) {
                h.append("<tr><td><span class=\"pill ").append(severityClass(f.severity())).append("\">").append(f.severity())
                        .append("</span></td><td><code>").append(esc(f.code())).append("</code></td><td><code>")
                        .append(f.file() == null ? "" : esc(f.file() + (f.line() > 0 ? ":" + f.line() : "")))
                        .append("</code></td><td>").append(esc(f.message())).append("</td></tr>");
            }
            h.append("</tbody></table></div>");
        }
        h.append("</section>");

        // API usage
        h.append("<section><h2>API usage in reachable code</h2><div class=\"scroll\"><table><thead><tr><th>API</th><th>Category</th>")
                .append("<th>Conversion</th><th class=\"num\">Call sites</th><th>Note</th></tr></thead><tbody>");
        for (ReportData.ApiRow api : data.apis()) {
            h.append("<tr><td><code>").append(esc(api.api())).append("</code></td><td>").append(esc(api.category()))
                    .append("</td><td><span class=\"pill ").append("auto".equals(api.support()) ? "ok" : "warn").append("\">")
                    .append(api.support()).append("</span></td><td class=\"num\">").append(api.sites()).append("</td><td>")
                    .append(esc(api.note())).append("</td></tr>");
        }
        h.append("</tbody></table></div></section>");

        // step definitions
        h.append("<section><h2>Step definitions</h2><input class=\"filter\" placeholder=\"Filter step definitions…\" data-target=\"stepdefs\">")
                .append("<div class=\"scroll\"><table id=\"stepdefs\"><thead><tr><th>Pattern</th><th>Style</th><th class=\"num\">Uses</th><th>Location</th></tr></thead><tbody>");
        for (ReportData.StepDefinitionRow s : data.stepDefinitions()) {
            h.append("<tr><td><code>").append(esc(s.definition().expression())).append("</code></td><td>")
                    .append(esc(s.definition().style())).append("</td><td class=\"num\">")
                    .append(s.uses() == 0 ? "<span class=\"pill warn\">0</span>" : String.valueOf(s.uses()))
                    .append("</td><td><code>").append(esc(s.definition().file() + ":" + s.definition().line())).append("</code></td></tr>");
        }
        h.append("</tbody></table></div></section>");

        // layout
        h.append("<section><h2>Project layout</h2><dl>");
        dl(h, "Build tool", layout.buildTool());
        dl(h, "Java source roots", String.join(", ", layout.javaSourceRoots()));
        dl(h, "Feature roots", String.join(", ", layout.featureRoots()));
        dl(h, "Glue packages", layout.gluePackages().isEmpty() ? "(not declared: all packages)" : String.join(", ", layout.gluePackages()));
        dl(h, "Runners", String.join(", ", layout.runners()));
        dl(h, "Dependency injection", layout.dependencyInjection());
        h.append("</dl><p class=\"muted small\">Also written: <code>inventory.json</code> (complete model), ")
                .append("<code>callgraph.dot</code> / <code>callgraph.json</code>, <code>traceability.csv</code>.</p></section>");

        h.append("</main><script>").append(JS).append("</script></body></html>\n");
        return h.toString();
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    private static void tile(StringBuilder h, String label, int value, String sub) {
        tile(h, label, value, sub, "");
    }

    private static void tile(StringBuilder h, String label, int value, String sub, String cls) {
        h.append("<div class=\"tile ").append(cls).append("\"><div class=\"label\">").append(label)
                .append("</div><div class=\"value\">").append(value).append("</div><div class=\"muted small\">")
                .append(sub).append("</div></div>");
    }

    private static void bar(StringBuilder h, int n, int total, String cls, String label) {
        if (n == 0) {
            return;
        }
        double pct = 100.0 * n / total;
        h.append("<span class=\"").append(cls).append("\" style=\"width:").append(String.format(java.util.Locale.ROOT, "%.2f", pct))
                .append("%\" title=\"").append(n).append(' ').append(label).append("\">").append(n).append(' ').append(label).append("</span>");
    }

    private static void dl(StringBuilder h, String key, String value) {
        h.append("<dt>").append(esc(key)).append("</dt><dd>").append(esc(value)).append("</dd>");
    }

    private static String pillClass(String status) {
        return switch (status) {
            case TestCaseAssessment.READY -> "ok";
            case TestCaseAssessment.PARTIAL -> "warn";
            default -> "bad";
        };
    }

    private static String severityClass(String severity) {
        return switch (severity) {
            case "error" -> "bad";
            case "warning" -> "warn";
            default -> "info";
        };
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static final String CSS = """
            :root{--bg:#fbfbfa;--fg:#1d1d1f;--muted:#6b6b70;--line:#e3e3e0;--card:#fff;--ok:#1f7a4d;--okbg:#e3f4ea;\
            --warn:#8a5a00;--warnbg:#fcf0d6;--bad:#a3262a;--badbg:#fbe4e4;--info:#33557a;--infobg:#e4edf7;--code:#f1f1ee}
            @media (prefers-color-scheme: dark){:root{--bg:#161618;--fg:#ececee;--muted:#9a9aa1;--line:#2e2e33;--card:#1e1e21;\
            --ok:#6fd39f;--okbg:#173527;--warn:#f2c46b;--warnbg:#3a2f14;--bad:#f39a9c;--badbg:#3e1d1f;--info:#9cc0ea;--infobg:#1d2a3b;--code:#2a2a2e}}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
            main{max-width:1180px;margin:0 auto;padding:24px 16px 64px}h1{font-size:26px;margin:0 0 4px}h2{font-size:19px;margin:36px 0 12px}
            .muted{color:var(--muted)}.small{font-size:13px}code{font:12.5px ui-monospace,SFMono-Regular,Consolas,monospace;background:var(--code);padding:1px 5px;border-radius:4px;word-break:break-word}
            .tiles{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:12px;margin-top:20px}
            .tile{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 14px}
            .tile .label{font-size:13px;color:var(--muted)}.tile .value{font-size:28px;font-weight:600;font-variant-numeric:tabular-nums}
            .tile.ok .value{color:var(--ok)}.tile.warn .value{color:var(--warn)}.tile.bad .value{color:var(--bad)}
            .bar{display:flex;height:26px;border-radius:6px;overflow:hidden;margin:18px 0 6px;border:1px solid var(--line);font-size:12px}
            .bar span{display:flex;align-items:center;justify-content:center;white-space:nowrap;overflow:hidden}
            .bar .ok{background:var(--okbg);color:var(--ok)}.bar .warn{background:var(--warnbg);color:var(--warn)}.bar .bad{background:var(--badbg);color:var(--bad)}
            .scroll{overflow-x:auto;border:1px solid var(--line);border-radius:10px;background:var(--card)}
            table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:8px 10px;border-bottom:1px solid var(--line);vertical-align:top}
            th{font-size:13px;color:var(--muted);font-weight:600}tr:last-child td{border-bottom:0}.num{text-align:right;font-variant-numeric:tabular-nums}
            .pill{display:inline-block;padding:1px 8px;border-radius:999px;font-size:12px;font-weight:600}
            .pill.ok{background:var(--okbg);color:var(--ok)}.pill.warn{background:var(--warnbg);color:var(--warn)}
            .pill.bad{background:var(--badbg);color:var(--bad)}.pill.info{background:var(--infobg);color:var(--info)}
            details summary{cursor:pointer}ol.steps{margin:8px 0;padding-left:20px}ul.issues{color:var(--warn);margin:6px 0;padding-left:20px}
            .filter{width:100%;max-width:360px;padding:7px 10px;margin:0 0 10px;border:1px solid var(--line);border-radius:8px;background:var(--card);color:var(--fg);font:inherit}
            dl{display:grid;grid-template-columns:minmax(0,max-content) minmax(0,1fr);gap:6px 16px}dt{color:var(--muted)}dd{margin:0;overflow-wrap:anywhere}
            @media (max-width:600px){dl{grid-template-columns:1fr}dd{margin-bottom:8px}}
            header p{overflow-wrap:anywhere}
            """;

    private static final String JS = """
            document.querySelectorAll('input.filter').forEach(function(input){
              input.addEventListener('input',function(){
                var q=input.value.toLowerCase();
                document.querySelectorAll('#'+input.dataset.target+' tbody tr').forEach(function(tr){
                  tr.style.display=tr.textContent.toLowerCase().indexOf(q)>=0?'':'none';
                });
              });
            });
            """;
}
