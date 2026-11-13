package com.example.shop.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Test data helper. Does nothing unless -Ddb.url is set (the demo shop keeps its data in the
 * browser), so the suite also runs without a database.
 */
public final class Database {

    private static final String NEXT_ORDER_ID = "SELECT order_seq.NEXTVAL FROM dual";

    private Database() {
    }

    public static void execute(String sql) {
        String url = System.getProperty("db.url");
        if (url == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(url, System.getProperty("db.user"), System.getProperty("db.password"));
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Test data setup failed: " + sql, e);
        }
    }

    public static long nextOrderId() {
        String url = System.getProperty("db.url");
        if (url == null) {
            return 1;
        }
        try (Connection connection = DriverManager.getConnection(url, System.getProperty("db.user"), System.getProperty("db.password"));
             PreparedStatement statement = connection.prepareStatement(NEXT_ORDER_ID);
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the order sequence", e);
        }
    }
}
