package io.reggraeton.scan.db;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTest {

    @Test
    void classifiesOracleStatements() {
        assertThat(Sql.operations("SELECT order_seq.NEXTVAL FROM dual", true)).containsExactly(Sql.NEXTVAL, Sql.DUAL);
        assertThat(Sql.operations("select sysdate from dual", true)).containsExactly(Sql.DUAL);
        assertThat(Sql.operations("select to_char(sysdate, 'YYYY') from dual", true)).containsExactly(Sql.DUAL);
        assertThat(Sql.operations("SELECT pkg_orders.create_order(?, ?) FROM DUAL", true)).containsExactly(Sql.FUNCTION, Sql.DUAL);
        assertThat(Sql.operations("SELECT NEXT VALUE FOR seq FROM t", true)).contains(Sql.NEXTVAL);
        assertThat(Sql.operations("insert into orders (id) values (order_seq.nextval)", true)).containsExactly(Sql.INSERT, Sql.NEXTVAL);
        assertThat(Sql.operations("UPDATE orders o SET o.status = ? WHERE o.id = ?", true)).containsExactly(Sql.UPDATE);
        assertThat(Sql.operations("delete from cart_items where user_name = ?", true)).containsExactly(Sql.DELETE);
        assertThat(Sql.operations("MERGE INTO stock s USING dual ON (s.id = 1) WHEN MATCHED THEN UPDATE SET s.qty = 1", true))
                .contains(Sql.MERGE);
        assertThat(Sql.operations("TRUNCATE TABLE audit_log", true)).containsExactly(Sql.TRUNCATE);
        assertThat(Sql.operations("{call shop_admin.reset_stock(?, ?)}", true)).containsExactly(Sql.CALL);
        assertThat(Sql.operations("{? = call shop_admin.next_id()}", true)).containsExactly(Sql.CALL);
        assertThat(Sql.operations("BEGIN shop_admin.reset(); END;", true)).containsExactly(Sql.PLSQL);
        assertThat(Sql.operations("CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON t", true)).contains(Sql.DDL);
        assertThat(Sql.operations("SELECT name FROM products WHERE id = ?", true)).containsExactly(Sql.SELECT);
        assertThat(Sql.operations("FLASHBACK TABLE orders TO RESTORE POINT before_run", true)).containsExactly(Sql.FLASHBACK);
        assertThat(Sql.operations("COMMIT", true)).containsExactly(Sql.COMMIT);
    }

    @Test
    void ignoresProse() {
        assertThat(Sql.operations("Select a product from the list", true)).isEmpty();
        assertThat(Sql.operations("I delete from the cart", false)).isEmpty();
        assertThat(Sql.operations("Update the cart", true)).isEmpty();
        assertThat(Sql.operations("call support", true)).isEmpty();
        assertThat(Sql.operations("The order was created", true)).isEmpty();
        assertThat(Sql.operations("Expected SELECT x FROM t", true)).isEmpty(); // literals must start with SQL
    }

    @Test
    void writesIncludeSequencesAndFunctions() {
        assertThat(Sql.writes(List.of(Sql.NEXTVAL, Sql.DUAL))).isTrue();
        assertThat(Sql.writes(List.of(Sql.FUNCTION, Sql.DUAL))).isTrue();
        assertThat(Sql.writes(List.of(Sql.DUAL))).isFalse();
        assertThat(Sql.writes(List.of(Sql.SELECT))).isFalse();
    }

    @Test
    void masksCredentials() {
        assertThat(Sql.display("CREATE USER t IDENTIFIED BY secret1\n  DEFAULT TABLESPACE users", 300))
                .isEqualTo("CREATE USER t IDENTIFIED BY *** DEFAULT TABLESPACE users");
        assertThat(Connections.mask("jdbc:oracle:thin:scott/tiger@//db:1521/SVC")).isEqualTo("jdbc:oracle:thin:scott/***@//db:1521/SVC");
        assertThat(Connections.mask("jdbc:postgresql://db/x?user=a&password=b")).isEqualTo("jdbc:postgresql://db/x?user=a&password=***");
        assertThat(Connections.mask("jdbc:mysql://bob:pw@db/x")).isEqualTo("jdbc:mysql://bob:***@db/x");
    }

    @Test
    void findsConnectionStringsAndSettings() {
        assertThat(Connections.find("String URL = \"jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=h)(PORT=1521)))\");", false))
                .extracting(Connections.Found::value).containsExactly("jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=h)(PORT=1521)))");
        assertThat(Connections.find("spring.datasource.password=s3cret", true)).containsExactly(
                new Connections.Found("spring.datasource.password", "***"));
        assertThat(Connections.find("db.password=${DB_PASSWORD}", true)).extracting(Connections.Found::value)
                .containsExactly("${DB_PASSWORD}");
        assertThat(Connections.find("  <db.user>shop</db.user>", true)).containsExactly(new Connections.Found("db.user", "shop"));
        assertThat(Connections.find("app.title=Shop", true)).isEmpty();
        assertThat(Connections.find("# db.url=jdbc:oracle:thin:@old", true)).extracting(Connections.Found::key).containsOnlyNulls();
    }
}
