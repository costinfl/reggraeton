package io.reggraeton.scan;

import io.reggraeton.Samples;
import io.reggraeton.model.Inventory;
import io.reggraeton.model.TestCase;
import io.reggraeton.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryScannerTest {

    @Test
    void everyScenarioAndExamplesRowBecomesOneTestCase() {
        Inventory inventory = new RepositoryScanner().scan(Samples.legacyShop());

        assertThat(inventory.features()).hasSize(3);
        // login: 1 scenario + 3 outline rows, cart: 3, search: 3
        assertThat(inventory.testCases()).hasSize(10);
        assertThat(inventory.testCases()).extracting(TestCase::id).doesNotHaveDuplicates();
    }

    @Test
    void inventoryRoundTripsThroughJson(@TempDir Path dir) {
        Inventory inventory = new RepositoryScanner().scan(Samples.legacyShop());
        Path file = dir.resolve("inventory.json");

        Json.write(file, inventory);
        Inventory back = Json.read(file, Inventory.class);

        assertThat(back).isEqualTo(inventory);
    }
}
