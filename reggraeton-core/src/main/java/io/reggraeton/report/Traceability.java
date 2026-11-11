package io.reggraeton.report;

import io.reggraeton.model.TestCase;
import io.reggraeton.model.TestCaseAssessment;
import io.reggraeton.naming.TestNaming;

import java.util.List;

/**
 * {@code traceability.csv}: one row per existing test case (scenario or Examples row) with the
 * Playwright test generated for it in each mode and the command that runs only that test.
 */
public final class Traceability {

    public static final List<String> COLUMNS = List.of(
            "test_case_id", "feature_file", "feature", "rule", "scenario", "examples", "example_row_line", "tags",
            "status", "manual_items", "native_test", "native_command", "bdd_feature", "bdd_command");

    private Traceability() {
    }

    public static String csv(ReportData data) {
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", COLUMNS)).append("\r\n");
        for (ReportData.TestRow row : data.tests()) {
            TestCase tc = row.testCase();
            TestCaseAssessment a = row.assessment();
            TestNaming.GeneratedTest g = row.generated();
            List<String> values = List.of(
                    tc.id(), tc.feature(), nz(tc.featureName()), nz(tc.rule()), tc.scenarioName(), nz(tc.examplesName()),
                    tc.exampleLine() == null ? "" : String.valueOf(tc.exampleLine()), String.join(" ", tc.tags()),
                    a == null ? "" : a.status(), a == null ? "" : String.valueOf(a.manualItems()),
                    g.nativeName(), g.nativeCommand(), g.bddFeature() + ":" + g.bddLine(), g.bddCommand());
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    csv.append(',');
                }
                csv.append(field(values.get(i)));
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * RFC 4180 quoting; also neutralises spreadsheet formulas ({@code =}, {@code +}, {@code -}). A leading
     * {@code @} is kept because every tag starts with one and the values come from the scanned repository.
     */
    static String field(String value) {
        String v = value;
        if (!v.isEmpty() && "=+-".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
