package io.reggraeton.naming;

import io.reggraeton.Samples;
import io.reggraeton.TempRepo;
import io.reggraeton.model.Inventory;
import io.reggraeton.model.TestCase;
import io.reggraeton.scan.RepositoryScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestNamingTest {

    @Test
    void namesEveryTestCaseUniquely() {
        Inventory inventory = new RepositoryScanner().scan(Samples.legacyShop());
        TestNaming naming = new TestNaming(inventory, "com.acme.regression");

        List<TestNaming.GeneratedTest> tests = naming.all(inventory.testCases());

        assertThat(tests).extracting(TestNaming.GeneratedTest::nativeName).doesNotHaveDuplicates();
        assertThat(tests).extracting(TestNaming.GeneratedTest::nativeName).contains(
                "com.acme.regression.features.LoginTest#successfulLogin",
                "com.acme.regression.features.LoginTest#rejectedLogin[1]",
                "com.acme.regression.features.LoginTest#rejectedLogin[3]",
                "com.acme.regression.features.shop.ShoppingCartTest#addASingleProduct");

        TestCase outlineRow = inventory.testCases().get(3);
        TestNaming.GeneratedTest g = naming.of(outlineRow);
        assertThat(g.bddFeature()).isEqualTo("src/test/resources/features/login.feature");
        assertThat(g.bddLine()).isEqualTo(outlineRow.exampleLine());
        assertThat(g.bddCommand()).isEqualTo("mvn test -Dcucumber.features=src/test/resources/features/login.feature:"
                + outlineRow.exampleLine());
        assertThat(g.nativeCommand()).isEqualTo("mvn test -Dtest=LoginTest#rejectedLogin");
    }

    @Test
    void sanitisesIdentifiersAndResolvesCollisions(@TempDir Path dir) {
        new TempRepo(dir)
                .file("features/a/one.feature", """
                        Feature: Café & Crème
                          Scenario: 1st class
                            Given x
                          Scenario: 1st class
                            Given x
                          Scenario: return
                            Given x
                        """)
                .file("features/b/two.feature", """
                        Feature: café crème
                          Scenario: s
                            Given x
                        """)
                .file("features/new/three.feature", """
                        Feature:
                          Scenario: s
                            Given x
                        """);
        Inventory inventory = new RepositoryScanner().scan(dir);
        TestNaming naming = new TestNaming(inventory, null);

        assertThat(naming.classFor("features/a/one.feature")).isEqualTo("regression.features.a.CafeCremeTest");
        assertThat(naming.classFor("features/b/two.feature")).isEqualTo("regression.features.b.CafeCremeTest");
        assertThat(naming.classFor("features/new/three.feature")).isEqualTo("regression.features._new.ThreeTest");
        assertThat(naming.bddFeaturePath("features/b/two.feature")).isEqualTo("src/test/resources/features/b/two.feature");
        assertThat(naming.methodFor("features/a/one.feature", 2)).isEqualTo("scenario1stClass");
        assertThat(naming.methodFor("features/a/one.feature", 4)).isEqualTo("scenario1stClass_2");
        assertThat(naming.methodFor("features/a/one.feature", 6)).isEqualTo("returnScenario");
    }

    @Test
    void multiModuleFeaturePathsStayUnique(@TempDir Path dir) {
        new TempRepo(dir)
                .file("shop/src/test/resources/features/login.feature", "Feature: Shop login\n  Scenario: s\n    Given x\n")
                .file("admin/src/test/resources/features/login.feature", "Feature: Admin login\n  Scenario: s\n    Given x\n");
        Inventory inventory = new RepositoryScanner().scan(dir);
        TestNaming naming = new TestNaming(inventory, null);

        assertThat(naming.bddFeaturePath("shop/src/test/resources/features/login.feature"))
                .isEqualTo("src/test/resources/features/shop/features/login.feature");
        assertThat(naming.classFor("admin/src/test/resources/features/login.feature"))
                .isEqualTo("regression.features.admin.features.AdminLoginTest");
        assertThat(TestNaming.commonDirectory(List.of("a/b/c.feature", "a/b/d/e.feature"))).isEqualTo("a/b");
        assertThat(TestNaming.commonDirectory(List.of("x.feature", "a/y.feature"))).isEmpty();
    }

    @Test
    void identifierHelpers() {
        assertThat(TestNaming.typeName("shopping cart")).isEqualTo("ShoppingCart");
        assertThat(TestNaming.typeName("42 things")).isEqualTo("F42Things");
        assertThat(TestNaming.memberName("Add a SINGLE product")).isEqualTo("addASingleProduct");
        assertThat(TestNaming.memberName("!!!")).isEqualTo("scenario");
    }
}
