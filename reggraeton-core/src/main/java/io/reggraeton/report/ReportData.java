package io.reggraeton.report;

import io.reggraeton.model.CallGraph;
import io.reggraeton.model.CallGraph.ApiUsage;
import io.reggraeton.model.FeatureFile;
import io.reggraeton.model.Finding;
import io.reggraeton.model.Inventory;
import io.reggraeton.model.StepDefinition;
import io.reggraeton.model.TestCase;
import io.reggraeton.model.TestCaseAssessment;
import io.reggraeton.model.TestStep;
import io.reggraeton.naming.TestNaming;
import io.reggraeton.scan.Assessor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Aggregates of an {@link Inventory} shared by the Markdown and HTML reports. */
public final class ReportData {

    public record Totals(int features, int featuresWithErrors, int scenarios, int outlines, int testCases,
                         int stepDefinitions, int hooks, int ready, int partial, int blocked,
                         int errors, int warnings, int infos, int manualSites, int reachableMethods) {
    }

    public record FeatureRow(String path, String name, int testCases, int ready, int partial, int blocked,
                             String error) {
    }

    public record TestRow(TestCase testCase, TestCaseAssessment assessment, TestNaming.GeneratedTest generated) {
    }

    public record ApiRow(String api, String category, String support, int sites, String note) {
    }

    public record StepDefinitionRow(StepDefinition definition, int uses) {
    }

    /**
     * Manual work grouped by API: how many call sites, and how many test cases reach at least one of
     * them (fixing all sites of this API makes that many tests stop depending on it).
     */
    public record ImpactByApi(String api, String category, String note, int sites, int testsAffected) {
    }

    /** One call site needing manual work and the number of test cases whose code reaches it. */
    public record ImpactBySite(String api, String file, int line, String code, String note, int testsAffected) {
    }

    private final Inventory inventory;
    private final TestNaming naming;
    private final Totals totals;
    private final List<FeatureRow> features = new ArrayList<>();
    private final List<TestRow> tests = new ArrayList<>();
    private final List<ApiRow> apis = new ArrayList<>();
    private final List<StepDefinitionRow> stepDefinitions = new ArrayList<>();
    private final Set<String> usedNodes = new LinkedHashSet<>();
    private final List<ImpactByApi> impactByApi = new ArrayList<>();
    private final List<ImpactBySite> impactBySite = new ArrayList<>();

    public ReportData(Inventory inventory, TestNaming naming) {
        this.inventory = inventory;
        this.naming = naming;

        Map<String, TestCaseAssessment> assessments = inventory.assessments().stream()
                .collect(Collectors.toMap(TestCaseAssessment::testCaseId, a -> a, (a, b) -> a, LinkedHashMap::new));
        for (TestCase tc : inventory.testCases()) {
            tests.add(new TestRow(tc, assessments.get(tc.id()), naming.of(tc)));
        }

        for (FeatureFile f : inventory.features()) {
            List<TestRow> rows = tests.stream().filter(t -> t.testCase().feature().equals(f.path())).collect(Collectors.toList());
            features.add(new FeatureRow(f.path(), f.name(), rows.size(),
                    count(rows, TestCaseAssessment.READY), count(rows, TestCaseAssessment.PARTIAL),
                    count(rows, TestCaseAssessment.BLOCKED), f.parseError()));
        }

        Assessor assessor = new Assessor(inventory.callGraph());
        for (TestCaseAssessment a : inventory.assessments()) {
            a.stepDefinitionIds().forEach(id -> usedNodes.addAll(assessor.reachable(id)));
            a.hookIds().forEach(id -> usedNodes.addAll(assessor.reachable(id)));
        }
        computeImpact(assessor);

        Map<String, int[]> siteCounts = new TreeMap<>();
        Map<String, ApiUsage> sample = new HashMap<>();
        for (CallGraph.Node node : inventory.callGraph().nodes()) {
            if (!usedNodes.contains(node.id())) {
                continue;
            }
            for (ApiUsage u : node.apiUsages()) {
                String key = u.api() + "|" + u.support();
                siteCounts.computeIfAbsent(key, k -> new int[1])[0]++;
                sample.putIfAbsent(key, u);
            }
        }
        siteCounts.forEach((key, count) -> {
            ApiUsage u = sample.get(key);
            apis.add(new ApiRow(u.api(), u.category(), u.support(), count[0], u.note()));
        });
        apis.sort(Comparator.comparing(ApiRow::support).reversed().thenComparing(ApiRow::sites, Comparator.reverseOrder())
                .thenComparing(ApiRow::api));

        Map<String, Integer> uses = new HashMap<>();
        for (TestCase tc : inventory.testCases()) {
            for (TestStep step : tc.steps()) {
                if (step.match() != null) {
                    step.match().stepDefinitionIds().forEach(id -> uses.merge(id, 1, Integer::sum));
                }
            }
        }
        for (StepDefinition d : inventory.stepDefinitions()) {
            stepDefinitions.add(new StepDefinitionRow(d, uses.getOrDefault(d.id(), 0)));
        }

        long manualSites = apis.stream().filter(a -> ApiUsage.MANUAL.equals(a.support())).mapToInt(ApiRow::sites).sum();
        long reachableMethods = inventory.callGraph().nodes().stream()
                .filter(n -> usedNodes.contains(n.id()) && !"class".equals(n.kind())).count();
        this.totals = new Totals(
                inventory.features().size(),
                (int) inventory.features().stream().filter(f -> f.parseError() != null).count(),
                inventory.features().stream().mapToInt(f -> f.scenarios().size()).sum(),
                (int) inventory.features().stream().flatMap(f -> f.scenarios().stream()).filter(s -> s.outline()).count(),
                inventory.testCases().size(),
                inventory.stepDefinitions().size(),
                inventory.hooks().size(),
                count(tests, TestCaseAssessment.READY),
                count(tests, TestCaseAssessment.PARTIAL),
                count(tests, TestCaseAssessment.BLOCKED),
                severity("error"), severity("warning"), severity("info"),
                (int) manualSites, (int) reachableMethods);
    }

    /**
     * For every manual call site reachable from some test case, the test cases that reach it. A site in
     * shared code (a base page, a hook) can make thousands of tests "partial"; this ranks what to fix first.
     */
    private void computeImpact(Assessor assessor) {
        Map<String, CallGraph.Node> nodesById = new HashMap<>();
        inventory.callGraph().nodes().forEach(n -> nodesById.put(n.id(), n));
        Map<String, java.util.BitSet> testsBySite = new HashMap<>();
        Map<String, java.util.BitSet> testsByApi = new HashMap<>();
        Map<String, ImpactBySite> siteInfo = new HashMap<>();
        Map<String, ApiUsage> apiInfo = new HashMap<>();
        Map<String, Integer> sitesPerApi = new HashMap<>();
        for (int t = 0; t < tests.size(); t++) {
            TestCaseAssessment a = tests.get(t).assessment();
            if (a == null) {
                continue;
            }
            Set<String> reached = new LinkedHashSet<>();
            a.stepDefinitionIds().forEach(id -> reached.addAll(assessor.reachable(id)));
            a.hookIds().forEach(id -> reached.addAll(assessor.reachable(id)));
            for (String nodeId : reached) {
                CallGraph.Node node = nodesById.get(nodeId);
                if (node == null) {
                    continue;
                }
                for (ApiUsage u : node.apiUsages()) {
                    if (!ApiUsage.MANUAL.equals(u.support())) {
                        continue;
                    }
                    String site = node.file() + ":" + u.line() + "|" + u.api();
                    if (!siteInfo.containsKey(site)) {
                        siteInfo.put(site, new ImpactBySite(u.api(), node.file(), u.line(), u.code(), u.note(), 0));
                        sitesPerApi.merge(u.api(), 1, Integer::sum);
                        apiInfo.putIfAbsent(u.api(), u);
                    }
                    testsBySite.computeIfAbsent(site, k -> new java.util.BitSet()).set(t);
                    testsByApi.computeIfAbsent(u.api(), k -> new java.util.BitSet()).set(t);
                }
            }
        }
        siteInfo.forEach((site, info) -> impactBySite.add(new ImpactBySite(info.api(), info.file(), info.line(),
                info.code(), info.note(), testsBySite.get(site).cardinality())));
        impactBySite.sort(Comparator.comparingInt(ImpactBySite::testsAffected).reversed()
                .thenComparing(ImpactBySite::file).thenComparingInt(ImpactBySite::line));
        apiInfo.forEach((api, u) -> impactByApi.add(new ImpactByApi(api, u.category(), u.note(), sitesPerApi.get(api),
                testsByApi.get(api).cardinality())));
        impactByApi.sort(Comparator.comparingInt(ImpactByApi::testsAffected).reversed()
                .thenComparing(ImpactByApi::sites, Comparator.reverseOrder()).thenComparing(ImpactByApi::api));
    }

    public List<ImpactByApi> impactByApi() {
        return impactByApi;
    }

    public List<ImpactBySite> impactBySite() {
        return impactBySite;
    }

    private static int count(List<TestRow> rows, String status) {
        return (int) rows.stream().filter(r -> r.assessment() != null && status.equals(r.assessment().status())).count();
    }

    private int severity(String severity) {
        return (int) inventory.findings().stream().filter(f -> severity.equals(f.severity())).count();
    }

    public Inventory inventory() {
        return inventory;
    }

    public TestNaming naming() {
        return naming;
    }

    public Totals totals() {
        return totals;
    }

    public List<FeatureRow> features() {
        return features;
    }

    public List<TestRow> tests() {
        return tests;
    }

    public List<ApiRow> apis() {
        return apis;
    }

    public List<StepDefinitionRow> stepDefinitions() {
        return stepDefinitions;
    }

    public Set<String> usedNodes() {
        return usedNodes;
    }

    /** Findings ordered by severity (errors first), then file and line. */
    public List<Finding> findings() {
        List<String> order = List.of("error", "warning", "info");
        return inventory.findings().stream()
                .sorted(Comparator.comparingInt((Finding f) -> order.indexOf(f.severity()))
                        .thenComparing(f -> f.file() == null ? "" : f.file())
                        .thenComparingInt(Finding::line))
                .collect(Collectors.toList());
    }
}
