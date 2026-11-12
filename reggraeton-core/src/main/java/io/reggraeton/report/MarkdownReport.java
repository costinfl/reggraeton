package io.reggraeton.report;

import io.reggraeton.model.Finding;
import io.reggraeton.model.ProjectLayout;
import io.reggraeton.model.TestCaseAssessment;


/** Plain Markdown version of the assessment report (readable in any Git web UI or terminal). */
public final class MarkdownReport {

    private MarkdownReport() {
    }

    public static String render(ReportData data) {
        StringBuilder md = new StringBuilder();
        ReportData.Totals t = data.totals();
        ProjectLayout layout = data.inventory().layout();

        md.append("# reggraeton assessment\n\n");
        md.append("Repository: `").append(layout.root()).append("`  \n");
        md.append("Generated: ").append(data.inventory().generatedAt()).append("\n\n");

        md.append("## Summary\n\n");
        md.append("| | |\n|---|---|\n");
        row(md, "Feature files", t.features() + (t.featuresWithErrors() > 0 ? " (" + t.featuresWithErrors() + " unparseable)" : ""));
        row(md, "Scenarios (outlines)", t.scenarios() + " (" + t.outlines() + ")");
        row(md, "Executable test cases → Playwright tests", String.valueOf(t.testCases()));
        row(md, "Ready / partial / blocked", t.ready() + " / " + t.partial() + " / " + t.blocked());
        row(md, "Step definitions / hooks", t.stepDefinitions() + " / " + t.hooks());
        row(md, "Project methods reached from steps", String.valueOf(t.reachableMethods()));
        row(md, "Call sites needing manual conversion", String.valueOf(t.manualSites()));
        row(md, "Findings (error / warning / info)", t.errors() + " / " + t.warnings() + " / " + t.infos());
        md.append("\n");
        md.append("*ready*: every reachable Selenium call has a translation rule. ")
                .append("*partial*: the test is generated, but some calls become `TODO(reggraeton)` stubs that fail until finished. ")
                .append("*blocked*: undefined or ambiguous steps; the test is generated and fails with the reason.\n\n");

        md.append("## Project layout\n\n");
        row(md, "Build tool", layout.buildTool(), true);
        row(md, "Java source roots", String.join(", ", layout.javaSourceRoots()), false);
        row(md, "Feature roots", String.join(", ", layout.featureRoots()), false);
        row(md, "Glue packages", layout.gluePackages().isEmpty() ? "(not declared: all packages)" : String.join(", ", layout.gluePackages()), false);
        row(md, "Runners", String.join(", ", layout.runners()), false);
        row(md, "Dependency injection", layout.dependencyInjection(), false);
        String libs = layout.libraries().entrySet().stream()
                .filter(e -> e.getKey().startsWith("io.cucumber") || e.getKey().startsWith("org.seleniumhq")
                        || e.getKey().startsWith("io.github.bonigarcia") || e.getKey().startsWith("org.junit")
                        || e.getKey().startsWith("org.testng") || e.getKey().startsWith("junit"))
                .map(e -> e.getKey().substring(e.getKey().indexOf(':') + 1) + " " + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("");
        row(md, "Test libraries", libs, false);
        md.append("\n");

        md.append("## Manual work by impact\n\n");
        if (data.impactByApi().isEmpty()) {
            md.append("No manual work.\n\n");
        } else {
            md.append("One call site in shared code can make many tests partial; *Tests affected* counts the test cases ")
                    .append("that reach it. Fix from the top.\n\n");
            md.append("| API | Call sites | Tests affected | What to do |\n|---|---:|---:|---|\n");
            for (ReportData.ImpactByApi row : data.impactByApi()) {
                md.append("| `").append(row.api()).append("` | ").append(row.sites()).append(" | ").append(row.testsAffected())
                        .append(" | ").append(cell(row.note())).append(" |\n");
            }
            int shown = Math.min(data.impactBySite().size(), 100);
            md.append("\nTop ").append(shown).append(" of ").append(data.impactBySite().size()).append(" call sites:\n\n");
            md.append("| Tests affected | API | Location | Code |\n|---:|---|---|---|\n");
            for (ReportData.ImpactBySite site : data.impactBySite().subList(0, shown)) {
                md.append("| ").append(site.testsAffected()).append(" | `").append(site.api()).append("` | `").append(site.file())
                        .append(':').append(site.line()).append("` | `").append(cell(site.code())).append("` |\n");
            }
            md.append('\n');
        }

        md.append("## Features\n\n");
        md.append("| Feature | File | Tests | Ready | Partial | Blocked |\n|---|---|---:|---:|---:|---:|\n");
        for (ReportData.FeatureRow f : data.features()) {
            md.append("| ").append(cell(f.error() != null ? "⚠ " + f.error() : f.name())).append(" | `").append(f.path())
                    .append("` | ").append(f.testCases()).append(" | ").append(f.ready()).append(" | ")
                    .append(f.partial()).append(" | ").append(f.blocked()).append(" |\n");
        }
        md.append("\n");

        md.append("## Test cases\n\n");
        md.append("| Status | Test case | Scenario | Native test | Manual items |\n|---|---|---|---|---:|\n");
        for (ReportData.TestRow r : data.tests()) {
            TestCaseAssessment a = r.assessment();
            md.append("| ").append(a.status()).append(" | `").append(r.testCase().id()).append("` | ")
                    .append(cell(r.testCase().name())).append(" | `").append(r.generated().nativeName()).append("` | ")
                    .append(a.manualItems()).append(" |\n");
        }
        md.append("\n");

        md.append("## Selenium and library API usage (reachable code)\n\n");
        md.append("| API | Category | Conversion | Call sites | Note |\n|---|---|---|---:|---|\n");
        for (ReportData.ApiRow api : data.apis()) {
            md.append("| `").append(api.api()).append("` | ").append(api.category()).append(" | ")
                    .append(api.support()).append(" | ").append(api.sites()).append(" | ").append(cell(api.note())).append(" |\n");
        }
        md.append("\n");

        md.append("## Step definitions\n\n");
        md.append("| Pattern | Style | Uses | Location |\n|---|---|---:|---|\n");
        for (ReportData.StepDefinitionRow s : data.stepDefinitions()) {
            md.append("| `").append(cell(s.definition().expression())).append("` | ").append(s.definition().style())
                    .append(" | ").append(s.uses()).append(" | `").append(s.definition().file()).append(":")
                    .append(s.definition().line()).append("` |\n");
        }
        md.append("\n");

        md.append("## Findings\n\n");
        if (data.findings().isEmpty()) {
            md.append("None.\n");
        } else {
            md.append("| Severity | Code | Location | Message |\n|---|---|---|---|\n");
            for (Finding f : data.findings()) {
                md.append("| ").append(f.severity()).append(" | ").append(f.code()).append(" | ")
                        .append(f.file() == null ? "" : "`" + f.file() + (f.line() > 0 ? ":" + f.line() : "") + "`")
                        .append(" | ").append(cell(f.message())).append(" |\n");
            }
        }
        return md.toString();
    }

    private static void row(StringBuilder md, String key, String value) {
        md.append("| ").append(key).append(" | ").append(cell(value)).append(" |\n");
    }

    private static void row(StringBuilder md, String key, String value, boolean header) {
        if (header) {
            md.append("| | |\n|---|---|\n");
        }
        row(md, key, value);
    }

    static String cell(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("|", "\\|").replace("\r", "").replace("\n", " ");
    }
}
