package io.reggraeton.report;

import io.reggraeton.Samples;
import io.reggraeton.model.Inventory;
import io.reggraeton.naming.TestNaming;
import io.reggraeton.scan.RepositoryScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportsTest {

    private static ReportData data;
    private static Inventory inventory;

    @BeforeAll
    static void scan() {
        inventory = new RepositoryScanner().scan(Samples.legacyShop());
        data = new ReportData(inventory, new TestNaming(inventory, null));
    }

    @Test
    void totalsMatchTheInventory() {
        ReportData.Totals t = data.totals();
        assertThat(t.testCases()).isEqualTo(12);
        assertThat(t.ready() + t.partial() + t.blocked()).isEqualTo(t.testCases());
        assertThat(t.outlines()).isEqualTo(1);
        assertThat(t.manualSites()).isEqualTo(4);
        assertThat(data.apis()).first().satisfies(api -> assertThat(api.support()).isEqualTo("manual"));
        assertThat(data.stepDefinitions()).filteredOn(s -> s.uses() == 0).hasSize(1);
    }

    @Test
    void ranksManualWorkByTestsAffected() {
        assertThat(data.impactByApi()).extracting(ReportData.ImpactByApi::api)
                .containsExactlyInAnyOrder("Logs.get", "WebDriver.Options.logs", "LogEntries.getAll");
        assertThat(data.impactByApi()).allSatisfy(row -> assertThat(row.testsAffected()).isEqualTo(1));
        assertThat(data.impactBySite()).hasSize(3).allSatisfy(site -> {
            assertThat(site.file()).endsWith("SearchSteps.java");
            assertThat(site.testsAffected()).isEqualTo(1);
        });

        String html = HtmlReport.render(data);
        assertThat(html).contains("id=\"impact\"").contains("Tests affected").contains("<details class=\"section\"")
                .contains("data-status=\"partial\"").doesNotContain("max-width:1180px");
        assertThat(MarkdownReport.render(data)).contains("## Manual work by impact").contains("| `Logs.get` | 1 | 1 |");
    }

    @Test
    void markdownAndHtmlEscapeContent() {
        String md = MarkdownReport.render(data);
        assertThat(md).contains("# reggraeton assessment").contains("| blocked |").contains("UNDEFINED_STEP");

        String html = HtmlReport.render(data);
        assertThat(html).contains("I log in as &quot;alice&quot;").doesNotContain("I log in as \"alice\" with");
        assertThat(HtmlReport.esc("<a href='x'>&</a>")).isEqualTo("&lt;a href=&#39;x&#39;&gt;&amp;&lt;/a&gt;");
    }

    @Test
    void callGraphExportFoldsOutlineRowsIntoOneScenario() {
        CallGraphExport.Export export = CallGraphExport.export(inventory, data.usedNodes());

        assertThat(export.scenarios()).hasSize(10);
        assertThat(export.scenarios()).filteredOn(s -> s.name().equals("Rejected login"))
                .singleElement().satisfies(s -> assertThat(s.testCaseIds()).hasSize(3));
        // the unused "I log out" step and its code are not part of the exported graph
        assertThat(export.nodes()).noneMatch(n -> n.id().endsWith("#iLogOut()"));
        assertThat(CallGraphExport.toDot(inventory, export)).contains("cluster_0").contains("-> \"com.example.shop.steps.LoginSteps#iLogInAs(String,String)\"");
    }

    @Test
    void csvFieldsAreQuotedAndSafe() {
        assertThat(Traceability.field("plain")).isEqualTo("plain");
        assertThat(Traceability.field("a,b")).isEqualTo("\"a,b\"");
        assertThat(Traceability.field("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(Traceability.field("=cmd()")).isEqualTo("'=cmd()");
    }
}
