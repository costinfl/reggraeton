package io.reggraeton.generate;

import io.reggraeton.model.Inventory;
import io.reggraeton.model.TestCaseAssessment;
import io.reggraeton.report.ReportData;
import io.reggraeton.translate.TranslatedSource;
import io.reggraeton.translate.Translator;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** {@code conversion-report.md} of a generated project. */
final class ConversionReport {

    private ConversionReport() {
    }

    static String render(ConvertOptions options, Inventory inventory, ReportData report, Translator.Result translation,
                         List<TranslatedSource.Todo> todos, List<String> problems, List<String> missing, int generated) {
        StringBuilder md = new StringBuilder();
        md.append("# Conversion report\n\n");
        md.append("Source repository: `").append(inventory.layout().root()).append("`  \n");
        md.append("Mode: **").append(options.mode().name().toLowerCase()).append("**  \n");
        md.append("Scanned: ").append(inventory.generatedAt()).append("\n\n");

        md.append("## Coverage\n\n");
        md.append("| | |\n|---|---|\n");
        md.append("| Original test cases (scenarios + Examples rows) | ").append(inventory.testCases().size()).append(" |\n");
        md.append("| Generated tests | ").append(generated).append(" |\n");
        md.append("| Without a generated test | ").append(missing.size()).append(" |\n");
        ReportData.Totals t = report.totals();
        md.append("| Ready / partial / blocked | ").append(t.ready()).append(" / ").append(t.partial()).append(" / ")
                .append(t.blocked()).append(" |\n");
        md.append("| Translated source files | ").append(translation.sources().size()).append(" |\n");
        md.append("| `TODO(reggraeton)` places | ").append(todos.size()).append(" |\n\n");
        if (!missing.isEmpty()) {
            md.append("**Not generated:** ").append(String.join(", ", missing)).append("\n\n");
        }

        md.append("## Manual work (`TODO(reggraeton)`)\n\n");
        if (todos.isEmpty()) {
            md.append("None: every reachable Selenium call had a translation rule.\n\n");
        } else {
            md.append("Each place throws `PendingConversionException` until rewritten, so affected tests fail.\n\n");
            md.append("| Original location | API | What to do |\n|---|---|---|\n");
            for (TranslatedSource.Todo todo : todos) {
                md.append("| `").append(todo.originalFile()).append(':').append(todo.line()).append("` | `")
                        .append(todo.api()).append("` | ").append(cell(todo.note())).append(" |\n");
            }
            md.append('\n');
        }

        md.append("## Tests that fail by design\n\n");
        List<ReportData.TestRow> blocked = report.tests().stream()
                .filter(r -> r.assessment() != null && !TestCaseAssessment.READY.equals(r.assessment().status()))
                .toList();
        if (blocked.isEmpty()) {
            md.append("None.\n\n");
        } else {
            md.append("| Test case | Status | Generated test | Reason |\n|---|---|---|---|\n");
            for (ReportData.TestRow r : blocked) {
                md.append("| `").append(r.testCase().id()).append("` | ").append(r.assessment().status()).append(" | `")
                        .append(options.mode() == ConvertOptions.Mode.NATIVE ? r.generated().nativeName()
                                : r.generated().bddFeature() + ":" + r.generated().bddLine())
                        .append("` | ").append(cell(String.join("; ", r.assessment().issues()))).append(" |\n");
            }
            md.append('\n');
        }

        if (!problems.isEmpty()) {
            md.append("## Other problems\n\n");
            problems.forEach(p -> md.append("- ").append(p).append('\n'));
            md.append('\n');
        }

        md.append("## Applied rewrites\n\n");
        Map<String, Integer> totals = new TreeMap<>();
        translation.sources().forEach(s -> s.changes().forEach((k, v) -> totals.merge(k, v, Integer::sum)));
        md.append("| Rewrite | Count |\n|---|---:|\n");
        totals.forEach((k, v) -> md.append("| ").append(cell(k)).append(" | ").append(v).append(" |\n"));
        md.append('\n');

        md.append("## Translated files\n\n");
        md.append("| Original | Generated |\n|---|---|\n");
        translation.sources().forEach(s -> md.append("| `").append(s.originalPath()).append("` | `src/test/java/")
                .append(s.relativePath()).append("` |\n"));
        if (!translation.skipped().isEmpty()) {
            md.append("\nNot copied (Cucumber runners, replaced by the generated ones): ")
                    .append(String.join(", ", translation.skipped())).append('\n');
        }
        return md.toString();
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }
}
