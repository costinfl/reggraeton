package io.reggraeton.scan.db;

import io.reggraeton.Samples;
import io.reggraeton.TempRepo;
import io.reggraeton.model.CallGraph.ApiUsage;
import io.reggraeton.model.DatabaseInventory;
import io.reggraeton.model.Finding;
import io.reggraeton.model.Inventory;
import io.reggraeton.naming.TestNaming;
import io.reggraeton.report.ReportData;
import io.reggraeton.scan.RepositoryScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseScannerTest {

    @Test
    void findsDatabaseWorkInTheSampleSuite() {
        Inventory inventory = new RepositoryScanner().scan(Samples.legacyShop());
        DatabaseInventory db = inventory.database();

        assertThat(db.connections()).extracting(DatabaseInventory.Connection::key, DatabaseInventory.Connection::value)
                .contains(org.assertj.core.groups.Tuple.tuple("db.url", "jdbc:oracle:thin:@//shop-db.example.com:1521/SHOPTEST"),
                        org.assertj.core.groups.Tuple.tuple("db.password", "***"));
        assertThat(db.statements()).filteredOn(s -> s.file().endsWith("Database.java")).singleElement().satisfies(s -> {
            assertThat(s.operations()).containsExactly(Sql.NEXTVAL, Sql.DUAL);
            assertThat(s.writes()).isTrue();
            assertThat(s.nodeId()).isEqualTo("com.example.shop.support.Database#nextOrderId()"); // where the constant is used
        });
        assertThat(db.statements()).filteredOn(s -> s.file().endsWith("reset.sql"))
                .extracting(s -> s.operations().get(0))
                .containsExactly(Sql.DELETE, Sql.INSERT, Sql.PLSQL, Sql.FUNCTION, Sql.COMMIT);
        assertThat(inventory.findings()).filteredOn(f -> f.code().equals("DATABASE_HOOK")).singleElement()
                .extracting(Finding::message).asString().contains("@Before hook Hooks#clearCart()", "@cart", "NEXTVAL", "changes data");

        ReportData report = new ReportData(inventory, new TestNaming(inventory, "regression"));
        assertThat(report.testsReachingDatabase()).isEqualTo(5);
        assertThat(report.databaseEntries()).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("@Before hook");
            assertThat(e.tests()).isEqualTo(5);
            assertThat(e.reach().summary()).isEqualTo("DELETE, NEXTVAL, SELECT FROM DUAL; JDBC (Statement.executeUpdate)");
        });
    }

    @Test
    void classifiesLibrariesAndMasksPasswordsInCode(@TempDir Path dir) {
        new TempRepo(dir).java("x", "Data", """
                import io.cucumber.java.After;
                import io.cucumber.java.en.Given;
                import java.sql.Connection;
                import java.sql.DriverManager;
                import org.springframework.jdbc.core.JdbcTemplate;

                public class Data {
                    private JdbcTemplate jdbc;

                    @Given("an order exists")
                    public void order() throws Exception {
                        jdbc.update("INSERT INTO orders (id) VALUES (order_seq.NEXTVAL)");
                        Long id = jdbc.queryForObject("SELECT " + "order_seq.CURRVAL FROM dual", Long.class);
                    }

                    @After("@orders")
                    public void cleanup() throws Exception {
                        try (Connection c = DriverManager.getConnection("jdbc:oracle:thin:@db:1521/X", "app", "hunter2")) {
                            c.prepareCall("{call test_data.cleanup(?)}").execute();
                        }
                    }
                }
                """).file("src/test/resources/features/o.feature", """
                Feature: Orders
                  @orders
                  Scenario: Order
                    Given an order exists
                """);
        Inventory inventory = new RepositoryScanner().scan(dir);

        var usages = inventory.callGraph().nodes().stream().flatMap(n -> n.apiUsages().stream())
                .filter(u -> ApiUsage.DATABASE.equals(u.category())).toList();
        assertThat(usages).extracting(ApiUsage::api).contains("SQL INSERT", "SQL CURRVAL", "SQL CALL", "DriverManager.getConnection");
        assertThat(usages).filteredOn(u -> u.api().equals("DriverManager.getConnection")).singleElement()
                .satisfies(u -> assertThat(u.code()).contains("\"***\"").doesNotContain("hunter2"));
        assertThat(usages).allMatch(u -> u.support().equals(ApiUsage.AUTO));

        ReportData report = new ReportData(inventory, new TestNaming(inventory, "regression"));
        assertThat(report.databaseEntries()).extracting(ReportData.DatabaseEntry::kind).containsExactly("@After hook", "step");
        assertThat(report.databaseEntries().get(0).reach().writes()).isTrue();
        assertThat(inventory.database().connections()).extracting(DatabaseInventory.Connection::value)
                .containsExactly("jdbc:oracle:thin:@db:1521/X");
    }
}
