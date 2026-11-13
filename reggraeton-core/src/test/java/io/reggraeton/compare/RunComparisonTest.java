package io.reggraeton.compare;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.reggraeton.compare.RunComparison.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;

class RunComparisonTest {

    static void report(Path dir, String reportClass, String testcases) throws Exception {
        Path reports = dir.resolve("target/surefire-reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("TEST-" + reportClass + ".xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="%s" tests="1">
                %s
                </testsuite>
                """.formatted(reportClass, testcases));
    }

    static String pass(String cls, String name) {
        return "<testcase name=\"" + name + "\" classname=\"" + cls + "\" time=\"1.25\"/>";
    }

    static String fail(String cls, String name, String message) {
        return "<testcase name=\"" + name + "\" classname=\"" + cls + "\" time=\"2\"><failure message=\""
                + message + "\" type=\"AssertionError\">stack</failure></testcase>";
    }

    @Test
    void bucketsEveryTest(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("java11");
        Path b = dir.resolve("java17");
        report(a, "regression.features.LoginTest", String.join("\n",
                pass("Login", "successfulLogin()"),
                pass("Login", "rejectedLogin(int)[1]"),
                fail("Login", "rejectedLogin(int)[2]", "Timeout 30000ms exceeded waiting for #error"),
                fail("Login", "lockedOut()", "expected 3 but was 2"),
                fail("Login", "fixedInB()", "boom"),
                pass("Login", "onlyA()"),
                "<testcase name=\"skipped()\" classname=\"Login\"><skipped/></testcase>"));
        report(b, "regression.features.LoginTest", String.join("\n",
                pass("Login", "successfulLogin()"),
                fail("Login", "rejectedLogin(int)[1]", "Element #user not found"),
                fail("Login", "rejectedLogin(int)[2]", "Timeout 5000ms exceeded waiting for #error"),
                fail("Login", "lockedOut()", "NullPointerException in CartPage"),
                pass("Login", "fixedInB()"),
                pass("Login", "onlyB()"),
                pass("Login", "skipped()")));
        Files.writeString(a.resolve("traceability.csv"), """
                test_case_id,feature_file,native_test
                features/login.feature:12,features/login.feature,regression.features.LoginTest#rejectedLogin[1]
                "features/login.feature:5",features/login.feature,regression.features.LoginTest#successfulLogin
                """);

        RunComparison c = new RunComparison("java 11", TestRun.read(a), "java 17", TestRun.read(b));

        assertThat(c.rows()).extracting(r -> r.outcome() + " " + r.name()).containsExactly(
                "REGRESSION Login > rejectedLogin(int)[1]",
                "DIFFERENT_FAILURE Login > lockedOut()",
                "FIXED Login > fixedInB()",
                "ONLY_IN_A Login > onlyA()",
                "ONLY_IN_B Login > onlyB()",
                "SKIPPED Login > skipped()",
                "SAME_FAILURE Login > rejectedLogin(int)[2]",
                "PASSED Login > successfulLogin()");
        assertThat(c.rows().get(0).testCaseId()).isEqualTo("features/login.feature:12");
        assertThat(c.rows().get(7).testCaseId()).isEqualTo("features/login.feature:5");
        assertThat(c.rows().get(0).b().message()).isEqualTo("Element #user not found");
        assertThat(c.rows().get(7).a().seconds()).isEqualTo(1.25);
        assertThat(c.hasRegressions()).isTrue();
        assertThat(c.hasDifferences()).isTrue();
        assertThat(c.counts()).containsEntry(REGRESSION, 1).containsEntry(PASSED, 1);

        Path out = dir.resolve("out");
        ComparisonWriter.write(c, out);
        assertThat(Files.readString(out.resolve("compare.html"))).contains("java 11", "Element #user not found", "id=\"regression\"");
        assertThat(Files.readString(out.resolve("compare.md"))).contains("## Regression (1)", "`features/login.feature:12`");
        assertThat(Files.readString(out.resolve("compare.json"))).contains("\"outcome\" : \"regression\"");
        assertThat(ComparisonWriter.summary(c)).contains("regression", "fixed");
    }

    @Test
    void sameResultsHaveNoDifferences(@TempDir Path dir) throws Exception {
        report(dir.resolve("a"), "RunRegressionTest", fail("Shopping cart", "Example #1.1", "Step failed at 12:01:33 id=4711"));
        report(dir.resolve("b"), "RunRegressionTest", fail("Shopping cart", "Example #1.1", "Step failed at 09:15:02 id=99"));
        RunComparison c = new RunComparison("A", TestRun.read(dir.resolve("a")), "B", TestRun.read(dir.resolve("b")));
        assertThat(c.rows()).singleElement().extracting(RunComparison.Row::outcome).isEqualTo(SAME_FAILURE);
        assertThat(c.hasDifferences()).isFalse();
        assertThat(c.hasRegressions()).isFalse();
    }

    @Test
    void repeatedNamesAreMatchedByOccurrence(@TempDir Path dir) throws Exception {
        report(dir.resolve("a"), "Run", pass("Cart", "Add") + fail("Cart", "Add", "x"));
        report(dir.resolve("b"), "Run", pass("Cart", "Add") + pass("Cart", "Add"));
        RunComparison c = new RunComparison("A", TestRun.read(dir.resolve("a")), "B", TestRun.read(dir.resolve("b")));
        assertThat(c.rows()).extracting(RunComparison.Row::outcome).containsExactly(FIXED, PASSED);
    }

    @Test
    void normaliseMasksNoise() {
        assertThat(RunComparison.normalise("Timeout 30000ms at Foo@1a2b3c id 0f8fad5b-d9cb-469f-a165-70867728950e"))
                .isEqualTo("Timeout #ms at Foo# id #");
        assertThat(RunComparison.normalise(null)).isEmpty();
    }
}
